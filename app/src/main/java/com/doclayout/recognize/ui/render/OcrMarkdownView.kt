package com.doclayout.recognize.ui.render

import com.doclayout.recognize.data.model.OcrScanPage
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Markdown 渲染：直接复用专题A 的 WebView + KaTeX 方案（assets/render.html）。
 *
 * 为什么识别结果不能再走 Compose 排版：
 * PP-OCR-VL 返回的是**一整段 Markdown**，里面有 `$...$` 行内公式、`$$...$$` 块级公式、
 * 表格（试卷里的成绩表、选择题选项表）、有序/无序列表。用 Compose 手写这些排版
 * 等于自己造一个 Markdown 引擎；而 render.html 里 markdown-it + KaTeX 已经把这活干完了，
 * 而且和专题A 是同一份文件 —— 教学页和产品页渲染同一段文本时结果完全一致。
 *
 * 三个必须照抄专题A 的点：
 *   1. loadUrl 只做一次（带 #app 让它别渲染内置 demo），之后内容一律走 evaluateJavascript
 *   2. 文本用 JSONObject.quote 转义再拼进 JS —— 换行和引号会直接把脚本打断
 *   3. WebView 是重对象，放在 remember 里复用；离开页面要 stopLoading + destroy
 *
 * 和专题A 的唯一区别：这里不做流式 diff，一次性 setMarkdown 整篇。
 * 识别结果本来就是一次到齐的，逐字推流只会让人以为还在识别。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OcrMarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Int = Color.parseColor("#1a1a1a"),
    /** streaming: scroll to bottom after each update */
    autoScrollBottom: Boolean = false,
) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            // 长按不要弹系统菜单：这里是只读结果页，选中反而挡住内容
            setOnCreateContextMenuListener { _, _, _ -> }
            addJavascriptInterface(HeightBridge { h ->
                // 高度回调先留给以后用（比如让 WebView 自适应高度嵌进滚动列表）
                Log.d("OcrMarkdown", "content height = $h")
            }, "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    ready = true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    // 公式写错时 KaTeX 会在 console 里报 ParseError，接出来便于排查
                    Log.d("OcrMarkdown", "[js] ${m.message()} (${m.lineNumber()})")
                    return true
                }
            }
            loadUrl("file:///android_asset/render.html#app")
        }
    }

    LaunchedEffect(markdown, ready) {
        if (ready) {
            val js = "setMarkdown(${JSONObject.quote(markdown)})" +
                if (autoScrollBottom) ";window.scrollTo(0, document.body.scrollHeight);" else ""
            webView.evaluateJavascript(js, null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (!ready) {
            Text(
                text = "正在加载 KaTeX 渲染器…",
                style = OcrType.Caption,
                color = Ocr.colors.muted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** JS → Kotlin 的唯一回调：内容高度。名字要和 render.html 里的 window.Android.onHeight 对上 */
private class HeightBridge(private val sink: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(h: Int) = sink(h)
}

/** 复制按钮用：把多页 markdown 拼成一份，页间用分隔线，方便直接粘到笔记里 */
fun List<OcrScanPage>.joinMarkdown(): String =
    mapIndexed { i, p ->
        if (size == 1) p.markdown.trim()
        else "## 第 ${i + 1} 页\n\n${p.markdown.trim()}"
    }.joinToString("\n\n---\n\n")
