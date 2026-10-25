package com.doclayout.recognize.data.local

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * UVDoc 卷曲矫正（拍照裁剪的阶段 B 增强；data 层，无 Compose）。
 *
 * 1. 属于什么文件
 *    - `data/local/UvdocModel.kt`（Kotlin 门面）+ native `app/src/main/cpp/uvdoc_unwarp.cpp`
 *      （MNN 推理与前后处理，前处理对译自 ModelScope dr3334/UVDoc-mnn 仓库的 uvdoc_math.cpp）。
 * 2. 用了什么组件
 *    - MNN（复用引擎已链接的运行库）；HttpURLConnection 直连 ModelScope raw 下载。
 * 3. 职责与边界
 *    - 模型是 8.3MB INT8 混合精度（uvdoc_int8_baseline_floathead_kl.mnn），
 *      **不是**识别引擎的必需件——就绪判定（model-profiles.json）不含它，
 *      缺失只影响「卷曲矫正」开关，绝不影响识别可用性。
 *    - 下载带 SHA-256 校验（哈希来自仓库 SHA256SUMS/README），损坏即删可重下。
 */
object UvdocModel {

    private const val REPO = "dr3334/UVDoc-mnn"
    private const val FILE_NAME = "uvdoc_int8_baseline_floathead_kl.mnn"
    private const val SIZE = 8_262_224L
    private const val SHA256 = "7acbe44d83a97929854d5ff683bf573f3c1e4d78cbc682023442f440a8a0b421"
    private const val URL_RAW =
        "https://modelscope.cn/api/v1/models/$REPO/repo?Revision=master&FilePath=$FILE_NAME"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models/uvdoc"), FILE_NAME)

    /** 快速判定：文件存在且大小正确（完整哈希只在下载完成时校验一次） */
    fun isDownloaded(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() == SIZE }

    /**
     * 下载模型（不支持断点续传——8.3MB 一次性拉完即可）；进度回调 0..1。
     * 成功返回模型文件；失败/校验不过删除残留并抛异常（文案可直接展示）。
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE_NAME.part")
        runCatching { tmp.delete() }
        try {
            val conn = URL(URL_RAW).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "DocLayoutRecognize")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("ModelScope HTTP $code")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress((read.toFloat() / SIZE).coerceAtMost(1f))
                    }
                }
            }
            if (tmp.length() != SIZE) throw IllegalStateException("下载不完整（${tmp.length()}/$SIZE 字节）")
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(tmp).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(SHA256, ignoreCase = true)) throw IllegalStateException("SHA-256 校验失败，请重试")
            if (!tmp.renameTo(target)) throw IllegalStateException("保存模型文件失败")
            target
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    /**
     * 对一张已透视校正的图跑 UVDoc 展平；输出写到输入同目录的临时 JPEG（长边 ≤1600，
     * 网格放大本就平滑，对识别链足够）。模型未就绪 / 推理失败返回 null
     * （调用方回退用未矫正图，不阻断识别链）。
     */
    suspend fun unwarp(context: Context, input: File): File? = withContext(Dispatchers.IO) {
        if (!isDownloaded(context)) return@withContext null
        // 解码到 ≤1600（内存安全：全流水线 float/uint8 缓冲都在这个尺寸域）
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(input.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1600) sample *= 2
        val src = android.graphics.BitmapFactory.decodeFile(
            input.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null
        val normalized = if (maxOf(src.width, src.height) > 1600) {
            val s = 1600f / maxOf(src.width, src.height)
            android.graphics.Bitmap.createScaledBitmap(
                src, (src.width * s).toInt().coerceAtLeast(2), (src.height * s).toInt().coerceAtLeast(2), true,
            ).also { if (it !== src) src.recycle() }
        } else src
        val out = android.graphics.Bitmap.createBitmap(normalized.width, normalized.height, android.graphics.Bitmap.Config.ARGB_8888)
        val ok = runCatching {
            UvdocUnwarp.nativeUnwarp(modelFile(context).absolutePath, normalized, out)
        }.getOrDefault(false)
        normalized.recycle()
        if (!ok) { out.recycle(); return@withContext null }
        val target = File.createTempFile("crop_uvdoc_", ".jpg", input.parentFile)
        target.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        target
    }
}

/** native 桥：实现见 app/src/main/cpp/uvdoc_unwarp.cpp（与引擎同一个 dococr_jni 库） */
object UvdocUnwarp {
    init {
        // 引擎侧 cn.local.ocr.NativeOcr 也会加载同名库；System.loadLibrary 幂等
        System.loadLibrary("dococr_jni")
    }

    /** src（RGBA，长边 ≤1600）→ 展平结果写入同尺寸 out；false = 失败（调用方回退原图） */
    @JvmStatic
    external fun nativeUnwarp(modelPath: String, src: android.graphics.Bitmap, out: android.graphics.Bitmap): Boolean
}
