package com.doclayout.recognize.data.local

import android.content.Context
import com.doclayout.recognize.data.model.OcrStreamBlock
import com.doclayout.recognize.data.model.OcrStreamPage
import org.json.JSONObject

/**
 * 读内置的「随手识别」导出样本：`assets/stream/document.json`，加上 stream/assets/ 下的区域裁剪图。
 *
 * 为什么放 data/local：它和 [OcrStore] 一样是"本地数据源"，只不过一个读文件系统、
 * 一个读 assets。UI 层不该知道 JSON 长什么样。
 *
 * 只取 `pages[0]`：样本是单页试卷。多页时这里要改成返回 List，其余逻辑不用动。
 */
object OcrStreamDemo {

    private const val DOC = "stream/document.json"

    /** 原图（给页面上半部分定位用） */
    const val SOURCE_IMAGE = "stream/source.png"

    /** 区域裁剪图的前缀：JSON 里写的是 `assets/p0001-b0001.png`，WebView 侧要补到 assets 根 */
    private const val CROP_PREFIX = "stream/"

    fun load(context: Context): OcrStreamPage? = runCatching {
        val text = context.assets.open(DOC).bufferedReader(Charsets.UTF_8).use { it.readText() }
        parse(text)
    }.getOrNull()

    /** 抽出来单独可测：不依赖 Context。 */
    fun parse(text: String): OcrStreamPage {
        val page = JSONObject(text).getJSONArray("pages").getJSONObject(0)

        val size = page.getJSONArray("raster_size")
        val orderArr = page.optJSONArray("reading_order")
        val readingOrder = if (orderArr == null) {
            emptyList()
        } else {
            (0 until orderArr.length()).map { orderArr.getString(it) }
        }

        val blockArr = page.getJSONArray("blocks")
        val blocks = (0 until blockArr.length()).map { i ->
            val b = blockArr.getJSONObject(i)
            val content = b.optJSONObject("content")
            val bboxArr = b.optJSONArray("bbox")
            OcrStreamBlock(
                id = b.optString("id"),
                order = b.optInt("block_order", i),
                type = b.optString("type", "text"),
                bbox = if (bboxArr == null) {
                    emptyList()
                } else {
                    (0 until bboxArr.length()).map { bboxArr.getInt(it) }
                },
                format = content?.optString("format").orEmpty(),
                text = content?.optString("text").orEmpty(),
                resource = content?.optString("resource")?.takeIf { it.isNotBlank() },
            )
        }

        return OcrStreamPage(
            width = size.getInt(0),
            height = size.getInt(1),
            readingOrder = readingOrder,
            blocks = blocks,
        )
    }

    /**
     * 把一个区域变成一段 Markdown。
     *
     * 文本块的 `content.text` 本来就是 Markdown（公式已经写成 `$...$`），原样用；
     * 图片块转成 `![](stream/assets/xxx.png)` —— render.html 的 resolveSrc() 会把相对路径
     * 补成 `file:///android_asset/...`，所以不用在这边拼协议头。
     */
    fun toMarkdown(block: OcrStreamBlock): String = when {
        block.resource != null && block.text.isBlank() -> "![]($CROP_PREFIX${block.resource})"
        else -> block.text.trim()
    }
}
