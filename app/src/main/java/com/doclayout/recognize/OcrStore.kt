package com.doclayout.recognize

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别服务配置。放 SharedPreferences —— 只有两个短字符串，
 * 用 DataStore 会多引一个依赖、多一层 Flow，这个规模不值。
 *
 * Token 是明文存在应用私有目录里的（别的 App 读不到，但 root / 备份能拿到）。
 * 真上生产应该走 EncryptedSharedPreferences 或干脆放到自己的服务端换临时票据。
 */
/** 识别走哪儿：云端星河社区异步接口 / 本地自建 PaddleX 服务 */
enum class OcrMode(val label: String) {
    Cloud("云端 · 星河社区"),
    Local("本地 · PaddleX"),
}

class OcrSettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("ocr_vl", Context.MODE_PRIVATE)

    var mode: OcrMode
        get() = if (sp.getString("mode", "cloud") == "local") OcrMode.Local else OcrMode.Cloud
        set(v) = sp.edit().putString("mode", if (v == OcrMode.Local) "local" else "cloud").apply()

    /** 服务根地址。云端是固定域名；本地一般是 http://127.0.0.1:8080 或局域网 IP */
    var baseUrl: String
        get() = sp.getString("base_url", defaultBaseFor(mode)).orEmpty()
            .ifBlank { defaultBaseFor(mode) }
        set(v) = sp.edit().putString("base_url", v.trim()).apply()

    var model: String
        get() = sp.getString("model", OcrVlApi.MODEL).orEmpty()
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var credential: String
        get() = sp.getString("credential", "").orEmpty()
        set(v) = sp.edit().putString("credential", v.trim()).apply()

    /** 云端必须有 Token；本地服务通常不鉴权，地址填了就算配好 */
    val configured: Boolean
        get() = when (mode) {
            OcrMode.Cloud -> credential.isNotBlank()
            OcrMode.Local -> baseUrl.isNotBlank()
        }

    companion object {
        fun defaultBaseFor(mode: OcrMode) = when (mode) {
            OcrMode.Cloud -> OcrVlApi.DEFAULT_BASE
            OcrMode.Local -> OcrVlApi.DEFAULT_LOCAL_BASE
        }
    }
}

/**
 * 记录仓库：一条记录 = 一个目录。
 *
 *   filesDir/ocr/<id>/record.json   元信息
 *   filesDir/ocr/<id>/p1.jpg        原图（页序 = 文件名序）
 *   filesDir/ocr/<id>/p1.md         该页的 Markdown（含内联图片）
 *
 * 为什么一条一个目录，而不是一个大 JSON 全塞进去：
 * 大 JSON 每次改一条都要重写全部内容，而且图片 base64 混在同一个文件里会让它膨胀到几十 MB。
 * 目录 = 天然的分页边界，删记录就是删目录，也不会有"索引和实物不一致"的半坏状态。
 */
class OcrStore(context: Context) {

    private val root = File(context.filesDir, "ocr")

    /** 读全部记录，新的在前 */
    suspend fun list(): List<OcrRecord> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readRecord(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** 存一条记录；pages 里每个元素对应一张原图 + 一份 markdown */
    suspend fun save(
        title: String,
        engine: String,
        elapsedMs: Long,
        pages: List<Pair<File, String>>, // 原图临时文件 to markdown
    ): OcrRecord = withContext(Dispatchers.IO) {
        val id = "r" + System.currentTimeMillis()
        val dir = File(root, id).apply { mkdirs() }
        val jsonPages = JSONArray()
        val scanPages = mutableListOf<OcrScanPage>()

        pages.forEachIndexed { i, (src, md) ->
            val n = i + 1
            val imageFile = File(dir, "p$n.jpg").also { src.copyTo(it, overwrite = true) }
            val mdFile = File(dir, "p$n.md").also { it.writeText(md, Charsets.UTF_8) }
            jsonPages.put(
                JSONObject()
                    .put("image", imageFile.name)
                    .put("markdown", mdFile.name),
            )
            scanPages += OcrScanPage(imagePath = imageFile.absolutePath, markdown = md)
        }

        val createdAt = System.currentTimeMillis()
        val meta = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("engine", engine)
            .put("createdAt", createdAt)
            .put("elapsedMs", elapsedMs)
            .put("pages", jsonPages)
        File(dir, "record.json").writeText(meta.toString(), Charsets.UTF_8)

        OcrRecord(
            id = id,
            title = title,
            time = relativeTime(createdAt),
            pages = scanPages.size,
            engine = engine,
            confidence = "—",
            duration = formatSeconds(elapsedMs),
            lines = emptyList(),
            meta = "VLM 结构化输出",
            scan = scanPages,
            createdAt = createdAt,
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).takeIf { it.exists() }?.deleteRecursively()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.takeIf { it.exists() }?.deleteRecursively()
        Unit
    }

    private fun readRecord(dir: File): OcrRecord? = runCatching {
        val meta = JSONObject(File(dir, "record.json").readText(Charsets.UTF_8))
        val arr = meta.optJSONArray("pages") ?: JSONArray()
        val scan = (0 until arr.length()).mapNotNull { i ->
            val p = arr.getJSONObject(i)
            val img = File(dir, p.optString("image"))
            val md = File(dir, p.optString("markdown"))
            if (!img.exists()) return@mapNotNull null
            OcrScanPage(
                imagePath = img.absolutePath,
                markdown = if (md.exists()) md.readText(Charsets.UTF_8) else "",
            )
        }
        if (scan.isEmpty()) return@runCatching null

        val createdAt = meta.optLong("createdAt")
        OcrRecord(
            id = meta.optString("id", dir.name),
            title = meta.optString("title", "未命名试卷"),
            time = relativeTime(createdAt),
            pages = scan.size,
            engine = meta.optString("engine", "PaddleOCR-VL 1.6"),
            confidence = "—",
            duration = formatSeconds(meta.optLong("elapsedMs")),
            lines = emptyList(),
            meta = "VLM 结构化输出",
            scan = scan,
            createdAt = createdAt,
        )
    }.getOrNull()

    /** 把 Uri 落到缓存目录：HttpURLConnection 只要字节数组，先解码成文件更省内存 */
    suspend fun stageUri(context: Context, uri: Uri, index: Int): File = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "scan_${System.currentTimeMillis()}_$index.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("读不到这张图片：$uri")
        tmp
    }

    companion object {
        fun relativeTime(ms: Long): String {
            if (ms <= 0) return "刚刚"
            val diff = System.currentTimeMillis() - ms
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000} 分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
                else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            }
        }

        fun formatSeconds(ms: Long): String =
            if (ms <= 0) "—" else String.format(Locale.US, "%.1f s", ms / 1000f)

        fun titleFor(count: Int): String {
            val now = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            return if (count > 1) "试卷 · $count 页 · $now" else "试卷 · $now"
        }
    }
}
