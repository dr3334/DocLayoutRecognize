package com.doclayout.recognize.ui.screen

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.doclayout.recognize.data.local.OcrStreamDemo
import com.doclayout.recognize.data.local.OcrStreamExport
import com.doclayout.recognize.data.model.OcrStreamPage
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.render.OcrMarkdownView
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.doclayout.recognize.data.model.OcrStreamBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * ─────────────────────────────────────────────────────────────
 * 学习导读
 *
 * 这个文件用不到 200 行讲清"流式渲染"的全部机制：
 *   1. 数据是区域级（OcrStreamPage），不是整篇 markdown
 *   2. 循环里只做一件事：让 typing 这个 String 变长
 *   3. 界面自己跟着变（声明式），全程没有一处"刷新"调用
 *
 * 两个刻意的取舍：
 *   - 文本每 3 个字符刷一次（cut += 3），不是每字符：每次变化都要跨进程
 *     evaluateJavascript 一次，逐字符是 80+ 次/秒，WebView 会掉帧
 *   - 图片块整块出现，不逐字：半截的 ![](stream/assets/p00 只会闪一下纯文本
 *
 * 公式能"边打边渲"靠的是 render.html 里的 balance()，不是这里的代码。
 * 配套阅读：LEARNING.md 第二节「公式怎么能边打边渲染」
 * ─────────────────────────────────────────────────────────────
 */

/**
 * 区域级流式渲染演示。
 *
 * 数据流和结果页完全不同：结果页拿到的是**一整篇 Markdown**（一次到齐），
 * 这里拿到的是**区域 JSON**，所以可以一块一块地吐出来，并且每块都能回到原图上定位：
 *
 *   document.json → pages[0].blocks[]（11 个区域，带 bbox + 自己的裁剪图）
 *                 → 按 reading_order 排序
 *                 → 逐区域转 Markdown → 逐字累积 → setMarkdown（render.html 做 diff，只重建最后一块）
 *
 * 公式能"边打边渲"靠的是 render.html 里现成的两条机制，不是这里做的：
 *   1. `parseBlocks` 把没闭合的 `$$...` 标成 closed=false
 *   2. `balance()` 把半截命令补齐（`\fra` 砍掉、`{` 补 `}`），于是 `$-\frac{y^{2}` 也能先渲一版
 */
@Composable
fun OcrStreamScreen(
    onBack: () -> Unit,
    /** 允许外部注入（测试/换数据源）；为 null 时读内置 assets 样本 */
    page: OcrStreamPage? = null,
    /** 点中某个区域时回调；默认空实现，所以现有调用点不用改 */
    onBlockClick: (OcrStreamBlock) -> Unit = {},
) {
    val c = Ocr.colors
    val context = LocalContext.current
    val data = remember(page) { page ?: OcrStreamDemo.load(context) }
    // ordered 每次访问都是新 List（getter 里 mapNotNull），必须 remember：
    // 否则 pointerInput 的 key 每帧都变，手势会被不停重启（和 sheetDrag 那个坑同源），
    // colorOf 的 remember(ordered) 也会失效、每次重组重算一遍。
    val ordered = remember(data) { data?.ordered.orEmpty() }

    // ── 顺序修正：模型最容易出错的地方（reading_order 会把某一段排到别处）
    // customOrder = null 表示没动过，用模型给的顺序；否则是调整后的 id 序列。
    //
    // 注意：**只改顺序，不碰框**。框的位置、大小、数量全部保持模型原样 ——
    // 框偏了是模型自身的问题，属于事实，界面不该悄悄"修好"它，只把它当定位提示用。
    var customOrder by remember { mutableStateOf<List<String>?>(null) }
    // 撤销栈：每次调序前压一份快照（上限 30）。撤销 = 弹回上一张，不用写反向操作。
    var history by remember { mutableStateOf<List<List<String>?>>(emptyList()) }
    // 按住连发期间为 true：整串只压一个撤销点（否则按住 3 秒要按 30 次撤销）
    var holding by remember { mutableStateOf(false) }

    // 当前生效的序列。所有下游统一从这里取：播放、正文、复制、合并高亮。
    // 只有这一处排序逻辑，不会出现"正文是新序、复制还是旧序"。
    val blocksNow = remember(ordered, customOrder) {
        val ids = customOrder
        if (ids == null) {
            ordered
        } else {
            val byId = ordered.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }

    var playKey by remember { mutableIntStateOf(0) }
    var doneText by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf("") }
    var activeIndex by remember { mutableIntStateOf(-1) }

    // 上下联动 + 区域合并：选中的是一组 id。连续点几个块 = 多选，原图按并集画一个大框
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 长按拖动时的临时范围（空 = 没在拖）。拖动中用它做预览，松手才提交
    var dragAnchor by remember { mutableStateOf<Int?>(null) }
    var dragRange by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(playKey, data) {
        doneText = ""
        typing = ""
        activeIndex = -1
        selectedIds = emptySet()
        // 重播 = 用当前顺序重放一遍。**不清空 customOrder**：那是刚调好的东西，
        // 点个重播就丢了会挨骂。要回模型原序，按工具条上的「还原」。
        if (data == null) return@LaunchedEffect

        var acc = ""
        blocksNow.forEachIndexed { idx, block ->
            activeIndex = idx
            val md = OcrStreamDemo.toMarkdown(block)
            if (block.isImage) {
                // 图片块不逐字：`![](stream/assets/p00` 这类半截语法只会闪一下纯文本
                typing = md
                delay(320)
            } else {
                var cut = 1
                // 每 3 个字符刷一次：逐字符刷是 80+ 次/秒的 evaluateJavascript，WebView 会掉帧
                while (cut <= md.length) {
                    typing = md.substring(0, cut)
                    cut += 3
                    delay(26)
                }
                typing = md
                delay(140)
            }
            // 已完成区域带锚点；正在打字的那个不带，否则标记会被逐字打出来
            val tagged = "@@blk:${block.id}@@\n$md"
            acc = if (acc.isEmpty()) tagged else "$acc\n\n$tagged"
            doneText = acc
            typing = ""
        }
        activeIndex = -1
    }

    // 流式态的正文：轻量，每次重组算一遍即可
    val streamed = when {
        typing.isEmpty() -> doneText
        doneText.isEmpty() -> typing
        // 空锚点把"正在打字"这段从上一个区域里隔离出来，点它不会命中错区域
        else -> "$doneText\n\n@@blk:@@\n$typing"
    }

    // 调过顺序后正文必须重建：逐字累积的 doneText 是按旧顺序拼的，改不了。
    // null 表示没调过，shown 回落到流式态 —— 没动过顺序的人行为完全不变。
    val reorderedMarkdown = remember(blocksNow, customOrder) {
        if (customOrder == null) {
            null
        } else {
            blocksNow.joinToString("\n\n") { "@@blk:${it.id}@@\n${OcrStreamDemo.toMarkdown(it)}" }
        }
    }
    val shown = reorderedMarkdown ?: streamed
    val activeBlock = blocksNow.getOrNull(activeIndex)

    // activeIndex 就是进度：它之前的块都已经"识别过"，已识别集合不用另存状态
    // （加一个 mutableList 记录"画过的框"等于两份数据要同步，迟早不一致）。
    val revealed = remember(blocksNow, activeIndex) {
        if (activeIndex >= 0) blocksNow.take(activeIndex + 1) else blocksNow
    }

    // 选中区域 = 复制它的内容并给一条轻提示；再点同一块取消选中（不复制）
    val clipboard = LocalClipboardManager.current
    var hintText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hintText) {
        if (hintText != null) {
            delay(1600)
            hintText = null
        }
    }

    fun copyBlocks(picked: List<OcrStreamBlock>) {
        val text = picked.filterNot { it.isImage }
            .joinToString("\n\n") { OcrStreamDemo.toMarkdown(it).trim() }
            .trim()
        if (text.isEmpty()) {
            hintText = "选中的区域都是图片，没有可复制的文本"
            return
        }
        clipboard.setText(AnnotatedString(text))
        hintText = if (picked.size == 1) {
            "已复制 ${picked[0].id} · ${text.length} 字"
        } else {
            "已合并 ${picked.size} 个区域 · ${text.length} 字"
        }
    }

    // 单击：只选这一个，再点同一个则取消。合并走长按拖动，两者不重合。
    fun applySingle(block: OcrStreamBlock) {
        if (selectedIds == setOf(block.id)) {
            selectedIds = emptySet()
            return
        }
        selectedIds = setOf(block.id)
        copyBlocks(listOf(block))
    }

    // 长按拖动松手：把起点到终点之间的所有块合成一个区域（按当前生效的顺序取区间）
    fun commitDrag(ids: Set<String>) {
        val picked = blocksNow.filter { it.id in ids }
        if (picked.isEmpty()) return
        selectedIds = picked.map { it.id }.toSet()
        copyBlocks(picked)
    }

    // ── 调序操作。统一"先压快照再改"，撤销就是弹回，不用为每个操作写反向逻辑
    fun pushOrder(next: List<String>) {
        // holding 期间（按住连发）不重复压栈：整串连续移动只留一个撤销点
        if (!holding) history = (history + customOrder).takeLast(30)
        customOrder = next
    }

    fun moveBlock(block: OcrStreamBlock, delta: Int) {
        val ids = blocksNow.map { it.id }.toMutableList()
        val from = ids.indexOf(block.id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.size - 1)
        if (to == from) {
            hintText = if (delta < 0) "${block.id} 已经在最前面" else "${block.id} 已经在最后面"
            return
        }
        ids.removeAt(from)
        ids.add(to, block.id)
        pushOrder(ids)
        hintText = "${block.id} 移到第 ${to + 1}/${ids.size} 位"
    }

    fun undoOrder() {
        if (history.isEmpty()) return
        customOrder = history.last()
        history = history.dropLast(1)
        hintText = "已撤销调序"
    }

    fun resetOrder() {
        if (customOrder == null) return
        history = (history + customOrder).takeLast(30)
        customOrder = null
        hintText = "已还原为模型原始顺序"
    }

    /**
     * 导出修正后的顺序：json（原序 + 新序对比）+ md（按新序拼好的全文），
     * 落到应用私有目录，同时把全文复制到剪贴板。
     * 写文件的具体逻辑在 data 层（OcrStreamExport），这里只负责提示。
     */
    fun exportOrder() {
        val page = data ?: return
        if (customOrder == null) {
            hintText = "顺序没改过，不用导出"
            return
        }
        runCatching { OcrStreamExport.saveOrder(context, page, ordered, blocksNow) }
            .onSuccess { r ->
                clipboard.setText(AnnotatedString(r.markdown))
                hintText = "已导出 ${r.jsonName} · 复制 ${r.markdown.length} 字"
            }
            .onFailure { hintText = "导出失败：${it.message}" }
    }

    // 四色分配：纯计算，一次算好；不要写成在 DrawScope 里现算
    val colorOf = remember(ordered) { assignBlockColors(ordered) }

    // 拖动中看 dragRange（预览），松手后看 selectedIds（已提交）
    val effectiveIds = if (dragRange.isNotEmpty()) dragRange else selectedIds

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        OcrTopBar(
            title = "流式渲染",
            subtitle = "区域 JSON → 逐字出公式与样图",
            onBack = onBack,
        )

        // ── 上半：原图 + 当前区域高亮
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Space.md, vertical = Space.xs)
                .background(c.paper, RoundedCornerShape(Radii.md)),
        ) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val bitmap = remember {
                runCatching {
                    context.assets.open(OcrStreamDemo.SOURCE_IMAGE).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            // 序号测量器：只在 SHOW_BLOCK_INDEX 打开时才会被调用，默认不影响性能
            val indexMeasurer = rememberTextMeasurer()

            if (bitmap != null && data != null) {
                // ContentScale.Fit 的等效换算：取小的那个比例，再居中留边
                val scale = min(boxW / data.width, boxH / data.height)
                val drawW = data.width * scale
                val drawH = data.height * scale
                val dx = (boxW - drawW) / 2f
                val dy = (boxH - drawH) / 2f

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "原图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        // 命中判断：屏幕坐标反算回原图坐标。Compose 不需要叠一层透明矩形
                        // （那是 View 的 hit-test 思路），直接算落在哪个 bbox 里就行。
                        .pointerInput(scale, dx, dy, blocksNow) {
                            // 单击 = 单选（再点同一块取消）；点空白也取消
                            detectTapGestures { pos ->
                                val hit = hitAt(blocksNow, pos, dx, dy, scale)
                                if (hit == null) {
                                    selectedIds = emptySet()
                                } else {
                                    Log.d("LinkDebug", "image tap -> ${hit.id}")
                                    applySingle(hit)
                                    onBlockClick(hit)
                                }
                            }
                        }
                        // 长按 + 拖动 = 范围合并，松手才生效。
                        // 必须是独立的 pointerInput：一个 pointerInput 里只能 await 一种手势，
                        // 两个探测器塞一起会互相抢事件（表现为长按后单击也触发）。
                        .pointerInput(scale, dx, dy, blocksNow) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val start = hitAt(blocksNow, pos, dx, dy, scale)
                                    dragAnchor = start?.let { blocksNow.indexOf(it) }
                                    dragRange = start?.let { setOf(it.id) } ?: emptySet()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val anchor = dragAnchor
                                    val cur = hitAt(blocksNow, change.position, dx, dy, scale)
                                        ?.let { blocksNow.indexOf(it) }
                                    if (anchor != null && cur != null) {
                                        val a = minOf(anchor, cur)
                                        val b = maxOf(anchor, cur)
                                        dragRange = blocksNow.subList(a, b + 1).map { it.id }.toSet()
                                    }
                                },
                                onDragEnd = {
                                    val ids = dragRange
                                    dragAnchor = null
                                    dragRange = emptySet()
                                    commitDrag(ids)
                                },
                                onDragCancel = {
                                    dragAnchor = null
                                    dragRange = emptySet()
                                },
                            )
                        },
                ) {
                    // 合并框：选中 >= 2 个时取并集（左上取最小、右下取最大），画一个虚线大框
                    if (effectiveIds.size >= 2) {
                        val picked = blocksNow.filter { it.id in effectiveIds && it.bbox.size == 4 }
                        if (picked.isNotEmpty()) {
                            val x1 = picked.minOf { it.bbox[0] }
                            val y1 = picked.minOf { it.bbox[1] }
                            val x2 = picked.maxOf { it.bbox[2] }
                            val y2 = picked.maxOf { it.bbox[3] }
                            val tl = Offset(dx + x1 * scale, dy + y1 * scale)
                            val sz = Size((x2 - x1) * scale, (y2 - y1) * scale)
                            drawRect(color = c.primary.copy(alpha = 0.10f), topLeft = tl, size = sz, style = Fill)
                            drawRect(
                                color = c.primary,
                                topLeft = tl,
                                size = sz,
                                style = Stroke(
                                    width = 2f.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                                ),
                            )
                        }
                    }

                    revealed.forEach { block ->
                        val b = block.bbox
                        if (b.size != 4) return@forEach
                        val isCurrent = block.id == activeBlock?.id
                        val isSelected = block.id in effectiveIds
                        drawRect(
                            // 已识别的用低透明度 + 细线：29 个框全用不透明 2dp 会把原图糊满
                            color = (colorOf[block.id] ?: c.primary)
                                .copy(alpha = if (isCurrent || isSelected) 1f else 0.42f),
                            topLeft = Offset(dx + b[0] * scale, dy + b[1] * scale),
                            size = Size(
                                ((b[2] - b[0]) * scale).coerceAtLeast(1f),
                                ((b[3] - b[1]) * scale).coerceAtLeast(1f),
                            ),
                            style = Stroke(width = when {
                                isSelected -> 3f
                                isCurrent -> 2f
                                else -> 1f
                            }.dp.toPx()),
                        )

                        // 序号：默认不画（SHOW_BLOCK_INDEX = false）。
                        // 只有需要"正文第几段对应原图哪个框"时才打开，避免小框被数字糊满。
                        if (SHOW_BLOCK_INDEX) {
                            val order = blocksNow.indexOfFirst { it.id == block.id } + 1
                            if (order > 0) {
                                val layout = indexMeasurer.measure(
                                    text = order.toString(),
                                    style = TextStyle(
                                        color = colorOf[block.id] ?: c.primary,
                                        fontSize = 9.sp,
                                    ),
                                )
                                drawText(
                                    textLayoutResult = layout,
                                    topLeft = Offset(
                                        dx + b[0] * scale + 3f,
                                        (dy + b[1] * scale - layout.size.height).coerceAtLeast(0f),
                                    ),
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "样本图加载失败",
                    style = OcrType.Caption,
                    color = c.muted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // ── 进度 / 调序工具条 + 重播
        // 三态：选中一段 -> 调序工具条；没选中但改过顺序 -> 撤销/还原/导出；都没有 -> 原进度行。
        // 改过顺序就必须常驻撤销入口，否则点一下空白工具条消失，再也回不到原顺序。
        val editing = blocksNow.firstOrNull { it.id in selectedIds }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            when {
                editing != null -> {
                    // 这里只改"这段排第几"，不动它的框。
                    // 用按钮而不是拖拽：下方正文在 WebView 里，拖动排序要穿透 JS 层；
                    // 而且上移/下移是离散操作，按钮不会误触，按住还能连发。
                    Text(
                        text = "${editing.id} ${blocksNow.indexOfFirst { it.id == editing.id } + 1}/${blocksNow.size}",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                    HoldKey(
                        label = "↑",
                        fg = c.ink,
                        bg = c.surfaceCard,
                        onStep = { moveBlock(editing, -1) },
                        onPressChange = { holding = it },
                    )
                    HoldKey(
                        label = "↓",
                        fg = c.ink,
                        bg = c.surfaceCard,
                        onStep = { moveBlock(editing, 1) },
                        onPressChange = { holding = it },
                    )
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoOrder() }
                    ActionKey(
                        "导出",
                        c.primary,
                        c.primary.copy(alpha = 0.12f),
                        enabled = customOrder != null,
                    ) { exportOrder() }
                }

                customOrder != null -> {
                    Text(
                        text = "顺序已调整",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoOrder() }
                    ActionKey("还原", c.danger, c.surfaceCard) { resetOrder() }
                    ActionKey("导出", c.primary, c.primary.copy(alpha = 0.12f)) { exportOrder() }
                }

                else -> {
                    val progress = if (activeIndex >= 0) "${activeIndex + 1}/${blocksNow.size}" else "完成"
                    Pill(text = progress, background = c.surfaceCard, color = c.muted)
                    if (activeBlock != null) {
                        Text(
                            text = "${activeBlock.type} · ${activeBlock.id}",
                            style = OcrType.Caption.copy(fontSize = 12.sp),
                            color = c.mutedSoft,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // 胶囊自绘：公共的 Pill 是只读展示组件，加 onClick 会牵动其它调用点
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.primary.copy(alpha = 0.12f))
                    .clickable { playKey++ }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(text = "重播", style = OcrType.Caption, color = c.primary)
            }
        }

        // ── 下半：流式 Markdown（复用结果页同一套 WebView + KaTeX）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.15f)
                .padding(horizontal = Space.md, vertical = Space.xs)
                .background(c.canvas, RoundedCornerShape(Radii.md)),
        ) {
            OcrMarkdownView(
                markdown = shown,
                // 选中某块时关掉自动滚底，否则每帧 setMarkdown 都把视线拽回底部
                autoScrollBottom = effectiveIds.isEmpty(),
                highlightBlockIds = blocksNow.filter { it.id in effectiveIds }.map { it.id },
                onBlockTap = { id ->
                    Log.d("LinkDebug", "text tap -> $id")
                    blocksNow.firstOrNull { it.id == id }?.let { applySingle(it) }
                },
                modifier = Modifier.fillMaxSize().padding(Space.sm),
            )
            if (shown.isEmpty()) {
                Text(
                    text = "正在准备区域数据…",
                    style = OcrType.Caption,
                    color = c.mutedSoft,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Spacer(Modifier.height(Space.sm))
    }

        // 复制提示：轻量浮条，1.6s 自动消失。用自绘而不用系统 Toast：
        // Toast 在部分 ROM 上会被限流，而且样式脱离这套设计语言。
        AnimatedVisibility(
            visible = hintText != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.xl),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Text(
                text = hintText.orEmpty(),
                style = OcrType.Caption,
                color = c.canvas,
                modifier = Modifier
                    .background(c.ink.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                    .padding(horizontal = Space.md, vertical = Space.xs),
            )
        }
    }
}

/* ---------------------- 调序工具条的小按键 ---------------------- */

/**
 * 是否在区域框左上角画序号（1、2、3…）。
 *
 * **默认关闭**。这一页要看的是"文字顺序对不对"，序号会把本来就小的框糊满；
 * 只有在需要确认"正文第 3 段对应原图哪个框"、或者调顺序调花眼时，改成 true 重新编译即可。
 */
private const val SHOW_BLOCK_INDEX = false

/**
 * 按住可以连发的按键：点一下走一步，按住 400ms 后每 90ms 自动走一步，松手即停。
 *
 * 用 pointerInput + detectTapGestures(onPress) 而不是 combinedClickable：
 * onPress 是 suspend 块，能精确拿到"按下 / 松开"两端，长按阈值也能自己定。
 *
 * 两个必须注意的点：
 *   1. 回调要用 rememberUpdatedState 兜住 —— pointerInput(Unit) 不会因重组重启，
 *      直接捕获 onStep 会永远执行第一次组合时的旧闭包（里面全是旧状态）
 *   2. onPressChange(true) 必须排在第一次 onStep **之后**，否则第一步就不压撤销快照，
 *      按住连发一百步之后一步都撤不回来
 */
@Composable
private fun HoldKey(
    label: String,
    fg: Color,
    bg: Color,
    enabled: Boolean = true,
    onStep: () -> Unit,
    onPressChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val canRun by rememberUpdatedState(enabled)
    val pressChange by rememberUpdatedState(onPressChange)

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (canRun) {
                            step()
                            pressChange(true)
                            val repeat = scope.launch {
                                delay(400)          // 先留出"只想点一下"的余量
                                while (isActive) {
                                    step()
                                    delay(90)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                            pressChange(false)
                        } else {
                            tryAwaitRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 15.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.30f),
        )
    }
}

/** 文字动作键：宽度跟着文字走，比方向键宽一点，避免中文被挤成两行 */
@Composable
private fun ActionKey(
    label: String,
    fg: Color,
    bg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 13.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.35f),
        )
    }
}

/* ---------------------- 区域框的四色着色 ---------------------- */

/** 四色：主色打头，其余取和纸张背景、正文墨色都不撞的深色 */
private val BLOCK_COLORS = listOf(
    Color(0xFFCC785C), // 珊瑚橘（primary）
    Color(0xFF1D9E75), // teal
    Color(0xFFBA7517), // amber
    Color(0xFF534AB7), // purple
)

/**
 * 贪心四色着色。
 *
 * 邻接判据用「间距 < gap」而不是「bbox 相交」：
 * 试卷上的文本块上下紧挨、边界几乎重合（实测 b0003 底边 457、b0004 顶边 461，只差 4px），
 * 用相交判断会得到一张空邻接表 → 每块都拿到第一个颜色 → 四色等于没做。
 *
 * 四色定理保证平面图 4 色足够；贪心在这里够用，因为它只需要"相邻不同色"，不追求最优。
 */
private fun assignBlockColors(
    blocks: List<OcrStreamBlock>,
    gap: Float = 12f,
): Map<String, Color> {
    val adj = buildAdjacency(blocks, gap)
    val result = HashMap<String, Color>(blocks.size)
    blocks.forEach { block ->
        val used = adj[block.id].orEmpty().mapNotNull { result[it] }.toSet()
        result[block.id] = BLOCK_COLORS.firstOrNull { it !in used } ?: BLOCK_COLORS[0]
    }
    return result
}

private fun buildAdjacency(
    blocks: List<OcrStreamBlock>,
    gap: Float,
): Map<String, List<String>> {
    val adj = blocks.associate { it.id to mutableListOf<String>() }
    for (i in blocks.indices) {
        for (j in i + 1 until blocks.size) {
            if (touching(blocks[i].bbox, blocks[j].bbox, gap)) {
                adj[blocks[i].id]?.add(blocks[j].id)
                adj[blocks[j].id]?.add(blocks[i].id)
            }
        }
    }
    return adj
}

/** 两个矩形"外扩 gap 后是否相交"。gap 用原图坐标，1400×1812 的页面取 12 左右分组刚好 */
private fun touching(a: List<Int>, b: List<Int>, gap: Float): Boolean {
    if (a.size != 4 || b.size != 4) return false
    return !(a[2] + gap < b[0] || b[2] + gap < a[0] ||
        a[3] + gap < b[1] || b[3] + gap < a[1])
}

/** 屏幕坐标反算回原图坐标，返回命中的区域。Compose 不需要叠透明矩形做 hit-test。 */
private fun hitAt(
    list: List<OcrStreamBlock>,
    pos: Offset,
    dx: Float,
    dy: Float,
    scale: Float,
): OcrStreamBlock? {
    if (scale <= 0f) return null
    val ix = (pos.x - dx) / scale
    val iy = (pos.y - dy) / scale
    return list.firstOrNull { b ->
        b.bbox.size == 4 &&
            ix >= b.bbox[0] && ix <= b.bbox[2] &&
            iy >= b.bbox[1] && iy <= b.bbox[3]
    }
}