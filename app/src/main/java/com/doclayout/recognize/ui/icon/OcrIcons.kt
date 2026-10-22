package com.doclayout.recognize.ui.icon

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 线描图标，全部用 Canvas 画，不引 material-icons-extended。
 *
 * 为什么不引图标库：extended 有 7MB 左右的 method 数，只为 6 个图标不值得；
 * 而且 Claude 的线条风格（1.6dp 圆头描边）和 Material 的实心图标本来就不是一回事。
 * 这里所有坐标都按 0f..1f 归一化，换 16dp 还是 32dp 都只改 size。
 */

private fun DrawScope.strokeOf(width: Dp) = Stroke(
    width = width.toPx(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

@Composable
private fun Glyph(
    tint: Color,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Float, Color) -> Unit,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        draw(w, tint)
    }
}

/** 相机：机身 + 镜头 + 顶部取景凸起 */
@Composable
fun IconCamera(tint: Color, size: Dp = 22.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.06f, w * 0.27f),
            size = Size(w * 0.88f, w * 0.58f),
            cornerRadius = CornerRadius(w * 0.15f),
            style = s,
        )
        drawCircle(c, radius = w * 0.165f, center = Offset(w * 0.5f, w * 0.56f), style = s)
        drawPath(
            Path().apply {
                moveTo(w * 0.33f, w * 0.27f)
                lineTo(w * 0.39f, w * 0.16f)
                lineTo(w * 0.61f, w * 0.16f)
                lineTo(w * 0.67f, w * 0.27f)
            },
            c,
            style = s,
        )
    }

/** 相册：外框 + 小太阳 + 山线 */
@Composable
fun IconGallery(tint: Color, size: Dp = 22.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.08f, w * 0.14f),
            size = Size(w * 0.84f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = s,
        )
        drawCircle(c, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.36f), style = s)
        drawPath(
            Path().apply {
                moveTo(w * 0.14f, w * 0.72f)
                lineTo(w * 0.36f, w * 0.48f)
                lineTo(w * 0.51f, w * 0.63f)
                lineTo(w * 0.63f, w * 0.51f)
                lineTo(w * 0.86f, w * 0.74f)
            },
            c,
            style = s,
        )
    }

/** 设置：三条滑杆（比齿轮更"设置"，也不会在 22dp 下糊成一团） */
@Composable
fun IconSliders(tint: Color, size: Dp = 20.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        val rows = listOf(0.26f to 0.36f, 0.5f to 0.66f, 0.74f to 0.44f)
        rows.forEach { (y, knob) ->
            drawLine(c, Offset(w * 0.12f, w * y), Offset(w * 0.88f, w * y), s.width, StrokeCap.Round)
            drawCircle(c, radius = w * 0.085f, center = Offset(w * knob, w * y))
        }
    }

@Composable
fun IconChevronRight(tint: Color, size: Dp = 16.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        drawPath(
            Path().apply {
                moveTo(w * 0.4f, w * 0.26f)
                lineTo(w * 0.62f, w * 0.5f)
                lineTo(w * 0.4f, w * 0.74f)
            },
            c,
            style = strokeOf(stroke),
        )
    }

@Composable
fun IconChevronDown(tint: Color, size: Dp = 18.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        drawPath(
            Path().apply {
                moveTo(w * 0.26f, w * 0.4f)
                lineTo(w * 0.5f, w * 0.62f)
                lineTo(w * 0.74f, w * 0.4f)
            },
            c,
            style = strokeOf(stroke),
        )
    }

@Composable
fun IconBack(tint: Color, size: Dp = 20.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawPath(
            Path().apply {
                moveTo(w * 0.56f, w * 0.24f)
                lineTo(w * 0.3f, w * 0.5f)
                lineTo(w * 0.56f, w * 0.76f)
            },
            c,
            style = s,
        )
        drawLine(c, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.86f, w * 0.5f), s.width, StrokeCap.Round)
    }

/** 文档：空状态插画、结果页都用它 */
@Composable
fun IconDoc(tint: Color, size: Dp = 40.dp, stroke: Dp = 1.6.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawRoundRect(
            color = c,
            topLeft = Offset(w * 0.26f, w * 0.1f),
            size = Size(w * 0.48f, w * 0.8f),
            cornerRadius = CornerRadius(w * 0.07f),
            style = s,
        )
        listOf(0.34f, 0.46f, 0.58f, 0.7f).forEachIndexed { i, y ->
            val right = if (i == 3) 0.56f else 0.64f
            drawLine(c, Offset(w * 0.36f, w * y), Offset(w * right, w * y), s.width, StrokeCap.Round)
        }
    }

@Composable
fun IconCopy(tint: Color, size: Dp = 18.dp, stroke: Dp = 1.5.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawRoundRect(
            c,
            topLeft = Offset(w * 0.34f, w * 0.34f),
            size = Size(w * 0.52f, w * 0.52f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = s,
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.62f, w * 0.28f)
                lineTo(w * 0.62f, w * 0.16f)
                lineTo(w * 0.16f, w * 0.16f)
                lineTo(w * 0.16f, w * 0.62f)
                lineTo(w * 0.28f, w * 0.62f)
            },
            c,
            style = s,
        )
    }

@Composable
fun IconExport(tint: Color, size: Dp = 18.dp, stroke: Dp = 1.5.dp, modifier: Modifier = Modifier) =
    Glyph(tint, size, stroke, modifier) { w, c ->
        val s = strokeOf(stroke)
        drawLine(c, Offset(w * 0.5f, w * 0.62f), Offset(w * 0.5f, w * 0.12f), s.width, StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.3f, w * 0.32f)
                lineTo(w * 0.5f, w * 0.12f)
                lineTo(w * 0.7f, w * 0.32f)
            },
            c,
            style = s,
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.2f, w * 0.56f)
                lineTo(w * 0.2f, w * 0.86f)
                lineTo(w * 0.8f, w * 0.86f)
                lineTo(w * 0.8f, w * 0.56f)
            },
            c,
            style = s,
        )
    }

/** Claude 的四角星标 —— 品牌记号，只出现在标题左侧 */
@Composable
fun SparkMark(tint: Color, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val p = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w * 0.57f, w * 0.43f)
            lineTo(w, w * 0.5f)
            lineTo(w * 0.57f, w * 0.57f)
            lineTo(w * 0.5f, w)
            lineTo(w * 0.43f, w * 0.57f)
            lineTo(0f, w * 0.5f)
            lineTo(w * 0.43f, w * 0.43f)
            close()
        }
        drawPath(p, tint)
    }
}
