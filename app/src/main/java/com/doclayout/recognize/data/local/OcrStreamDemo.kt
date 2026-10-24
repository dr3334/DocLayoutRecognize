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

    /** JSON 路径。导出顺序修正时会写进 source 字段，指明改的是哪份数据 */
    const val DOCUMENT = "stream/document.json"

    /** 原图（给页面上半部分定位用）。jpg 是因为整页扫描图转 png 会到 1.2MB，jpg 只要 375KB */
    const val SOURCE_IMAGE = "stream/source.jpg"

    /** 区域裁剪图的前缀：JSON 里写的是 `assets/p0001-b0001.png`，WebView 侧要补到 assets 根 */
    private const val CROP_PREFIX = "stream/"

    fun load(context: Context): OcrStreamPage? = runCatching {
        val text = context.assets.open(DOCUMENT).bufferedReader(Charsets.UTF_8).use { it.readText() }
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
     * 图片块转成 `![](...)`——路径两种根：真实记录的 resource 已在 [DocumentIrMapper.resolveResources]
     * 改写成裁剪图绝对路径，按 `file://` 原样嵌入（render.html 的 resolveSrc 放行 file:）；
     * 演示样本仍是相对路径，补 `stream/` 前缀后由 resolveSrc 归到 android_asset 根。
     * 独立公式块：content.format = latex，正文是裸 LaTeX（没有 $ 包裹），直接扔进 Markdown
     * 只会显示成一段普通文字，必须自己补 $$。
     */
    fun toMarkdown(block: OcrStreamBlock): String {
        val res = block.resource
        return when {
            res != null && block.text.isBlank() ->
                if (res.startsWith("/")) "![](file://$res)" else "![]($CROP_PREFIX$res)"
            block.format == "latex" -> "$$${block.text.trim()}$$"
            else -> block.text.trim()
        }
    }
}
