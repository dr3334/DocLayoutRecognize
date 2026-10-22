package com.doclayout.recognize.data.model

/**
 * 数据模型：渲染层和数据层之间唯一的契约，不含任何 Android / Compose 依赖。
 *
 * 两套结构故意不合并：
 *   [OcrLine] + [OcrLineKind]  —— 结构化文本块（内置样例、将来端侧 OCR 也能直接产出）
 *   [OcrScanPage] + [OcrRecord.scan] —— 一整页原图 + 该页 Markdown（云端 VLM 的产物）
 *
 * 用 [OcrRecord.isReal]（即 scan 是否为空）区分，渲染层据此换实现：
 * 前者走 OcrTex（RaTeX），后者走 OcrMarkdownView（WebView）。
 */

enum class OcrLineKind { Heading, Body, Formula, Answer }

/**
 * @param text 识别出来的纯文本（纸面上显示这个，因为扫描件上本来就是"印出来的样子"）
 * @param tex  对应的 LaTeX 源码（结果面板里用 RaTeX 排版，显示这个）
 *
 * 两个都留着，是因为"扫描件"和"结构化结果"本来就是两份东西：
 * 前者要还原纸，后者要能复制去 Markdown / Word。
 */
data class OcrLine(val kind: OcrLineKind, val text: String, val tex: String? = null)

/**
 * 真机上跑出来的一页：原图路径 + 该页的 Markdown。
 *
 * 和 [OcrLine] 是两套东西，故意不合并：
 *   OcrLine     —— 内置样例，是"已经拆好块"的假数据，用来演示版式
 *   OcrScanPage —— 云端 VLM 的真实产物，正文是一整段 Markdown（表格/公式都在里面）
 * 用 [OcrRecord.scan] 是否为空来区分，渲染层据此换实现。
 */
data class OcrScanPage(
    val imagePath: String,
    val markdown: String,
)

data class OcrRecord(
    val id: String,
    val title: String,
    val time: String,
    val pages: Int,
    val engine: String,
    val confidence: String,
    val duration: String,
    val lines: List<OcrLine>,
    /** 面板副标题；为空时用"置信度 X · 时长 · N 个文本块"那套样例文案 */
    val meta: String = "",
    /** 真实扫描页；为空 = 内置样例 */
    val scan: List<OcrScanPage> = emptyList(),
    /** 排序用；样例记录为 0 */
    val createdAt: Long = 0L,
) {
    val isReal: Boolean get() = scan.isNotEmpty()
}
