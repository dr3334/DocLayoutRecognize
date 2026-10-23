package com.doclayout.recognize.ui.screen

import android.graphics.BitmapFactory
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

    LaunchedEffect(playKey, data) {
        doneText = ""
        typing = ""
        activeIndex = -1
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
            acc = if (acc.isEmpty()) md else "$acc\n\n$md"
            doneText = acc
            typing = ""
        }
        activeIndex = -1
    }

    val shown = when {
        typing.isEmpty() -> doneText
        doneText.isEmpty() -> typing
        else -> "$doneText\n\n$typing"
    }
    val activeBlock = ordered.getOrNull(activeIndex)

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

                val bbox = activeBlock?.bbox
                if (bbox != null && bbox.size == 4) {
                    Canvas(Modifier.matchParentSize()) {
                        val w = (bbox[2] - bbox[0]) * scale
                        val h = (bbox[3] - bbox[1]) * scale
                        drawRect(
                            color = c.primary,
                            topLeft = Offset(dx + bbox[0] * scale, dy + bbox[1] * scale),
                            size = Size(w.coerceAtLeast(1f), h.coerceAtLeast(1f)),
                            style = Stroke(width = 2.dp.toPx()),
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
                autoScrollBottom = true,
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
