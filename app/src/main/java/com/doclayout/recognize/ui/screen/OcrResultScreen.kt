package com.doclayout.recognize.ui.screen

import com.doclayout.recognize.data.model.OcrLine
import com.doclayout.recognize.data.model.OcrLineKind
import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.data.local.decodeSampled
import com.doclayout.recognize.ui.component.CircleIconButton
import com.doclayout.recognize.ui.component.GlassCircleButton
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.component.PrimaryButton
import com.doclayout.recognize.ui.component.SecondaryButton
import com.doclayout.recognize.ui.icon.IconBack
import com.doclayout.recognize.ui.icon.IconChevronDown
import com.doclayout.recognize.ui.icon.IconCopy
import com.doclayout.recognize.ui.icon.IconExport
import com.doclayout.recognize.ui.icon.IconFeedback
import com.doclayout.recognize.ui.layout.OcrLayoutMode
import com.doclayout.recognize.ui.layout.ResizableRow
import com.doclayout.recognize.ui.layout.RestoreSplitPill
import com.doclayout.recognize.ui.layout.rememberOcrWindowInfo
import com.doclayout.recognize.ui.layout.rememberSplitPaneState
import com.doclayout.recognize.ui.render.OcrMarkdownView
import com.doclayout.recognize.ui.render.OcrTex
import com.doclayout.recognize.ui.render.joinMarkdown
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.sample.sampleRecords
import com.doclayout.recognize.ui.theme.LocalOcrColors
import com.doclayout.recognize.ui.theme.OcrColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 结果页的入口：**只做布局选择**，不画任何内容。
 *
 *   竖屏 / 窄屏 → [StackedResult]：全屏试卷 + 底部可拖抽屉（拇指够得到）
 *   横屏 / 宽屏 → [SplitResult]  ：左右分栏 + 可拖分割线（VS Code 那种）
 *
 * 两套布局吃同一份 [OcrRecord]，共用 PaperStage / PanelBody / ResultLine，
 * 所以"加一个字段"或"改一处排版"只改一次，不会两边不一致。
 *
 * 方向判定在 [rememberOcrWindowInfo]（Configuration.orientation + 宽度断点），
 * 这里不碰 maxWidth/maxHeight —— 布局选择就该是"看环境"，不是"看父容器"。
 */
@Composable
fun OcrResultScreen(
    record: OcrRecord,
    onBack: () -> Unit,
    onFeedback: () -> Unit = {},
    startOpen: Boolean = false,
    startSplit: Float = 0.5f,
) {
    when (rememberOcrWindowInfo().layout) {
        OcrLayoutMode.Stacked -> StackedResult(record, onBack, onFeedback, startOpen)
        OcrLayoutMode.Split -> SplitResult(record, onBack, onFeedback, startSplit)
    }
}

/* ----------------------------- 竖屏：底部抽屉 ----------------------------- */

@Composable
private fun StackedResult(
    record: OcrRecord,
    onBack: () -> Unit,
    onFeedback: () -> Unit,
    startOpen: Boolean,
) {
    val c = Ocr.colors

    BoxWithConstraints(Modifier.fillMaxSize().background(c.scrim)) {
        PaperStage(record = record, onBack = onBack, onFeedback = onFeedback, modifier = Modifier.fillMaxSize())
        FloatingSheet(
            record = record,
            peekH = 136.dp,
            halfH = maxHeight * 0.58f,
            // 留出顶栏（返回 + 标题）的高度，拖到最高时也从上面露出试卷和返回键
            fullH = maxHeight - 104.dp,
            startOpen = startOpen,
            panelBg = c.canvas,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/* ----------------------------- 横屏：左右分栏 ----------------------------- */

@Composable
private fun SplitResult(
    record: OcrRecord,
    onBack: () -> Unit,
    onFeedback: () -> Unit,
    startSplit: Float,
) {
    val c = Ocr.colors
    val split = rememberSplitPaneState(initialFraction = startSplit)
    // derivedStateOf：只在 0.4 阈值翻转时才重组 left 子树。
    // ResizableRow 改造后拖动帧只剩 remeasure，但这里若直接读 split.fraction，
    // left lambda 仍会每帧重跑 —— 收敛到 Boolean 后拖动帧完全不重跑
    val compact by remember { derivedStateOf { split.fraction < 0.4f } }

    Box(Modifier.fillMaxSize().background(c.scrim)) {
        ResizableRow(
            state = split,
            modifier = Modifier.fillMaxSize(),
            left = { m ->
                PaperStage(
                    record = record,
                    onBack = onBack,
                    onFeedback = onFeedback,
                    modifier = m,
                    // 分栏拖到很窄时，顶栏只留返回，别让标题挤成两行
                    compact = compact,
                )
            },
            right = { m ->
                ResultPanel(record = record, modifier = m, panelBg = c.canvas)
            },
        )

        // 任一侧被拖没了，必须有回来的路（分割线还在，但抓 14dp 太考手艺）
        if (split.isLeftCollapsed) {
            RestoreSplitPill(
                text = "查看原图",
                onClick = { split.reset() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(Space.md),
            )
        }
        if (split.isRightCollapsed) {
            RestoreSplitPill(
                text = "识别结果",
                onClick = { split.reset() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(Space.md),
            )
        }
    }
}

/* ----------------------------- 图片区 ----------------------------- */

@Composable
private fun PaperStage(
    record: OcrRecord,
    onBack: () -> Unit,
    onFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = Ocr.colors
    // 真机扫描：左边就是那张照片本身，没必要再用假纸面模拟
    if (record.isReal) {
        RealPaperStage(record = record, onBack = onBack, onFeedback = onFeedback, modifier = modifier, compact = compact)
        return
    }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 纸面尺寸自己算：宽 = min(可用宽, 可用高 × 0.72)。
        // 不直接用 aspectRatio(0.72f)，因为它在"高度先满足"时会算出超过父宽的宽度并溢出。
        // 分栏拉窄时可用宽会很小，两个减法的下限都要兜住，否则会算出负尺寸。
        val availW = (maxWidth - if (compact) 24.dp else 48.dp).coerceAtLeast(48.dp)
        val availH = (maxHeight - 156.dp).coerceAtLeast(120.dp)
        val paperW = minOf(availW, availH * 0.72f)
        val paperH = paperW / 0.72f

        Column(
            modifier = Modifier
                .width(paperW)
                .height(paperH)
                .shadow(18.dp, RoundedCornerShape(3.dp))
                .background(c.paper, RoundedCornerShape(3.dp))
                .padding(
                    horizontal = if (compact) 16.dp else 28.dp,
                    vertical = if (compact) 20.dp else 30.dp,
                )
                .verticalScroll(rememberScrollState()),
        ) {
            // 卷头
            Text(
                text = "2026 学年第一学期期末模拟",
                style = OcrType.Caption.copy(fontSize = 11.5.sp, letterSpacing = 1.2.sp),
                color = c.paperInk.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = record.title.substringBefore(" · "),
                style = OcrType.DisplaySm.copy(fontSize = 19.sp, lineHeight = 26.sp),
                color = c.paperInk,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("姓名 ______", "班级 ______", "得分 ______").forEach {
                    Text(it, style = OcrType.Caption.copy(fontSize = 10.5.sp), color = c.paperInk.copy(alpha = 0.62f))
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = c.paperInk.copy(alpha = 0.16f))
            Spacer(Modifier.height(12.dp))

            record.lines.forEach { line -> PaperLine(line) }
        }

        // 顶部浮层：返回 + 标题 + 页码
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(onClick = onBack) { IconBack(c.onScrim, size = 19.dp) }
            if (!compact) {
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        style = OcrType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = c.onScrim,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "第 1 / ${record.pages} 页 · ${record.engine}",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.onScrimSoft,
                        maxLines = 1,
                    )
                }
                GlassCircleButton(onClick = onFeedback) { IconFeedback(c.onScrim, size = 17.dp) }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 纸面上的一行：印刷体，字号比面板里小一号，才像"扫描件"而不是"摘要" */
@Composable
private fun PaperLine(line: OcrLine) {
    val c = Ocr.colors
    when (line.kind) {
        OcrLineKind.Heading -> {
            Spacer(Modifier.height(6.dp))
            Text(
                text = line.text,
                style = OcrType.TitleMd.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
                color = c.paperInk,
            )
            Spacer(Modifier.height(4.dp))
        }

        OcrLineKind.Body -> {
            Text(
                text = line.text,
                style = OcrType.Caption.copy(fontSize = 12.5.sp, lineHeight = 20.sp),
                color = c.paperInk.copy(alpha = 0.86f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        OcrLineKind.Formula -> {
            Text(
                text = line.text,
                style = OcrType.Formula.copy(fontSize = 13.sp, lineHeight = 21.sp),
                color = c.paperInk,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            )
        }

        OcrLineKind.Answer -> {
            Text(
                text = line.text,
                style = OcrType.Caption.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = c.primaryPressed,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/* --------------------------- 竖屏：悬浮结果卡片 --------------------------- */

/**
 * 悬浮卡片（不是贴底的抽屉）。
 *
 * 视觉上它是一张独立的纸卡片：左右各留 14dp、离底留 14dp（再让开导航条）、
 * 四角 22dp 圆角、24dp 投影 —— 下面那张试卷仍然看得见，所以它读起来是"盖在上面的一层"，
 * 而不是"页面被切成两半"。
 *
 * 三档高度，拖到哪算哪，松手吸附：
 *   156dp（只看标题）→ 58%（半屏）→ 满屏减 104dp（顶部还留着返回键和试卷的边）
 *
 * 高度不是用 animateDpAsState 直接跟着 stop 走的：那样"拖动中"和"松手吸附"
 * 会各自持有一个动画，松手瞬间会跳一下。这里只用一个 Animatable 存"当前高度"，
 * 拖动时暂时不喂它（用 dragTotal 叠上去），松手先把手指位置 snapTo 成起点，
 * 再 animateTo 最近的档位 —— 全程一条连续曲线，没有闪。
 */
@Composable
private fun FloatingSheet(
    record: OcrRecord,
    peekH: Dp,
    halfH: Dp,
    fullH: Dp,
    startOpen: Boolean,
    panelBg: Color,
    modifier: Modifier = Modifier,
) {
    val c = Ocr.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val stops = remember(peekH, halfH, fullH) { listOf(peekH, halfH, fullH) }
    // 停在哪一档也存下来：转屏回来还是同一档
    var stopIndex by rememberSaveable { mutableIntStateOf(if (startOpen) 1 else 0) }
    val anim = remember { Animatable(stops[stopIndex].value) }
    // 不用 by 委托：sheetHeight 的 measure 阶段要读 State<Float>（动画帧只 remeasure 不重组）
    val dragTotal = remember { mutableFloatStateOf(0f) }
    // anim 存的是绝对 dp：转屏/折叠后 stops 随窗口尺寸重建，旧值落在错档位上 —— 重新落档
    LaunchedEffect(peekH, halfH, fullH) { anim.snapTo(stops[stopIndex].value) }

    val peekPx = with(density) { peekH.toPx() }
    val fullPx = with(density) { fullH.toPx() }

    fun settleTo(index: Int) {
        stopIndex = index
        scope.launch {
            anim.animateTo(
                targetValue = stops[index].value,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    // 让开手势条，再留一圈外边距 —— 这一圈就是"悬浮"的来源
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp + navBottom)
            .fillMaxWidth()
            .sheetHeight(peekH, fullH, anim, dragTotal)
            .shadow(elevation = 24.dp, shape = shape, clip = false)
            .clip(shape)
            .background(panelBg)
            .border(1.dp, c.hairline.copy(alpha = 0.7f), shape),
    ) {
        // 抓取区：手柄 + 标题行，只有这一条能拖，正文留给滚动。
        // 这里刻意**不做**任何"按高度切换 Modifier"的判断 —— 一旦拖动中链结构变了，
        // pointerInput 节点会被重建，手势当场被取消（表现为"怎么拖都不动"）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetDrag(
                    onDrag = { total -> dragTotal.floatValue = total },
                    onEnd = { total ->
                        // anim 的数值本身就是 dp，换成 px 才能和手指位移相加；
                        // 上滑是负位移，卡片变高，所以是减
                        val nowPx = with(density) { anim.value.dp.toPx() } - total
                        val clamped = nowPx.coerceIn(peekPx, fullPx)
                        scope.launch {
                            // 先把"手指松开的位置"变成动画起点，再把 dragTotal 清零。
                            // 顺序反了会有 1 帧跳回旧高度。
                            anim.snapTo(with(density) { clamped.toDp() }.value)
                            dragTotal.floatValue = 0f
                            val nearest = stops.minByOrNull { abs(it.value - anim.value) }
                            val index = stops.indexOf(nearest ?: stops[1])
                            stopIndex = index
                            anim.animateTo(
                                targetValue = stops[index].value,
                                animationSpec = spring(
                                    dampingRatio = 0.88f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    },
                    onCancel = { dragTotal.floatValue = 0f },
                ),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(c.hairline)
                    .align(Alignment.CenterHorizontally),
            )
            PanelHeader(
                record = record,
                open = stopIndex > 0,
                onToggle = { settleTo(if (stopIndex == 0) 1 else 0) },
                horizontalPadding = Space.lg,
            )
        }

        // 正文显隐用离散档位而不是连续高度：拖动中每帧增删 PanelBody（含 WebView 链）
        // 的节点是逐帧组合开销；拖动中正文等松手吸附后才出现，属可接受的观感变化
        if (stopIndex > 0) {
            PanelBody(record = record, modifier = Modifier.weight(1f))
        }
    }
}


/**
 * 卡片高度修饰符：高度 = 当前动画值 + 手指拖动量（clamp 到 peek..full）。
 * anim/dragTotal 都在 layout（测量）阶段读取 —— 动画帧只 remeasure 这一棵子树，
 * 不再像旧写法那样在组合期读两个 State 交给 .height()，拖动/吸附每帧重组整卡。
 */
private fun Modifier.sheetHeight(
    peekH: Dp,
    fullH: Dp,
    anim: Animatable<Float, *>,
    dragTotal: State<Float>,
): Modifier = layout { measurable, constraints ->
    // 手指上滑 = dy 为负 = 卡片要变高，所以拖动量取反。
    // dragTotal 以 State<Float> 接口传入（读 .value 即可，layout 阶段读 State 只触发 remeasure）
    val hPx = (anim.value.dp + with(this@layout) { (-dragTotal.value).toDp() })
        .coerceIn(peekH, fullH)
        .roundToPx()
    val p = measurable.measure(constraints.copy(minHeight = hPx, maxHeight = hPx))
    layout(p.width, p.height) { p.placeRelative(0, 0) }
}


/**
 * 竖直拖拽修饰符。
 *
 * 回调用 rememberUpdatedState 兜住：如果直接把 lambda 当 pointerInput 的 key，
 * 拖动过程中每次重组都会重启手势识别，手一松就断；用 Unit 做 key 又会拿到旧闭包（高度永远不变）。
 */
@Composable
private fun Modifier.sheetDrag(
    onDrag: (Float) -> Unit,
    onEnd: (Float) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val drag = rememberUpdatedState(onDrag)
    val end = rememberUpdatedState(onEnd)
    val cancel = rememberUpdatedState(onCancel)
    return this.pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { cancel.value() },
            onDragEnd = { end.value(total) },
            onVerticalDrag = { change, amount ->
                change.consume()
                total += amount
                drag.value(total)
            },
        )
    }
}

@Composable
private fun PanelHeader(
    record: OcrRecord,
    open: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    horizontalPadding: Dp,
) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "识别结果", style = OcrType.DisplaySm, color = c.ink)
                Spacer(Modifier.width(Space.xs))
                Pill(text = "Markdown")
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = record.meta.ifEmpty {
                    "置信度 ${record.confidence} · ${record.duration} · ${record.lines.size} 个文本块"
                },
                style = OcrType.Caption.copy(fontSize = 12.5.sp),
                color = c.muted,
            )
        }
        if (onToggle != null) {
            CircleIconButton(onClick = onToggle) {
                IconChevronDown(
                    tint = c.ink,
                    size = 18.dp,
                    modifier = Modifier.rotate(if (open == true) 180f else 0f),
                )
            }
        }
    }
}

@Composable
private fun PanelBody(record: OcrRecord, modifier: Modifier = Modifier) {
    val c = Ocr.colors
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg),
    ) {
        HorizontalDivider(thickness = 1.dp, color = c.hairlineSoft)

        // 真机记录：正文交给 WebView + KaTeX（专题A 同一套渲染器），
        // 表格、行内/块级公式、列表都由 markdown-it + KaTeX 处理，原生这边不再重复排版。
        if (record.isReal) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                OcrMarkdownView(
                    markdown = record.scan.joinMarkdown(),
                    modifier = Modifier.fillMaxSize().padding(top = Space.xs),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.md, top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                PrimaryButton(
                    text = "复制 Markdown",
                    onClick = { clipboard.setText(AnnotatedString(record.scan.joinMarkdown())) },
                    modifier = Modifier.weight(1f),
                ) { fg ->
                    IconCopy(fg, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                }
                SecondaryButton(
                    text = "复制纯文本",
                    onClick = {
                        val plain = record.scan.joinMarkdown()
                            .replace(Regex("```[\\s\\S]*?```"), "")
                            .replace(Regex("[#*`|>_-]{1,}"), "")
                        clipboard.setText(AnnotatedString(plain))
                    },
                    modifier = Modifier.weight(1f),
                ) { fg ->
                    IconExport(fg, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                }
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = Space.xs, bottom = Space.xs),
            ) {
                record.lines.forEach { line -> ResultLine(line) }
            }
            // 底部渐隐：不然滚动区会正好切在半个字上，看着像渲染坏了
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, c.canvas),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.md, top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            PrimaryButton(
                text = "复制文本",
                onClick = {},
                modifier = Modifier.weight(1f),
            ) { fg ->
                IconCopy(fg, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            SecondaryButton(
                text = "导出文件",
                onClick = {},
                modifier = Modifier.weight(1f),
            ) { fg ->
                IconExport(fg, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/* --------------------------- 横屏右栏 --------------------------- */

/**
 * 横屏的右栏：标题 + 文本 / 公式 + 操作按钮，宽度由分栏状态决定。
 * 和竖屏抽屉共用 PanelBody，区别只是不再有"拖拽把手"和收起箭头 ——
 * 收起的动作交给中间那条分割线，右栏本身不需要第二个开关。
 */
@Composable
private fun ResultPanel(
    record: OcrRecord,
    modifier: Modifier = Modifier,
    panelBg: Color,
) {
    // 背景铺到状态栏/导航条下面，内容用 safeDrawingPadding 让开
    Box(modifier.background(panelBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 分栏拖到 0:1 时右栏会铺满 1280dp，一行文字过宽反而不好读；
            // 正文限到 720dp 居中，背景仍然铺满 —— "占据屏幕"和"行宽"是两件事。
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                PanelHeader(record = record, horizontalPadding = Space.lg)
                PanelBody(record = record, modifier = Modifier.weight(1f))
            }
        }
    }
}

/* -------------------------- 面板里的一行文本 -------------------------- */

@Composable
private fun ResultLine(line: OcrLine) {
    val c = Ocr.colors
    when (line.kind) {
        OcrLineKind.Heading -> {
            Spacer(Modifier.height(6.dp))
            Text(text = line.text, style = OcrType.TitleMd, color = c.ink)
            Spacer(Modifier.height(4.dp))
        }

        OcrLineKind.Body -> Text(
            text = line.text,
            style = OcrType.Body,
            color = c.body,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        OcrLineKind.Formula -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(Radii.md))
                .background(c.surfaceSoft)
                .padding(horizontal = Space.sm, vertical = 9.dp),
        ) {
            // 有 LaTeX 源码就用 RaTeX 真排版（矢量、可缩放、不引 WebView）；
            // 没有或解析失败就退化成识别出来的纯文本。
            OcrTex(
                tex = line.tex ?: line.text,
                fontSize = 16.sp,
                // 和 WebView 那条路径保持一致：公式统一用主色，不跟随正文墨色
                color = c.primary,
                fallback = line.text,
            )
        }

        OcrLineKind.Answer -> Row(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(2.dp).height(14.dp).background(c.primary))
            Spacer(Modifier.width(Space.xs))
            Text(text = line.text, style = OcrType.BodySm, color = c.muted)
        }
    }
}

/* --------------------------- 真机扫描：原图区 --------------------------- */

/**
 * 左侧（竖屏是整屏底图）显示真正的扫描件。
 *
 * 三件事必须做对，否则真机上一眼就能看出是"套壳"：
 *   1. **采样解码**：手机拍的试卷是 4000×3000，直接 decodeFile 会吃掉 48MB 内存，
 *      连开两页就 OOM。先只读边界拿尺寸、算 inSampleSize，再解码。
 *   2. **ContentScale.Fit + 居中**：照片长宽比和任何容器都不一致，宁可留白也不能裁掉题干。
 *   3. **多页用 Pager**：合并成一卷时这里是左右滑，页面指示器在顶部跟着走。
 */
@Composable
private fun RealPaperStage(
    record: OcrRecord,
    onBack: () -> Unit,
    onFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = Ocr.colors
    val pager = rememberPagerState(pageCount = { record.scan.size })

    Box(modifier.background(c.scrim)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp,
        ) { index ->
            val page = record.scan[index]
            // 翻页解码走 IO + 三态：旧写法 remember 同步解码，解码直接落进滑动手势帧必掉帧
            val imageState by produceState<PageImageState>(PageImageState.Loading, page.imagePath) {
                val path = page.imagePath
                val bmp = withContext(Dispatchers.IO) { decodeSampled(path, maxSide = 1440) }
                value = if (bmp != null) PageImageState.Ready(bmp) else PageImageState.Missing
            }
            // 系统栏让位不拍死 92/108dp：挖孔设备状态栏比 92dp 高时原图会贴死；
            // = 对应 insets + 固定余量（顶部留返回键浮层，底部留卡片露出量）
            val insetsTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val insetsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                modifier = Modifier.fillMaxSize().padding(
                    start = if (compact) 8.dp else 16.dp,
                    end = if (compact) 8.dp else 16.dp,
                    top = insetsTop + 64.dp,
                    bottom = insetsBottom + (if (compact) 76.dp else 88.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                when (val st = imageState) {
                    PageImageState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = c.onScrim,
                    )
                    is PageImageState.Ready -> Image(
                        bitmap = st.bitmap.asImageBitmap(),
                        contentDescription = "第 ${index + 1} 页原图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    PageImageState.Missing -> Text(
                        "原图丢了（文件被删除或外部清理）",
                        style = OcrType.Caption,
                        color = c.onScrim,
                    )
                }
            }
        }

        // 顶部浮层：返回 + 标题 + 页码
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(onClick = onBack) { IconBack(c.onScrim, size = 19.dp) }
            if (!compact) {
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        style = OcrType.Body,
                        color = c.onScrim,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "第 ${pager.currentPage + 1} / ${record.scan.size} 页 · ${record.engine}",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.onScrimSoft,
                        maxLines = 1,
                    )
                }
                Pill(
                    text = record.duration,
                    background = c.scrim.copy(alpha = 0.5f),
                    color = c.onScrim,
                )
                Spacer(Modifier.width(Space.xs))
                // 识别问题反馈（带本记录的原图与识别结果，见 OcrFeedbackScreen）
                GlassCircleButton(onClick = onFeedback) { IconFeedback(c.onScrim, size = 17.dp) }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 结果页页原图的三态：翻页解码挪到 IO 线程后，页面渲染要区分
 * 「还在解 / 解到了 / 解不到（文件被清）」，各给一个占位。
 */
private sealed interface PageImageState {
    data object Loading : PageImageState
    data class Ready(val bitmap: Bitmap) : PageImageState
    data object Missing : PageImageState
}

/* ------------------------------------------------------------------ *
 * 预览组：竖屏（Stacked 抽屉）与横屏（Split 分栏）两形态都要能看。
 * 喂 sampleRecords[0]（假样例，isReal=false，不走 WebView/原图解码）。
 * ------------------------------------------------------------------ */
@Preview(name = "phone", showBackground = true, device = Devices.PHONE)
@Preview(name = "landscape", showBackground = true, widthDp = 800, heightDp = 380)
@Preview(name = "foldable", showBackground = true, device = Devices.FOLDABLE)
@Preview(name = "tablet", showBackground = true, device = Devices.TABLET)
@Composable
private fun OcrResultScreenPreview() {
    CompositionLocalProvider(LocalOcrColors provides OcrColors.Light) {
        OcrResultScreen(
            record = sampleRecords[0],
            onBack = {},
        )
    }
}
