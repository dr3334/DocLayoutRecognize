package com.doclayout.recognize.data.remote

import com.doclayout.recognize.data.local.OcrMode
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * PaddleOCR-VL 1.6 云端识别（百度星河社区 AI Studio 异步任务接口）。
 *
 * 为什么用异步而不是 /layout-parsing 同步接口：
 *   同步接口的地址（https://xxxx.aistudio-app.com/layout-parsing）是**每个账号一份**的，
 *   换个人跑就得改代码；异步接口的域名是固定的，只要一个 Access Token。
 *   代价是要多两步：提交拿到 jobId，再轮询到 done，最后去 BOS 预签名地址取结果。
 *
 * 三步走：
 *   1. POST {BASE}/api/v2/ocr/jobs      multipart（file + model + optionalPayload）→ jobId
 *   2. GET  {BASE}/api/v2/ocr/jobs/{id} 轮询，state=done 后给 resultUrl.jsonUrl
 *   3. GET  jsonUrl                      取 result.layoutParsingResults[0].markdown
 *
 * jsonUrl 是预签名地址，**不要再带 Authorization**：带了反而会被 BOS 判成签名冲突。
 */
object OcrVlApi {

    /** 云端：星河社区异步任务接口 */
    const val DEFAULT_BASE = "https://paddleocr.aistudio-app.com"

    /** 本地：自建 PaddleX 服务（paddlex --serve），默认端口 8080 */
    const val DEFAULT_LOCAL_BASE = "http://127.0.0.1:8080"

    const val MODEL = "PaddleOCR-VL-1.6"

    /**
     * multipart 里的 optionalPayload。
     *
     * 字段写入和 Content-Length 计算必须引用同一份 —— 手写 boundary 时长度算错，
     * 服务端会直接拒收（400 或连接被重置），是最难查的一类 bug。
     * 三项都写全：少字段服务端报 10008 参数错误。
     */
    private const val OPTIONAL_PAYLOAD =
        """{"useDocOrientationClassify":true,"useDocUnwarping":true,"useChartRecognition":false}"""

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000
    private const val POLL_INTERVAL = 1_200L
    private const val POLL_TIMEOUT = 150_000L

    /** 一页的识别结果 */
    data class VlPage(
        /** 已把文内裁图换好地址的 Markdown */
        val markdown: String,
        /** 服务端耗时（毫秒），记录里显示 "4.1 s" */
        val elapsedMs: Long,
        /** 页数，PDF 时可能 > 1 */
        val pages: Int,
    )

    class VlException(message: String) : Exception(message)

    /**
     * 识别一张图。挂起函数，调用方按顺序一张张传 —— 并发虽然快，
     * 但"第 3 张传完了"这种进度会乱跳，免费额度下并发容易撞限流。
     */
    suspend fun parse(
        mode: OcrMode,
        baseUrl: String,
        token: String,
        image: ByteArray,
        model: String = MODEL,
    ): VlPage = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val page = when (mode) {
            OcrMode.Cloud -> {
                require(token.isNotBlank()) { "云端模式需要 Access Token（设置 → 识别服务）" }
                val base = normalizeBase(baseUrl.ifBlank { DEFAULT_BASE })
                val jobId = submit(base, token, image, model)
                fetchResult(poll(base, token, jobId))
            }

            OcrMode.Local -> parseLocal(baseUrl, token, image)

            // 端侧模式不走任何 HTTP：真分流在 OcrApp.runScan（WP5 起 mode==OnDevice 时逐页走
            // LocalOcrEngine.recognizeFile，根本不会进 parse）。这里保留兜底拦截，
            // 防止未来新调用点误把端侧模式当 HTTP 打出去。
            OcrMode.OnDevice -> throw VlException(
                "端侧模式不使用 HTTP 服务：请先在「端侧模型」页完成下载，识别走本地流水线（导入文件流程）",
            )
        }
        page.copy(elapsedMs = System.currentTimeMillis() - started)
    }

    /**
     * 文件版重载：读文件（数 MB 的磁盘 IO）发生在 Dispatchers.IO 里。
     * 调用方若在主线程协程内联实参 [parse]（ByteArray 版），withContext 包不到
     * 求值过程，等于每页在主线程整读一次文件 —— 传 File 走这个版本。
     */
    suspend fun parse(
        mode: OcrMode,
        baseUrl: String,
        token: String,
        imageFile: java.io.File,
        model: String = MODEL,
    ): VlPage {
        val image = withContext(Dispatchers.IO) { imageFile.readBytes() }
        return parse(mode, baseUrl, token, image, model)
    }

    /**
     * 本地 PaddleX 服务：一步出结果，没有 jobId / 轮询。
     *
     *   POST {base}/layout-parsing
     *   {"file":"<base64>","fileType":1,"useDocOrientationClassify":true,...}
     *   → {"result":{"layoutParsingResults":[{"markdown":{"text":..,"images":{..}}}]}}
     *
     * 和云端的三点差别，都会咬人：
     *   1. 本地默认是 **http**（127.0.0.1:8080 / 192.168.x.x:8080），Android 9+ 默认禁明文，
     *      必须在 manifest 里开 usesCleartextTraffic（见 AndroidManifest 注释）
     *   2. 本地服务默认**不鉴权**，token 可以为空；填了就带上（有些团队会套一层网关）
     *   3. `markdown.images` 的值默认是 **base64**（云端是预签名 URL），
     *      所以 rewriteImages 里那条 base64 → data URI 的分支是给本地用的
     */
    private fun parseLocal(baseUrl: String, token: String, image: ByteArray): VlPage {
        val base = baseUrl.ifBlank { DEFAULT_LOCAL_BASE }.trimEnd('/')
        val url = if (base.endsWith("/layout-parsing")) base else "$base/layout-parsing"

        val payload = JSONObject().apply {
            put("file", Base64.encodeToString(image, Base64.NO_WRAP))
            put("fileType", 1)
            put("useDocOrientationClassify", true)
            put("useDocUnwarping", true)
            put("useChartRecognition", false)
        }.toString()

        val conn = open(url, token.ifBlank { null }).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        return parseResult(readBody(conn))
    }

    // ---------- 云端：1. 提交任务 ----------

    private fun submit(base: String, token: String, image: ByteArray, model: String): String {
        val boundary = "----OcrVlBoundary" + System.currentTimeMillis()
        val conn = open("$base/api/v2/ocr/jobs", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // 大图上传慢，写超时和读超时分开给
            setFixedLengthStreamingMode(
                multipartLength(boundary, image.size, model),
            )
        }

        conn.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write(
                    ("--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                        "$value\r\n").toByteArray(Charsets.UTF_8),
                )
            }
            field("model", model)
            // 手机拍的卷子十有八九是歪的，方向分类 + 去畸变默认开；
            // 图表识别不开（试卷里用不上，开着还慢）。三项写全 —— 少字段服务端报 10008。
            field("optionalPayload", OPTIONAL_PAYLOAD)
            out.write(
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"page.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8),
            )
            out.write(image)
            out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }

        val body = readBody(conn)
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) {
            throw VlException("提交失败：${json.optString("msg", body.take(160))}")
        }
        return json.optJSONObject("data")?.optString("jobId").orEmpty()
            .ifBlank { throw VlException("服务端没返回 jobId：${body.take(160)}") }
    }

    /** multipart 的字节总数：手写 boundary 时必须自己算，否则 chunked 模式下服务端可能拒收 */
    private fun multipartLength(boundary: String, imageSize: Int, model: String): Long {
        fun part(head: String, body: Int) = head.toByteArray(Charsets.UTF_8).size.toLong() + body
        val modelPart = part(
            "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n",
            0,
        )
        val payloadPart = part(
            "--$boundary\r\nContent-Disposition: form-data; name=\"optionalPayload\"\r\n\r\n$OPTIONAL_PAYLOAD\r\n",
            0,
        )
        val fileHead = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
            "filename=\"page.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8).size.toLong()
        return modelPart + payloadPart + part(fileHead, imageSize) + tail
    }

    // ---------- 2. 轮询 ----------

    private suspend fun poll(base: String, token: String, jobId: String): String {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val body = withContext(Dispatchers.IO) {
                readBody(open("$base/api/v2/ocr/jobs/$jobId", token).apply { requestMethod = "GET" })
            }
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw VlException("查询任务失败：${json.optString("msg", body.take(160))}")
            }
            val data = json.optJSONObject("data") ?: throw VlException("任务返回里没有 data")
            when (data.optString("state")) {
                "done" -> return data.optJSONObject("resultUrl")?.optString("jsonUrl").orEmpty()
                    .ifBlank { throw VlException("任务完成但没有结果地址") }

                "failed", "error" -> throw VlException(
                    data.optString("errorMsg").ifBlank { "识别任务失败（state=failed）" },
                )

                else -> delay(POLL_INTERVAL) // pending / running
            }
        }
        throw VlException("识别超时（${POLL_TIMEOUT / 1000}s）。图太大或服务排队，稍后再试。")
    }

    // ---------- 3. 取结果 ----------

    private fun fetchResult(jsonUrl: String): VlPage {
        // 预签名地址自带鉴权，这里**不带** Authorization
        val body = readBody(open(jsonUrl, token = null).apply { requestMethod = "GET" })
        return parseResult(body)
    }

    /**
     * 解析结果。**注意 jsonUrl 返回的是 JSONL，不是一个大 JSON** ——
     * 一行一条任务结果，PDF 多页时会有多行；只按 JSONObject(body) 解析会直接抛异常。
     * （单页图片时恰好只有一行，所以"能用"和"正确"在这里差得很近，容易踩。）
     */
    private fun parseResult(body: String): VlPage {
        val lines = body.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw VlException("结果是空的")

        val sb = StringBuilder()
        var pageCount = 0
        var parsedAny = false

        for (line in lines) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optInt("errorCode", 0) != 0) {
                throw VlException(json.optString("errorMsg", "errorCode=${json.optInt("errorCode")}"))
            }
            val result = json.optJSONObject("result") ?: continue
            val pages = result.optJSONArray("layoutParsingResults") ?: continue
            parsedAny = true

            for (i in 0 until pages.length()) {
                val md = pages.getJSONObject(i).optJSONObject("markdown") ?: continue
                val text = md.optString("text", "")
                if (text.isBlank()) continue
                if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
                sb.append(rewriteImages(text, md.optJSONObject("images")))
                pageCount++
            }
        }

        if (!parsedAny) throw VlException("返回里没有 result.layoutParsingResults")
        if (sb.isEmpty()) throw VlException("识别结果没有文字（可能是空白页或图片太小）")

        return VlPage(markdown = sb.toString(), elapsedMs = 0, pages = pageCount)
    }

    /**
     * 把 Markdown 里引用的裁图路径换掉。
     *
     * 服务端返回两种东西：
     *   1. `images` 里是 BOS 预签名 URL —— 直接用，WebView 能自己加载
     *   2. `images` 里是 base64 —— 拼成 data URI 内联，图片就跟着 markdown 走
     *
     * 而且引用写法有两种：`![](imgs/x.jpg)` 和 `<img src="imgs/x.jpg">`。
     * PaddleOCR-VL 实际输出的是后者（HTML img），只替换 markdown 语法会漏掉所有图。
     */
    private fun rewriteImages(text: String, images: JSONObject?): String {
        if (images == null || images.length() == 0) return text
        var out = text
        val keys = images.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val raw = images.optString(path, "")
            if (raw.isBlank()) continue
            val target = if (raw.startsWith("http", true)) {
                raw
            } else {
                "data:image/jpeg;base64," + raw.replace("\n", "").replace("\r", "")
            }
            out = out
                .replace("src=\"$path\"", "src=\"$target\"")
                .replace("src='$path'", "src='$target'")
                .replace("](./$path)", "]($target)")
                .replace("]($path)", "]($target)")
        }
        return normalizeHtml(out)
    }

    /**
     * 把行内 HTML 规整成纯 Markdown。
     *
     * PaddleOCR-VL 输出的图片是 `<div style="text-align:center"><img src="..." width="35%"></div>`，
     * 而 render.html 里 markdown-it 没开 html 选项（专题A 出于安全没开），
     * 这些标签会被**转义成文字**显示出来 —— 屏幕上就是一大段 <div style="..."> 源码。
     * 所以图片必须转成 `![](url)`，纯排版标签（div/center/span/p）直接剥掉。
     */
    private fun normalizeHtml(markdown: String): String {
        var s = markdown
        // <img src="X" ...> / <img alt=".." src='X'> → ![](X)
        s = Regex("""<img\b[^>]*?src\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            .replace(s) { "![](${it.groupValues[1]})" }
        // 只做居中/换行用的容器标签，去掉不影响语义
        s = Regex("""</?(div|center|span|p)\b[^>]*>""", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        // 去标签后会留下空行堆叠，压回最多一个空行
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        return s.trim()
    }

    // ---------- HTTP 小工具 ----------

        /**
     * 把用户填的地址规整成根地址。
     *
     * 设置页提示写的是"接口根地址"，但总有人把官方示例里的完整 URL
     * （https://paddleocr.aistudio-app.com/api/v2/ocr/jobs）整条粘进来。
     * 直接拼会变成 .../api/v2/ocr/jobs/api/v2/ocr/jobs → 404，
     * 而报错只说 HTTP 404，很难联想到是地址多了尾巴。
     */
    private fun normalizeBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        for (s in listOf("/api/v2/ocr/jobs", "/layout-parsing")) {
            if (b.endsWith(s, ignoreCase = true)) b = b.dropLast(s.length)
        }
        return b.trimEnd('/').ifBlank { DEFAULT_BASE }
    }

private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            if (token != null) {
                // 认证方案的大小写不敏感（RFC 7235），官方示例写的是小写 `bearer`，这里用 Bearer，
                // 两种 Token 都能过：星河社区的 Access Token、千帆的 API Key（bce-v3 开头）。
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

    private fun readBody(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw VlException("HTTP $code：${extractError(text)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun extractError(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(160)
        return json.optString("msg").ifBlank {
            json.optString("errorMsg").ifBlank {
                json.optString("error_msg").ifBlank { body.take(160) }
            }
        }
    }
}
