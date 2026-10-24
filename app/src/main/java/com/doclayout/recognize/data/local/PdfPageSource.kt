package com.doclayout.recognize.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PDF → PNG 逐页栅格化（WP5「导入文件识别」的 PDF 接入）。
 *
 * 1. 属于什么文件
 *    - `app/src/main/java/com/doclayout/recognize/data/local/PdfPageSource.kt`，data 层，
 *      与 [OcrStore] 同级：只做「输入形态归一」（PDF → 一叠页 PNG），不做识别、不碰 Compose。
 * 2. 用了什么组件
 *    - `android.graphics.pdf.PdfRenderer`（API 19+，minSdk 26 直接可用）：系统自带 PDF 光栅化，
 *      不引 pdfbox/POI 这类几十 MB 的依赖——这也是移植时没把源项目 DocumentImporter（pdfbox）
 *      搬过来的原因，见 `cn/local/ocr/DocumentStore.java` 的「移植适配」注释；
 *    - `ParcelFileDescriptor`：PdfRenderer 只吃可 seek 的文件描述符，所以 `content://` / `file://`
 *      的 Uri 统一走 `ContentResolver.openFileDescriptor`，深链 `--es scan <pdf 路径>` 也走这一条路；
 *    - `Matrix`：渲染时按算好的 scale 把 pt（1/72 英寸）坐标放大到目标像素。
 * 3. 为什么栅格化尺寸要跟引擎对齐
 *    - 引擎拿到图后还会跑一次 `ImageInput.normalize`（balanced 档：≤8MP、最长边 4096，硬上限
 *      64MP，见 `cn/local/ocr/ImageResolution.java:17`）。这里直接按同一个预算栅格化：
 *      试卷 A4 页（595×842pt）会渲到约 287dpi（≈8MP），文字清晰又不会被引擎二次缩小；
 *      超大海报页自动缩小，永不越过 64MP 硬上限。
 */
object PdfPageSource {

    /** 单页像素预算：与引擎 ImageResolution.plan 的 balanced 档一致（8_000_000 像素）。 */
    private const val MAX_PIXELS = 8_000_000.0

    /** 单页最长边预算：同 balanced 档的 4096。 */
    private const val MAX_EDGE = 4096f

    /**
     * 把 [pdf] 逐页栅格化成 PNG，**页序 = 返回列表顺序**。
     *
     * @param outDir 页 PNG 的输出目录（调用方给临时目录，用完整目录删即可）
     * @return 每页一个文件（page-1.png 起）；PDF 打不开 / 加密 / 无页面时抛 [IOException]
     */
    suspend fun rasterize(context: Context, pdf: Uri, outDir: File): List<File> =
        withContext(Dispatchers.IO) {
            outDir.mkdirs()
            // 加密 PDF 在两处都会抛 SecurityException（打开 fd 阶段 / 构造 renderer 阶段），
            // 统一转成用户能看懂的 IOException，让 OcrApp 的失败弹窗直接显示。
            val pfd = try {
                context.contentResolver.openFileDescriptor(pdf, "r")
                    ?: throw IOException("打不开这个 PDF 文件")
            } catch (e: SecurityException) {
                throw IOException("这份 PDF 是加密的，暂时识别不了", e)
            }
            // PdfRenderer/Page 都是 AutoCloseable（平台类型），这里不用 .use{} 而手动
            // try/finally close：AutoCloseable.use 的泛型返回推断在 lambda 含 try 表达式时
            // 会退化成 Unit（编译报 Missing return statement），手动 close 零歧义
            try {
                val renderer = try {
                    PdfRenderer(pfd)
                } catch (e: SecurityException) {
                    throw IOException("这份 PDF 是加密的，暂时识别不了", e)
                }
                try {
                    if (renderer.pageCount <= 0) throw IOException("这份 PDF 没有可识别的页面")
                    val pages = mutableListOf<File>()
                    // 顺序渲染；PdfRenderer 同一时刻只允许一个打开的 Page，逐页 open/close
                    for (i in 0 until renderer.pageCount) pages += renderPage(renderer, i, outDir)
                    pages
                } finally {
                    renderer.close()
                }
            } finally {
                pfd.close()
            }
        }

    /** 渲染第 [index] 页（0 起）到 outDir/page-<n>.png。 */
    private fun renderPage(renderer: PdfRenderer, index: Int, outDir: File): File {
        val page = renderer.openPage(index)
        try {
            // page.width/height 的单位是 pt（1/72 英寸），A4 = 595×842pt
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            // scale = min(最长边上限, 像素预算)。超大页面会算出 <1 的 scale（缩小渲染），
            // 不能 coerceAtLeast(1f)，否则会重新越过像素预算
            val scale = min(MAX_EDGE / max(pw, ph), sqrt(MAX_PIXELS / (pw * ph)).toFloat())
            val w = max(1, (pw * scale).toInt())
            val h = max(1, (ph * scale).toInt())

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                // PDF 页面可能带透明底，先铺白，否则试卷在深色阅读器里渲染出黑底
                bitmap.eraseColor(Color.WHITE)
                // RENDER_MODE_FOR_PRINT：按打印质量光栅化（FOR_DISPLAY 会做屏幕降质优化）
                page.render(
                    bitmap,
                    null,
                    Matrix().apply { setScale(scale, scale) },
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                )
                val out = File(outDir, "page-${index + 1}.png")
                FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 0, it) }
                return out
            } catch (e: Exception) {
                throw IOException("第 ${index + 1} 页渲染失败：${e.message}", e)
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }
}
