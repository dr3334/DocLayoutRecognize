package com.doclayout.recognize.data.local

import com.doclayout.recognize.data.model.OcrStreamPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DocumentIR（引擎导出的 document.json）→ 界面模型的映射器（WP4 流水识别测试页配套）。
 *
 * 1. 属于什么文件
 *    - `app/src/main/java/com/doclayout/recognize/data/local/DocumentIrMapper.kt`，data 层。
 * 2. 用了什么组件
 *    - `org.json`：解析引擎导出的三份产物（document.json / document.md / run-manifest.json）；
 *    - [OcrStreamPage]（`data/model/OcrStreamModels.kt`）：区域框视图共用的纯 Kotlin 模型，
 *      「流式演示页」的样本数据也走它，所以两个页面拿到的数据形状完全一致；
 *    - 解析复用 [OcrStreamDemo.parse]：同一引擎导出 schema（pages[].raster_size / reading_order /
 *      blocks[].content），样本与真机产物没有差别，没必要抄第二份解析代码。
 * 3. 职责与边界
 *    - 只做「文件文本 → 界面模型」的纯映射，**不引任何 Compose 类型**（data 层硬规则）；
 *      画框、渲染都在 ui 层完成。产物文件的定位（目录布局）在 [LocalOcrEngine.readExport]。
 *    - 本页先按**单页**映射（pages[0]）：端侧流水线当前一次只吃一张图，产物是单页的；
 *      将来 PDF 多页导入（WP5）时再扩展成 List<OcrStreamPage>。
 */
object DocumentIrMapper {

    /**
     * document.json 文本 → pages[0] 的 [OcrStreamPage]。
     * JSON 损坏 / 无 pages 时返回 null，调用方按"没有区域框数据"处理（仍能展示 markdown）。
     */
    fun parsePage(documentJson: String): OcrStreamPage? = runCatching {
        OcrStreamDemo.parse(documentJson)
    }.getOrNull()

    /**
     * 组装流水识别页「完成后」要展示的全部产物数据。
     * 任何一份文件缺失都不算失败：markdown 缺失显示占位文案，manifest 缺失摘要为空列表。
     *
     * @param inputPath 记录目录里规范化输入图（state.json 的 "input"，如 source.png）的绝对路径，
     *                  区域框底图用；定位逻辑在 [LocalOcrEngine.readExport]，这里只透传。
     * @param docDir    记录目录：图片块的 resource 是它内部的相对路径（assets/p0001-b0004.png），
     *                  传入时会把 resource 改写成裁剪图绝对路径（见 [resolveResources]）。
     */
    fun build(
        documentJson: String,
        markdown: String?,
        manifestJson: String?,
        inputPath: String?,
        docDir: File? = null,
    ): LocalIrExport {
        val root = runCatching { JSONObject(documentJson) }.getOrNull()
        return LocalIrExport(
            markdown = markdown,
            page = parsePage(documentJson)?.let { resolveResources(it, docDir) },
            pageCount = root?.optJSONArray("pages")?.length() ?: 0,
            schemaVersion = root?.optString("schema_version").orEmpty(),
            inputPath = inputPath,
            manifestSummary = manifestSummary(manifestJson),
        )
    }

    /**
     * 图片块 resource 规范化：引擎写的是**记录目录内**的相对路径（assets/p0001-b0004.png），
     * 演示样本的相对路径却以 assets/stream/ 为根——形状相同、根不同。
     * 真实记录在这里把 resource 改写成裁剪图绝对路径，ui 层 toMarkdown 见到绝对路径就按
     * file:// 原样嵌入（render.html 的 resolveSrc 放行 file:），插图才能真正显示；
     * 不改写会被拼到 android_asset 下静默 404（演示页没暴露，是因为样本裁剪图恰好打包在 assets 里）。
     */
    private fun resolveResources(page: OcrStreamPage, docDir: File?): OcrStreamPage {
        if (docDir == null) return page
        return page.copy(
            blocks = page.blocks.map { b ->
                val r = b.resource ?: return@map b
                val f = File(docDir, r)
                if (r.startsWith("/") || !f.isFile) b else b.copy(resource = f.absolutePath)
            },
        )
    }

    /**
     * run-manifest.json 顶层字段的 (key, value) 列表，给「产物信息」视图铺键值行用。
     * 嵌套的 JSON 对象/数组只显示占位（<对象>/<数组 · n 项>），不在测试页里做递归树；
     * value 截到 120 字符，防止一整段耗时统计把列表撑爆。
     */
    fun manifestSummary(manifestJson: String?): List<Pair<String, String>> {
        val root = manifestJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        root.keys().forEach { key ->
            val value: Any = when {
                root.isNull(key) -> "null"
                else -> root.get(key)
            }
            out += key to when (value) {
                is JSONObject -> "<对象 · ${value.length()} 键>"
                is JSONArray -> "<数组 · ${value.length()} 项>"
                else -> value.toString().take(120)
            }
        }
        return out
    }
}
