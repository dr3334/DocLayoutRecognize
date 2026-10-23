package com.doclayout.recognize.data.local

import android.content.Context
import com.doclayout.recognize.data.model.OcrStreamBlock
import com.doclayout.recognize.data.model.OcrStreamPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把「人工修正过的区域顺序」落盘。
 *
 * 为什么放在 data 层，而不是直接在 Screen 里 `File(...).writeText(...)`：
 *   1. 项目约定 ui/ 不碰 org.json / 文件 IO，Screen 只该拿到一个结果对象和一句话提示
 *   2. 「原序 + 新序」的对比格式只有一份定义，以后做批量修正或换存储位置时不用改 UI
 *
 * 产物都写在应用私有目录 `files/stream-order/`，不需要任何存储权限：
 *   order-<时间>.json   原始顺序 + 修正后顺序，用来回答"到底把哪几块挪了"
 *   order-<时间>.md     按修正后顺序拼好的全文，可以直接拿去用
 *
 * 注意这里**不修改** assets 里的 document.json：那是模型的原始输出，
 * 修正结果作为一份独立产物存放，两者可以随时对比。
 */
object OcrStreamExport {

    /** 导出结果。UI 只需要 jsonName 用于提示、markdown 用于复制 */
    data class Result(
        val jsonName: String,
        val mdName: String,
        val dirPath: String,
        val markdown: String,
    )

    fun saveOrder(
        context: Context,
        page: OcrStreamPage,
        original: List<OcrStreamBlock>,
        corrected: List<OcrStreamBlock>,
    ): Result {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.filesDir, "stream-order").apply { mkdirs() }

        val json = JSONObject().apply {
            put("source", "assets/${OcrStreamDemo.DOCUMENT}")
            put("page", JSONArray(listOf(page.width, page.height)))
            put("original_order", JSONArray(original.map { it.id }))
            put("corrected_order", JSONArray(corrected.map { it.id }))
            put("exported_at", stamp)
        }
        val jsonFile = File(dir, "order-$stamp.json")
        jsonFile.writeText(json.toString(2), Charsets.UTF_8)

        // 图片块不写进正文：`![](assets/p0001-b0005.png)` 离开这个 App 就是坏链，
        // 而且用户导出的目的是"拿到可用的文字与公式"。
        val markdown = corrected.filterNot { it.isImage }
            .joinToString("\n\n") { OcrStreamDemo.toMarkdown(it).trim() }
        val mdFile = File(dir, "order-$stamp.md")
        mdFile.writeText(markdown, Charsets.UTF_8)

        return Result(
            jsonName = jsonFile.name,
            mdName = mdFile.name,
            dirPath = dir.absolutePath,
            markdown = markdown,
        )
    }
}
