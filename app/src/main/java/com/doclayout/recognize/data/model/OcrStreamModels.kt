package com.doclayout.recognize.data.model

/**
 * 「随手识别」导出的区域级模型（schema_version 1.10）。
 *
 * 和 [OcrRecord] 是两套东西，别混：
 *   OcrRecord       —— 整篇 Markdown，一次到齐，用于结果页
 *   OcrStreamPage   —— 拆到"区域"粒度（每个块带 bbox + 自己的裁剪图），
 *                      才能一个区域一个区域地流式渲染，并回到原图定位
 *
 * 数据来源：`files/documents/<uuid>/document.json` 的 `pages[0]`。
 */
data class OcrStreamBlock(
    /** b0001 这样的稳定 id，reading_order 里引用的就是它 */
    val id: String,
    /** 模型给的阅读顺序rank（可能为空，此时按数组下标排） */
    val order: Int,
    /** text / image / ...（模型输出的 block 类别） */
    val type: String,
    /** [x1, y1, x2, y2]，坐标系是原图栅格（见 [OcrStreamPage.width] / [OcrStreamPage.height]） */
    val bbox: List<Int>,
    /** markdown：正文含 $...$ 行内公式；resource：内容就是一张裁剪图 */
    val format: String,
    /** 正文。图片块为空，看图请用 [resource] */
    val text: String,
    /** 该区域的裁剪图，形如 `assets/p0001-b0001.png`（相对 assets/stream/） */
    val resource: String?,
) {
    val isImage: Boolean get() = format == "resource" || type == "image"
}

/** 一页的区域集合。[readingOrder] 是权威渲染顺序，[blocks] 是乱序的。 */
data class OcrStreamPage(
    val width: Int,
    val height: Int,
    val readingOrder: List<String>,
    val blocks: List<OcrStreamBlock>,
) {
    /** 按 reading_order 排好的块；缺 id 时退回按 order 排。 */
    val ordered: List<OcrStreamBlock>
        get() {
            val byId = blocks.associateBy { it.id }
            val fromOrder = readingOrder.mapNotNull { byId[it] }
            return if (fromOrder.size == blocks.size) fromOrder else blocks.sortedBy { it.order }
        }
}
