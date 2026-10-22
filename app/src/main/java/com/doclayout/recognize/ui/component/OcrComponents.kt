package com.doclayout.recognize.ui.component

import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.ui.icon.IconBack
import com.doclayout.recognize.ui.icon.IconChevronRight
import com.doclayout.recognize.ui.icon.IconDoc
import com.doclayout.recognize.ui.icon.SparkMark
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ *
 * 这一层是"设计系统 → Compose 组件"的映射。
 * 页面文件里只写 OcrTopBar / ActionCard / SettingRow 这些名字，不直接写颜色和 dp，
 * 这样换皮只改这里，页面不动 —— 和 CSS 里用 class 不用内联 style 是同一件事。
 * ------------------------------------------------------------------ */

/** 圆形描边按钮（36dp，DESIGN.md 的 button-icon-circular） */
@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Ocr.colors.canvas,
    borderColor: Color = Ocr.colors.hairline,
    size: Dp = 38.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 顶部栏：左侧衬线标题（+ 可选星标），右侧一个圆形按钮位 */
@Composable
fun OcrTopBar(
    title: String,
    subtitle: String? = null,
    mark: Boolean = false,
    onBack: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconButton(onClick = onBack) { IconBack(c.ink, size = 18.dp) }
            Spacer(Modifier.width(Space.sm))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mark) {
                    SparkMark(c.primary, size = 15.dp)
                    Spacer(Modifier.width(Space.xs))
                }
                Text(text = title, style = OcrType.Display, color = c.ink)
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = OcrType.Caption, color = c.muted)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(Space.sm))
            action()
        }
    }
}

/**
 * 主操作卡：占屏幕上半部、两块并排。
 * primary = true 用整块珊瑚色 —— DESIGN.md 允许珊瑚铺满"全幅卡片"，
 * 但同一屏只能有一块，否则强调就失效了。
 */
@Composable
fun ActionCard(
    label: String,
    hint: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val c = Ocr.colors
    val bg = if (primary) c.primary else c.surfaceCard
    val fg = if (primary) c.onPrimary else c.ink
    val hintColor = if (primary) c.onPrimary.copy(alpha = 0.74f) else c.muted
    val iconBg = if (primary) c.onPrimary.copy(alpha = 0.16f) else c.canvas

    Column(
        modifier = modifier
            .height(198.dp)
            .clip(RoundedCornerShape(Radii.lg))
            .background(bg)
            .border(1.dp, if (primary) c.primaryPressed.copy(alpha = 0.35f) else c.hairline, RoundedCornerShape(Radii.lg))
            .clickable(onClick = onClick)
            .padding(Space.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) { icon(fg) }

        Column {
            Text(text = label, style = OcrType.DisplaySm, color = fg)
            Spacer(Modifier.height(4.dp))
            Text(text = hint, style = OcrType.Caption, color = hintColor)
        }
    }
}

/**
 * 试卷缩略图：用 Canvas 画"一页纸"。
 *
 * 为什么不用真图片：这个工程没有资源目录，而且缩略图在 108dp 高的时候，
 * 抽象成"标题两行 + 正文线 + 一块公式区"反而比真照片更易读（也更省内存）。
 */
@Composable
fun PaperThumb(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    rotation: Float = 0f,
    imagePath: String? = null,
) {
    val c = Ocr.colors

    // 真实扫描记录的缩略图就是那张照片；假样例才用 Canvas 画"一页纸"。
    // 采样到 360px：卡片最大也就 168dp 高，按 2.5x 屏算够用了，还省内存。
    if (imagePath != null) {
        val bitmap = remember(imagePath) { decodeThumb(imagePath, 360) }
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(Radii.md))
                .background(c.creamStrong)
                .border(1.dp, c.hairline, RoundedCornerShape(Radii.md)),
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.md))
            .background(c.paper)
            .border(1.dp, c.hairline, RoundedCornerShape(Radii.md)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
            val w = size.width
            val h = size.height
            fun line(y: Float, from: Float, to: Float, alpha: Float, thick: Float = 0.018f) {
                drawRoundRect(
                    color = c.paperInk.copy(alpha = alpha),
                    topLeft = Offset(w * from, h * y),
                    size = Size(w * (to - from), h * thick),
                    cornerRadius = CornerRadius(h * 0.01f),
                )
            }

            // 标题两行，居中
            line(0.04f, 0.24f, 0.76f, 0.52f, 0.024f)
            line(0.10f, 0.36f, 0.64f, 0.28f)
            // 正文
            line(0.24f, 0.0f, 0.92f, 0.20f)
            line(0.32f, 0.0f, 0.84f, 0.20f)
            line(0.40f, 0.0f, 0.90f, 0.20f)
            // 公式区：一块浅色底 + 一条居中的短横线
            drawRoundRect(
                color = c.paperInk.copy(alpha = 0.07f),
                topLeft = Offset(w * 0.08f, h * 0.52f),
                size = Size(w * 0.84f, h * 0.18f),
                cornerRadius = CornerRadius(h * 0.02f),
            )
            line(0.585f, 0.24f, 0.72f, 0.5f, 0.02f)
            // 后面的题
            line(0.78f, 0.0f, 0.88f, 0.20f)
            line(0.86f, 0.0f, 0.62f, 0.20f)

            if (accent) {
                // 一处珊瑚色批注：像老师在答案下划的一道红线。
                // 放左下角，避开右下角的页数徽章。
                drawRoundRect(
                    color = c.primary,
                    topLeft = Offset(w * 0.06f, h * 0.775f),
                    size = Size(w * 0.34f, h * 0.018f),
                    cornerRadius = CornerRadius(h * 0.01f),
                )
            }
        }
    }
}

/** 最近记录卡：缩略图 + 页数角标 + 文件名 + 时间 */
@Composable
fun RecordCard(
    record: OcrRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 148.dp,
    thumbHeight: Dp = 112.dp,
) {
    val c = Ocr.colors
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick),
    ) {
        Box {
            PaperThumb(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight),
                accent = true,
                imagePath = record.scan.firstOrNull()?.imagePath,
            )
            // 页数徽章：深色胶囊，只是信息不是强调
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(c.scrim.copy(alpha = 0.82f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${record.pages} 页",
                    style = OcrType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = c.onScrim,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = record.title,
            style = OcrType.BodySm,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = record.time,
            style = OcrType.Caption.copy(fontSize = 12.5.sp),
            color = c.muted,
        )
    }
}

/** 空状态：一块柔和底 + 文档线描 + 两句引导文案 */
@Composable
fun EmptyRecords(modifier: Modifier = Modifier) {
    val c = Ocr.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(Radii.xl))
                    .background(c.surfaceSoft)
                    .border(1.dp, c.hairline, RoundedCornerShape(Radii.xl)),
                contentAlignment = Alignment.Center,
            ) {
                IconDoc(c.muted.copy(alpha = 0.85f), size = 38.dp, stroke = 1.5.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text(text = "还没有识别记录", style = OcrType.DisplaySm, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(text = "拍一张试卷，开始识别吧", style = OcrType.BodySm, color = c.muted)
        }
    }
}

/** 区块标题：左边中文标题，右边一个珊瑚色"查看全部 ›" */
@Composable
fun SectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Ocr.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = OcrType.TitleMd, color = c.ink)
        Spacer(Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.sm))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = actionText, style = OcrType.Caption, color = c.primary)
                Spacer(Modifier.width(2.dp))
                IconChevronRight(c.primary, size = 13.dp, stroke = 1.5.dp)
            }
        }
    }
}

/** 设置页的小节标签（法文大写那种"淡淡一行"） */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = Ocr.colors
    Text(
        text = text,
        style = OcrType.Label,
        color = c.mutedSoft,
        modifier = modifier.padding(start = 4.dp, top = Space.lg, bottom = Space.xs),
    )
}

/** 设置项容器：奶白卡 + 细边 + 行间发丝线 */
@Composable
fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    val c = Ocr.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(c.canvas)
            .border(1.dp, c.hairline, RoundedCornerShape(Radii.lg)),
        content = content,
    )
}

/** 行内发丝线（左边留出文字缩进，看起来才像"表格行"） */
@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = Space.md),
        thickness = 1.dp,
        color = Ocr.colors.hairlineSoft,
    )
}

@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = OcrType.BodySm,
            color = if (danger) c.danger else c.ink,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(text = value, style = OcrType.Caption, color = c.muted)
            Spacer(Modifier.width(6.dp))
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            IconChevronRight(c.mutedSoft, size = 14.dp, stroke = 1.5.dp)
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = OcrType.BodySm, color = c.ink)
            if (hint != null) {
                Text(text = hint, style = OcrType.Caption.copy(fontSize = 12.5.sp), color = c.mutedSoft)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                checkedBorderColor = c.primary,
                uncheckedThumbColor = c.canvas,
                uncheckedTrackColor = c.creamStrong,
                uncheckedBorderColor = c.hairline,
            ),
        )
    }
}

/** 主按钮：珊瑚填充、8dp 圆角、44dp 高 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (Color) -> Unit = {},
) {
    val c = Ocr.colors
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(c.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading(c.onPrimary)
        Text(text = text, style = OcrType.Button, color = c.onPrimary, maxLines = 1)
    }
}

/** 次按钮：描边、透明底 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (Color) -> Unit = {},
) {
    val c = Ocr.colors
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Radii.md))
            .border(1.dp, c.hairline, RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading(c.ink)
        Text(text = text, style = OcrType.Button, color = c.ink, maxLines = 1)
    }
}

/** 胶囊标签（badge-pill） */
@Composable
fun Pill(text: String, background: Color = Ocr.colors.surfaceCard, color: Color = Ocr.colors.muted) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = OcrType.Caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = color)
    }
}

/** 关闭按钮用的细化描边圈，暗底上才看得见 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Ocr.colors
    CircleIconButton(
        onClick = onClick,
        background = c.scrimElevated.copy(alpha = 0.92f),
        borderColor = c.onScrim.copy(alpha = 0.16f),
        size = 40.dp,
    ) { content() }
}

/** 一个小圆点，用来在页脚分隔说明文字 */
@Composable
fun Dot(alpha: Float = 0.4f) {
    val color = Ocr.colors.muted
    Canvas(Modifier.size(3.dp)) {
        drawCircle(color.copy(alpha = alpha))
    }
}

/** 缩略图采样解码：和结果页那套逻辑一样，只是目标尺寸更小 */
private fun decodeThumb(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
