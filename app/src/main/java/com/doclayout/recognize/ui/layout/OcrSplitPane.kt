package com.doclayout.recognize.ui.layout

import com.doclayout.recognize.ui.icon.IconChevronRight
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Ocr
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 左右分栏 + 可拖动分割线（VS Code 那种）。
 *
 * 三个关键决定：
 *
 * 1. **宽度用「比例」而不是「像素」存**。fraction 只在 0..1 之间，
 *    转屏、分屏、换密度都不用重新算，两个面板永远撑满。
 *
 * 2. **拖动跟手，松手吸附**。拖动时直接改 fraction（每帧 remeasure，跟指）；
 *    松手后用 spring 动画吸到最近的锚点上，而不是"松手瞬间跳变"。
 *    锚点默认 {0, 0.25, 0.5, 0.75, 1} —— 也就是 0:1 / 1:3 / 1:1 / 3:1 / 1:0 五档，
 *    其中 0 和 1 就是你说的"一侧完全占据"。
 *
 * 3. **不用 anchoredDraggable**。它适合"少量固定位置 + 强吸附"的开关型交互，
 *    但这里要的是连续跟手、松手才吸附；自己拿 detectDragGestures + Animatable 更直接，
 *    也不用吃 ExperimentalFoundationApi。分栏两侧的 0 宽度用「不测量不放置」兜住
 *    （内容保留在组合中，见 ResizableRow 的注释）。
 */

/** 默认吸附档位：0:1 / 1:3 / 1:1 / 3:1 / 1:0 */
val DefaultSplitAnchors: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/**
 * 分栏状态。放在页面这一层持有，这样"恢复分栏"的按钮也能直接改它。
 */
@Stable
class SplitPaneState internal constructor(
    initialFraction: Float,
    val anchors: List<Float>,
    private val scope: CoroutineScope,
    /** 停稳之后回调一次，用来把比例存进 rememberSaveable / SavedStateHandle */
    private val onSettled: (Float) -> Unit = {},
) {
    /** 左侧占比 0..1 */
    var fraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))
        private set

    /** 是否正在拖（分割线要变珊瑚色、显示比例） */
    var dragging by mutableStateOf(false)
        private set

    private var job: Job? = null

    internal fun beginDrag() {
        job?.cancel()
        dragging = true
    }

    internal fun dragBy(deltaFraction: Float) {
        fraction = (fraction + deltaFraction).coerceIn(0f, 1f)
    }

    internal fun endDrag() {
        dragging = false
        snapToNearest()
        onSettled(fraction)
    }

    /** 动画到某个比例（恢复分栏、双击复位都走这里） */
    fun animateTo(target: Float) {
        job?.cancel()
        val to = target.coerceIn(0f, 1f)
        job = scope.launch {
            animate(
                initialValue = fraction,
                targetValue = to,
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { value, _ -> fraction = value }
            onSettled(fraction)
        }
    }

    /** 吸附到最近的档位 */
    fun snapToNearest() {
        val nearest = anchors.minByOrNull { abs(it - fraction) } ?: fraction
        animateTo(nearest)
    }

    /** 回到 1:1 */
    fun reset() = animateTo(0.5f)

    val isLeftCollapsed: Boolean get() = fraction <= 0.001f
    val isRightCollapsed: Boolean get() = fraction >= 0.999f

    /** 拖到哪一档的比例文案，例如 "1 : 3" */
    val ratioLabel: String
        get() {
            val left = (fraction * 4).roundToInt().coerceIn(0, 4)
            val right = 4 - left
            val d = gcd(left, right).coerceAtLeast(1)
            return "${left / d} : ${right / d}"
        }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

@Composable
fun rememberSplitPaneState(
    initialFraction: Float = 0.5f,
    anchors: List<Float> = DefaultSplitAnchors,
): SplitPaneState {
    val scope = rememberCoroutineScope()
    // 记住"上次停在哪一档"：转屏、被系统回收再回来都还在。
    // rememberSaveable 和 SavedStateHandle 是**同一套** SavedStateRegistry，
    // 区别只是前者挂在 Composable 上、后者挂在 ViewModel 上（见 OcrApp 里的注释）。
    val saved = rememberSaveable { mutableFloatStateOf(initialFraction) }
    return remember {
        SplitPaneState(saved.floatValue, anchors, scope) { saved.floatValue = it }
    }
}

/**
 * 左右分栏。
 *
 * 宽度分配走自定义 [Layout]：fraction 在 measure 阶段读取，拖动每帧只触发
 * remeasure，不再重组整个分栏内容（左栏 Pager+位图、右栏 WebView 的 skip 检查每帧都跑）。
 *
 * @param left 左面板，收到的 Modifier 已在测量阶段被固定宽度，**必须透传给内容根节点**
 *             （否则内容不消费固定宽度约束，高度会塌陷）
 * @param right 右面板，同上
 */
@Composable
fun ResizableRow(
    state: SplitPaneState,
    modifier: Modifier = Modifier,
    dividerWidth: Dp = 14.dp,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    // 拖动位移换算用的可用宽度：组合期拿不到（宽度 measure 才知道），
    // 测量期写入、SplitDivider 的拖动回调里读取
    val usablePxState = remember { mutableFloatStateOf(1f) }
    Layout(
        content = {
            Box(Modifier.layoutId("left")) { left(Modifier.fillMaxSize()) }
            Box(Modifier.layoutId("divider")) {
                SplitDivider(state, usablePxState.floatValue, dividerWidth)
            }
            Box(Modifier.layoutId("right")) { right(Modifier.fillMaxSize()) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        // ← 这里读 state.fraction：Layout 阶段读 State 只触发 remeasure 不触发重组
        val dividerPx = dividerWidth.roundToPx()
        val usable = (constraints.maxWidth - dividerPx).coerceAtLeast(0)
        val f = state.fraction
        val leftPx = (usable * f).roundToInt()
        val rightPx = usable - leftPx
        usablePxState.floatValue = usable.toFloat().coerceAtLeast(1f)
        // 0 宽度时不测量不放置（内容保留在组合中）：收起侧的 remember 状态
        // （Pager 页码、已解码位图）由旧版"不组合即丢弃"变成"保留"，属良性差异；
        // 两侧都是固定宽度约束，子组件不可能拿到 0/负约束，无需再防御
        val leftPlaceable = measurables.first { it.layoutId == "left" }
            .takeIf { leftPx > 1 }
            ?.measure(constraints.copy(minWidth = leftPx, maxWidth = leftPx))
        val dividerPlaceable = measurables.first { it.layoutId == "divider" }
            .measure(constraints.copy(minWidth = dividerPx, maxWidth = dividerPx))
        val rightPlaceable = measurables.first { it.layoutId == "right" }
            .takeIf { rightPx > 1 }
            ?.measure(constraints.copy(minWidth = rightPx, maxWidth = rightPx))
        layout(constraints.maxWidth, constraints.maxHeight) {
            leftPlaceable?.placeRelative(0, 0)
            dividerPlaceable.placeRelative(leftPx, 0)
            rightPlaceable?.placeRelative(leftPx + dividerPx, 0)
        }
    }
}

/** 分割线：14dp 的可拖热区里放一根 1dp 线 + 一个胶囊把手 */
@Composable
private fun SplitDivider(
    state: SplitPaneState,
    usablePx: Float,
    width: Dp,
) {
    val c = Ocr.colors
    val active = state.dragging

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .pointerInput(state, usablePx) {
                detectDragGestures(
                    onDragStart = { state.beginDrag() },
                    onDragEnd = { state.endDrag() },
                    onDragCancel = { state.endDrag() },
                    onDrag = { change, amount ->
                        change.consume()
                        // Offset 的字段是 x/y（dx/dy 是 DrawScope 里的别的类型），
                        // 按可用宽度折算成比例，拖动才跟手
                        state.dragBy(amount.x / usablePx)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1dp 的分割线本身
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(if (active) c.primary.copy(alpha = 0.45f) else c.hairline),
        )
        // 把手：拖动时变珊瑚色、变长
        Box(
            Modifier
                .width(if (active) 5.dp else 4.dp)
                .height(if (active) 64.dp else 44.dp)
                .clip(CircleShape)
                .background(if (active) c.primary else c.mutedSoft.copy(alpha = 0.7f)),
        )
        // 拖动中显示当前比例（松手后会停到 0:1 / 1:3 / 1:1 / 3:1 / 1:0 之一）
        if (active) {
            Box(
                Modifier
                    .offset(y = (-56).dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(c.scrim.copy(alpha = 0.9f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = state.ratioLabel,
                    style = OcrType.Caption.copy(fontSize = 11.sp),
                    color = c.onScrim,
                )
            }
        }
    }
}

/** 某一侧被拖没了的"恢复入口"。不给按钮，用户就真的回不来了。 */
@Composable
fun RestoreSplitPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingArrow: Boolean = true,
) {
    val c = Ocr.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.scrimElevated.copy(alpha = 0.94f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = OcrType.Caption.copy(fontSize = 12.5.sp),
            color = c.onScrim,
        )
        if (trailingArrow) {
            Spacer(Modifier.width(6.dp))
            IconChevronRight(c.onScrim, size = 12.dp, stroke = 1.5.dp)
        }
    }
}
