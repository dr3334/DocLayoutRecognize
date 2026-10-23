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
import com.doclayout.recognize.data.local.OcrStreamDemo
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
import kotlin.math.abs
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

    var playKey by remember { mutableIntStateOf(0) }
    var doneText by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf("") }
    var activeIndex by remember { mutableIntStateOf(-1) }

    // 上下联动 + 区域合并：选中的是一组 id。连续点几个块 = 多选，原图按并集画一个大框
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ── 框编辑（方案 B：全部按钮操作，不新增任何手势）
    // 编辑是"叠加层"，原数据一个字都不改：assets 里的 document.json 是模型的原始输出，
    // 框偏移本身是模型的错误，属于"事实"。这里的移动/删除只用于你自己对齐查看，
    // 不回写文件、不传回识别流程，重播即还原。所以撤销 = 弹 history 一格，重置 = edits 清空。
    var edits by remember { mutableStateOf<Map<String, BlockEdit>>(emptyMap()) }
    var history by remember { mutableStateOf<List<Map<String, BlockEdit>>>(emptyList()) }
    // 长按拖动时的临时范围（空 = 没在拖）。拖动中用它做预览，松手才提交
    var dragAnchor by remember { mutableStateOf<Int?>(null) }
    var dragRange by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(playKey, data) {
        doneText = ""
        typing = ""
        activeIndex = -1
        selectedIds = emptySet()
        // 重播 = 回到模型原始输出的样子，编辑一并丢弃
        edits = emptyMap()
        history = emptyList()
        if (data == null) return@LaunchedEffect

        var acc = ""
        ordered.forEachIndexed { idx, block ->
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

    // 编辑态的正文：只在 edits 或原始数据变化时重建一次（29 块 toMarkdown 不便宜）。
    // 用 null 表示"没编辑过"，让 shown 回落到流式态 —— 这样没用编辑的人行为完全不变。
    val editedMarkdown = remember(ordered, edits) {
        if (edits.isEmpty()) {
            null
        } else {
            // 删掉的块必须从正文里消失，而逐字累积的 doneText 改不了，只能重建带锚点的全文
            ordered.filterNot { edits[it.id]?.hidden == true }
                .joinToString("\n\n") { "@@blk:${it.id}@@\n${OcrStreamDemo.toMarkdown(it)}" }
        }
    }
    val shown = editedMarkdown ?: streamed
    val activeBlock = ordered.getOrNull(activeIndex)

    // 一个块在"当前视图"里长什么样：隐藏的返回 null，移动过的返回换了 bbox 的副本。
    // 只影响绘制与命中，四色仍按原始 ordered 算 —— 框挪一下不应该让整页重新配色。
    fun viewOf(b: OcrStreamBlock): OcrStreamBlock? {
        val e = edits[b.id] ?: return b
        if (e.hidden) return null
        return e.bbox?.let { b.copy(bbox = it) } ?: b
    }

    // 命中判断要用"当前视图"的 bbox：框挪过之后，点在旧位置不应该还命中它。
    // 必须 remember —— 每次重组新建 List 会让 pointerInput 的 key 每帧变化、手势被反复重启。
    val blocksNow = remember(ordered, edits) { ordered.mapNotNull { viewOf(it) } }

    // activeIndex 就是进度：它之前的块都已经"识别过"，已识别集合不用另存状态
    // （加一个 mutableList 记录"画过的框"等于两份数据要同步，迟早不一致）。
    val revealed = remember(ordered, activeIndex, edits) {
        (if (activeIndex >= 0) ordered.take(activeIndex + 1) else ordered)
            .mapNotNull { viewOf(it) }
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

    // 长按拖动松手：把起点到终点之间的所有块（按 readingOrder）合成一个区域
    fun commitDrag(ids: Set<String>) {
        val picked = ordered.filter { it.id in ids && edits[it.id]?.hidden != true }
        if (picked.isEmpty()) return
        selectedIds = picked.map { it.id }.toSet()
        copyBlocks(picked)
    }

    // ── 编辑操作：全部走 pushEdit，先把当前快照压进 history，再改。
    // 撤销就是"弹回上一张快照"，不需要为每个操作写一对反向逻辑（那样很容易漏）。
    fun pushEdit(next: Map<String, BlockEdit>) {
        history = (history + edits).takeLast(30)   // 只留 30 步，防止无限增长
        edits = next
    }

    fun nudge(block: OcrStreamBlock, dx: Int, dy: Int) {
        val b = block.bbox
        val page = data ?: return
        if (b.size != 4) return
        // 先算"这一步实际能走多远"，避免把框推出页面（推到边界后按同方向不再生效）
        val sx = dx.coerceIn(-b[0], page.width - b[2])
        val sy = dy.coerceIn(-b[1], page.height - b[3])
        if (sx == 0 && sy == 0) {
            hintText = "${block.id} 已经贴到页面边缘"
            return
        }
        val moved = listOf(b[0] + sx, b[1] + sy, b[2] + sx, b[3] + sy)
        val cur = edits[block.id] ?: BlockEdit()
        pushEdit(edits + (block.id to cur.copy(bbox = moved)))
        val dir = when {
            sx > 0 -> "右移"
            sx < 0 -> "左移"
            sy > 0 -> "下移"
            else -> "上移"
        }
        // 带上"相对模型原始框的累计位移"：数字每次都变，提示才会重新计时（同值不会刷新），
        // 顺带让你知道累加多了需要往回收。
        val totalX = moved[0] - b[0]
        val totalY = moved[1] - b[1]
        val acc = when {
            totalY != 0 -> " 累计 ${totalX},${totalY}"
            else -> " 累计 ${if (totalX > 0) "+" else ""}${totalX}px"
        }
        hintText = "${block.id} ${dir}${maxOf(abs(sx), abs(sy))}px$acc"
    }

    fun hideBlock(block: OcrStreamBlock) {
        val cur = edits[block.id] ?: BlockEdit()
        pushEdit(edits + (block.id to cur.copy(hidden = true)))
        // 刻意不清 selectedIds：删完工具条要能就地显示"已删除 b0001"并给出撤销。
        // 一删就跳回进度行的话，撤销按钮跑到别处，用户会以为删没了没法回头。
        hintText = "已删除 ${block.id}，可撤销"
    }

    fun undoEdit() {
        if (history.isEmpty()) return
        edits = history.last()
        history = history.dropLast(1)
        hintText = "已撤销"
    }

    /** 一键回到模型原始输出。也走 pushEdit，所以"还原"本身还能被撤销回来 */
    fun resetEdits() {
        if (edits.isEmpty()) return
        pushEdit(emptyMap())
        hintText = "已还原为模型原始输出"
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

        // ── 进度 / 编辑工具条 + 重播
        // 三态：选中一个块 -> 块工具条；没选中但有改动 -> 撤销/还原；都没有 -> 原进度行。
        // 有改动时必须常驻撤销入口，否则删完块点一下空白，工具条消失就再也回不来了。
        val editing = ordered.firstOrNull { it.id in selectedIds }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            when {
                editing != null -> {
                    // 为什么全是按钮而不是拖拽：单击/长按拖动已经被"单选/合并"占满，
                    // 再塞一个"拖框边"必然抢事件；而 OCR 框的典型错误只是"整体偏几像素"，
                    // 点两下箭头比拖着对准更快，也没有手指挡住目标的问题。
                    val gone = edits[editing.id]?.hidden == true
                    Text(
                        text = if (gone) "${editing.id} 已删除" else editing.id,
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = if (gone) c.danger else c.muted,
                    )
                    NudgeKey("←", c.ink, c.surfaceCard, enabled = !gone) { nudge(editing, -NUDGE_STEP, 0) }
                    NudgeKey("→", c.ink, c.surfaceCard, enabled = !gone) { nudge(editing, NUDGE_STEP, 0) }
                    NudgeKey("↑", c.ink, c.surfaceCard, enabled = !gone) { nudge(editing, 0, -NUDGE_STEP) }
                    NudgeKey("↓", c.ink, c.surfaceCard, enabled = !gone) { nudge(editing, 0, NUDGE_STEP) }
                    if (!gone) ActionKey("删除", c.danger, c.surfaceCard) { hideBlock(editing) }
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoEdit() }
                }

                edits.isNotEmpty() -> {
                    Text(
                        text = "已修改 ${edits.size} 处",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoEdit() }
                    ActionKey("还原", c.danger, c.surfaceCard) { resetEdits() }
                }

                else -> {
                    val progress = if (activeIndex >= 0) "${activeIndex + 1}/${ordered.size}" else "完成"
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
                highlightBlockIds = ordered
                    .filter { it.id in effectiveIds && edits[it.id]?.hidden != true }
                    .map { it.id },
                onBlockTap = { id ->
                    Log.d("LinkDebug", "text tap -> $id")
                    ordered.firstOrNull { it.id == id }?.let { applySingle(it) }
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

/* ---------------------- 框编辑（叠加层，不改原数据） ---------------------- */

/** 微调步长（原图像素）。这页是 1400×1812、行高约 30px，8px 点两下就能对齐一行 */
private const val NUDGE_STEP = 8

/**
 * 一个区域的编辑记录。
 *
 * 关键：这不是"真值"，只是显示层的偏移。assets 里的 document.json 是模型输出，
 * 框偏了属于模型错误本身，不该被界面悄悄改掉 —— 所以这里只记差异，不回写任何文件。
 *
 * 记差异而不是直接改 bbox 的三个好处：
 *   - 撤销 = 弹回上一份快照，不用为每个操作写一对反向逻辑
 *   - 重置 = edits 清空，原数据仍在
 *   - 将来要导出"修正后结果"时，原始数据 + 差异 才是完整信息
 */
private data class BlockEdit(
    val bbox: List<Int>? = null,
    val hidden: Boolean = false,
)

/** 方向微调键：34dp 方块，点一次走一步 */
@Composable
private fun NudgeKey(
    label: String,
    fg: Color,
    bg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 15.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.30f),
        )
    }
}

/** 文字动作键：宽度跟着文字走，比方向键宽一点，避免"删除"被挤成两行 */
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