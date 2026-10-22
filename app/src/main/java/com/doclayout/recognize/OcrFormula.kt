package com.doclayout.recognize

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer

/**
 * 公式渲染：RaTeX（Rust 排版 → display list → Canvas 矢量）。
 *
 * 为什么这里**不**用 WebView + KaTeX：
 *   WebView 要带一份 JS 引擎 + KaTeX 的 JS/CSS/字体，进程内存按几十 MB 起，
 *   首帧要等页面加载完，滚动时还要跨进程合成；而识别结果里公式只是"几行排版"，
 *   用不上 DOM 的能力。RaTeX 只把 TeX 解析成一组字形路径，直接在 Compose 的
 *   Canvas 上画，不产生 Bitmap、不加载网页，滚动和缩放都跟普通文字一样。
 *
 * 什么时候还是该用 WebView：需要在公式里嵌交互（可点击的引用、动画），
 * 或者已经有整套 KaTeX 主题/宏包定制 —— 那是"网页"的需求，不是"排版"的需求。
 */
private object OcrTexCache {
    private val cache = LruCache<String, RaTeXRenderer>(64)

    fun get(tex: String, sizePx: Float, colorArgb: Int): RaTeXRenderer? {
        val body = tex.trim()
        if (body.isEmpty()) return null
        val key = "$sizePx|$colorArgb|$body"
        cache.get(key)?.let { return it }
        return runCatching {
            val list = RaTeXEngine.parseBlocking(body, true, colorArgb)
            RaTeXRenderer(list, sizePx) { name -> RaTeXFontLoader.getTypeface(name) }
                .also { cache.put(key, it) }
        }.getOrNull()
    }
}

/**
 * 画一行 formula。
 *
 * @param fallback 解析失败时显示的纯文本（识别结果里宁可退化成文字，也不要红屏）
 */
@Composable
fun OcrTex(
    tex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    color: Color = Ocr.colors.ink,
    fallback: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }

    // 字体必须先注册进 RaTeX（它自己按名字取 Typeface）。
    // 少了这一步不会抛异常，只会画出"没有字形的公式"——只剩分数线，最容易被忽略。
    val fontsReady = remember {
        runCatching { RaTeXFontLoader.ensureLoaded(context) }.isSuccess
    }
    val renderer = remember(tex, sizePx, color, fontsReady) {
        if (fontsReady) OcrTexCache.get(tex, sizePx, color.toArgb()) else null
    }

    if (renderer == null) {
        Text(text = fallback ?: tex, fontSize = fontSize, color = color, modifier = modifier)
        return
    }

    val wPx = renderer.widthPx
    val hPx = renderer.totalHeightPx
    if (wPx <= 0f || hPx <= 0f) {
        Text(text = fallback ?: tex, fontSize = fontSize, color = color, modifier = modifier)
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availPx = with(density) { maxWidth.toPx() }
        // 太长的公式等比缩到可用宽度，不做横向滚动 —— 一屏里混着滚动条很难看
        val fit = if (availPx > 0f && wPx > availPx) availPx / wPx else 1f
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (hPx * fit).toDp() }),
        ) {
            val dx = (size.width - wPx * fit) / 2f
            drawIntoCanvas { compose ->
                val nc = compose.nativeCanvas
                val save = nc.save()
                nc.translate(dx, 0f)
                nc.scale(fit, fit)
                renderer.draw(nc)
                nc.restoreToCount(save)
            }
        }
    }
}
