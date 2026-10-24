package com.doclayout.recognize.data.local

import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.data.model.OcrScanPage
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

    /**
     * 端侧记录落盘（WP5）：在 [save] 的骨架上多搬两样东西——
     *   1. 引擎记录里的**区域裁剪图**（`filesDir/documents/<docId>/assets/`）整棵拷进
     *      `filesDir/ocr/<id>/assets/`，让记录目录自包含（引擎记录删除后产物还在）；
     *   2. document.md 里的相对引用（`assets/xxx.png`）改写成记录目录的 **file:// 绝对路径**：
     *      render.html 的 resolveSrc 对 `file:` 前缀直接放行（assets/render.html:231），
     *      WebView 开着 allowFileAccess（OcrMarkdownView），私有目录里的裁剪图能直接加载。
     *
     * markdown 用引擎 export 的 document.md 原文（[LocalOcrEngine.recognizeFile] 返回），
     * 不再像云端那样逐段重排——端侧流水线的 Markdown 本来就是 DocumentIR 转好的。
     */
    suspend fun saveOnDevice(
        title: String,
        engine: String,
        elapsedMs: Long,
        pages: List<OnDevicePage>, // 原图 + document.md 原文 + 引擎 assets 目录（可 null）
    ): OcrRecord = withContext(Dispatchers.IO) {
        val id = "r" + System.currentTimeMillis()
        val dir = File(root, id).apply { mkdirs() }
        val jsonPages = JSONArray()
        val scanPages = mutableListOf<OcrScanPage>()

        pages.forEachIndexed { i, page ->
            val n = i + 1
            val imageFile = File(dir, "p$n.jpg").also { page.source.copyTo(it, overwrite = true) }
            val md = if (page.assetsDir?.isDirectory == true) {
                val localAssets = File(dir, "assets")
                page.assetsDir.copyRecursively(localAssets, overwrite = true)
                rewriteAssetRefs(page.markdown, localAssets)
            } else {
                page.markdown
            }
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
            // 面板副标题随 engine 语义存盘：读回时直接用（readRecord 对老记录按 engine 兜底）
            .put("meta", META_ON_DEVICE)
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
            meta = META_ON_DEVICE,
            scan = scanPages,
            createdAt = createdAt,
        )
    }

    /**
     * document.md 的资源引用改写：`assets/…` → `file://<记录目录>/assets/…`。
     * 引擎 markdown 只产 `![…](assets/…)` 一种写法（native markdown.cpp 的 `![插图](resource)`），
     * `<img src="assets/…">` 形式一并兜住，防止导出策略将来变化时图全挂。
     */
    private fun rewriteAssetRefs(markdown: String, assetsDir: File): String {
        val base = Uri.fromFile(assetsDir).toString() // file:///data/user/0/<pkg>/files/ocr/<id>/assets
        return markdown
            .replace("](assets/", "]($base/")
            .replace("src=\"assets/", "src=\"$base/")
            .replace("src='assets/", "src='$base/")
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
        val engine = meta.optString("engine", "PaddleOCR-VL 1.6")
        OcrRecord(
            id = meta.optString("id", dir.name),
            title = meta.optString("title", "未命名试卷"),
            time = relativeTime(createdAt),
            pages = scan.size,
            engine = engine,
            // 两套引擎都不回传置信度：保持占位，不编数值；结果页只在 meta 为空时才展示它
            confidence = "—",
            duration = formatSeconds(meta.optLong("elapsedMs")),
            lines = emptyList(),
            // 面板副标题按 engine 语义取：WP5 起记录里带 meta 字段（saveOnDevice 写入）；
            // 老记录（WP5 前落盘）没有，按 engine 前缀兜底，避免端侧记录挂上"VLM 结构化输出"
            meta = meta.optString("meta").ifBlank {
                if (engine.startsWith("端侧")) META_ON_DEVICE else "VLM 结构化输出"
            },
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
        /** 端侧记录的 panel 副标题（结果页"识别结果"标题下那行）；与云端的"VLM 结构化输出"对应 */
        const val META_ON_DEVICE = "端侧流水线 · 区域结构化输出"

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

/**
 * 端侧落盘的一页（[OcrStore.saveOnDevice] 的输入）：
 * - [source]：该页原图（识别用的临时页文件，落盘时拷成记录目录的 p<n>.jpg）；
 * - [markdown]：引擎 document.md 原文（相对引用，落盘时改写 file://）；
 * - [assetsDir]：引擎记录的裁剪图目录（`documents/<docId>/assets/`），纯文字页为 null。
 */
data class OnDevicePage(
    val source: File,
    val markdown: String,
    val assetsDir: File?,
)
