package com.doclayout.recognize.ui.render

import com.doclayout.recognize.ui.theme.Ocr
import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** 纯内存查缓存，不触发解析 —— 组合期可以安全调，未命中交给 produceState 后台补 */
    fun getIfPresent(tex: String, sizePx: Float, colorArgb: Int): RaTeXRenderer? =
        tex.trim().takeIf { it.isNotEmpty() }?.let { cache.get("$sizePx|$colorArgb|$it") }
}

/**
 * RaTeX 字体预加载单例：ensureLoaded 要读字体文件（磁盘 IO），不能留在组合期做。
 * ready 是全局 State —— 一处预加载成功，所有 OcrTex 同帧拿到 fontsReady=true。
 */
private object RaTeXFonts {
    val ready = mutableStateOf(false)

    fun preload(context: Context) {
        if (ready.value) return
        runCatching { RaTeXFontLoader.ensureLoaded(context.applicationContext) }
            .onSuccess { ready.value = true }
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
    // 加载（磁盘 IO）挪到 LaunchedEffect+IO，这里只读全局就绪态
    val fontsReady by remember { RaTeXFonts.ready }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { RaTeXFonts.preload(context) } }

    // 缓存命中直接同步用（无挂起开销）；未命中走 produceState 在 Default 线程解析，
    // 期间走下面的 fallback 纯文本分支，解析完成无缝换 Canvas。
    // 结果页一屏多个公式行，解析（RaTeXEngine.parseBlocking）连续阻塞会拖慢首帧
    val initial = remember(tex, sizePx, color) {
        if (fontsReady) OcrTexCache.getIfPresent(tex, sizePx, color.toArgb()) else null
    }
    // 顶层直赋值写法：Compose lint 的 ProduceStateDoesNotAssignValue 有已知误报
    // （issuetracker 349411310/376491756），`value = x?.let{…}` 与 if 表达式赋值识别不了
    val renderer by produceState(initial, tex, sizePx, color, fontsReady) {
        if (!fontsReady || value != null) return@produceState
        val r = withContext(Dispatchers.Default) { OcrTexCache.get(tex, sizePx, color.toArgb()) }
        value = r
    }

    // 先读进局部值：renderer 是委托属性，Kotlin 不能对它 smart cast
    val resolved = renderer
    if (resolved == null) {
        Text(text = fallback ?: tex, fontSize = fontSize, color = color, modifier = modifier)
        return
    }

    val wPx = resolved.widthPx
    val hPx = resolved.totalHeightPx
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
                resolved.draw(nc)
                nc.restoreToCount(save)
            }
        }
    }
}
