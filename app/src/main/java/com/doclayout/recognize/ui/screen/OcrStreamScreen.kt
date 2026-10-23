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
import com.doclayout.recognize.data.local.OcrStreamDemo
import com.doclayout.recognize.data.model.OcrStreamPage
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.render.OcrMarkdownView
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.doclayout.recognize.data.model.OcrStreamBlock
import kotlinx.coroutines.delay
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

    // 上下联动：当前选中的区域 id（点原图框或点正文都会改它）
    var selectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playKey, data) {
        doneText = ""
        typing = ""
        activeIndex = -1
        selectedId = null
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

    val shown = when {
        typing.isEmpty() -> doneText
        doneText.isEmpty() -> typing
        // 空锚点把"正在打字"这段从上一个区域里隔离出来，点它不会命中错区域
        else -> "$doneText\n\n@@blk:@@\n$typing"
    }
    val activeBlock = ordered.getOrNull(activeIndex)

// 四色分配：纯计算，一次算好；不要写成在 DrawScope 里现算
val colorOf = remember(ordered) { assignBlockColors(ordered) }

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

                // activeIndex 就是进度：它之前的块都已经"识别过"，所以已识别集合不用另存状态。
                // 加一个 mutableList 记录"画过的框"等于两份数据要同步，迟早不一致。
                val revealed = if (activeIndex >= 0) ordered.take(activeIndex + 1) else ordered

                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        // 命中判断：屏幕坐标反算回原图坐标。Compose 不需要叠一层透明矩形
                        // （那是 View 的 hit-test 思路），直接算落在哪个 bbox 里就行。
                        .pointerInput(scale, dx, dy, ordered) {
                            detectTapGestures { pos ->
                                val ix = (pos.x - dx) / scale
                                val iy = (pos.y - dy) / scale
                                ordered.firstOrNull { b ->
                                    b.bbox.size == 4 &&
                                        ix >= b.bbox[0] && ix <= b.bbox[2] &&
                                        iy >= b.bbox[1] && iy <= b.bbox[3]
                                }?.let { tapped ->
                                    // 再点同一块 = 取消选中
                                    selectedId = if (selectedId == tapped.id) null else tapped.id
                                    Log.d("LinkDebug", "image tap -> ${tapped.id}")
                                    onBlockClick(tapped)
                                }
                            }
                        },
                ) {
                    revealed.forEach { block ->
                        val b = block.bbox
                        if (b.size != 4) return@forEach
                        val isCurrent = block.id == activeBlock?.id
                        val isSelected = block.id == selectedId
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

        // ── 进度 + 重播
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            val progress = if (activeIndex >= 0) "${activeIndex + 1}/${ordered.size}" else "完成"
            Pill(text = progress, background = c.surfaceCard, color = c.muted)
            if (activeBlock != null) {
                Text(
                    text = "${activeBlock.type} · ${activeBlock.id}",
                    style = OcrType.Caption.copy(fontSize = 12.sp),
                    color = c.mutedSoft,
                )
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
                autoScrollBottom = selectedId == null,
                highlightBlockId = selectedId,
                onBlockTap = { id -> Log.d("LinkDebug", "text tap -> $id"); selectedId = id },
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
