package com.doclayout.recognize.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.doclayout.recognize.BuildConfig
import com.doclayout.recognize.data.model.OcrRecord
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 反馈通道（data 层，无 Compose）。
 *
 * 1. 属于什么文件
 *    - `data/local/FeedbackClient.kt`；UI 在 `ui/screen/OcrFeedbackScreen.kt`。
 * 2. 用了什么组件
 *    - `java.util.zip`（反馈包打包）；HttpURLConnection（GitHub Issues API）；
 *      FileProvider（附件授权，路径见 res/xml/file_paths.xml 的 feedback/ 条目）。
 * 3. 职责与边界 —— 三条通道
 *    - **GitHub Issue**：直连 api.github.com，只收文本（Issue API 传不了二进制附件，
 *      识别文本内联进正文）。国内网络不可达时快速失败（10s 超时），UI 引导走备用。
 *      Token 来自 local.properties 的 feedback.token（BuildConfig 注入，仓库无明文），
 *      未配置则该通道直接禁用。
 *    - **邮件**（国内备用首选）：zip 附件 + 正文交给系统邮件 App 发送——应用本身
 *      不需要能访问 GitHub/Google，由用户的邮箱客户端完成投递。
 *    - **导出反馈包**（终极备用）：zip 走系统分享面板，微信/QQ/网盘任选。
 * 4. 反馈包结构：feedback.json（类型/描述/联系方式/环境信息）+ 识别问题时附
 *    `原图-p{n}.jpg` 与 `识别结果-p{n}.md`（来自 filesDir/ocr/<记录id>/）。
 */
object FeedbackClient {

    private const val GITHUB_API = "https://api.github.com/repos/"
    private const val FEEDBACK_DIR = "feedback"

    /** 反馈类型：体验问题 / 识别问题（识别问题自动附原图与识别记录） */
    enum class Type { Experience, Recognition }

    /** GitHub 通道是否可用（Token 已配置） */
    val githubEnabled: Boolean get() = BuildConfig.FEEDBACK_TOKEN.isNotBlank()

    /** 接收邮箱是否已配置（未配置时邮件通道用系统选择器由用户自己填收件人） */
    val feedbackEmail: String get() = BuildConfig.FEEDBACK_EMAIL

    private fun feedbackDir(context: Context): File =
        File(context.filesDir, FEEDBACK_DIR).apply { mkdirs() }

    /**
     * 组装反馈 zip 并落盘（IO 线程）。识别问题自动带原图 + 识别文本。
     * 返回 zip 文件；描述为空会抛 IllegalStateException（UI 先行校验，这里兜底）。
     */
    suspend fun buildZip(
        context: Context,
        type: Type,
        description: String,
        contact: String,
        record: OcrRecord?,
    ): File = withContext(Dispatchers.IO) {
        require(description.isNotBlank()) { "请先填写问题描述" }
        val meta = JSONObject().apply {
            put("type", if (type == Type.Recognition) "识别问题" else "体验问题")
            put("description", description)
            put("contact", contact)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("device", android.os.Build.MODEL)
            put("android", "API ${android.os.Build.VERSION.SDK_INT}")
            put("recordId", record?.id ?: "")
            put("recordTitle", record?.title ?: "")
            put("recordEngine", record?.engine ?: "")
            put("recordPages", record?.pages ?: 0)
            put("recordDuration", record?.duration ?: "")
        }
        val zip = File(feedbackDir(context), "feedback_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("feedback.json"))
            out.write(meta.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()
            // 识别问题：原图 + 识别文本（OcrStore 布局 filesDir/ocr/<id>/p{n}.jpg|p{n}.md）
            if (type == Type.Recognition && record != null) {
                val dir = File(File(context.filesDir, "ocr"), record.id)
                for (n in 1..record.pages) {
                    File(dir, "p$n.jpg").takeIf { it.isFile }?.let { f ->
                        out.putNextEntry(ZipEntry("原图-p$n.jpg"))
                        FileInputStream(f).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    File(dir, "p$n.md").takeIf { it.isFile }?.let { f ->
                        out.putNextEntry(ZipEntry("识别结果-p$n.md"))
                        FileInputStream(f).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
        zip
    }

    /**
     * 直连提交 GitHub Issue（文本通道）。成功返回 issue URL。
     * 网络不可达 / 未配置 Token / GitHub 拒绝时抛异常，文案可直接展示，
     * UI 捕获后引导用户走邮件或导出反馈包。
     */
    suspend fun submitGithubIssue(
        context: Context,
        type: Type,
        description: String,
        contact: String,
        record: OcrRecord?,
    ): String = withContext(Dispatchers.IO) {
        if (!githubEnabled) throw IllegalStateException("未配置反馈 Token（GitHub 通道停用），请走邮件或导出反馈包")
        // 识别文本内联进正文（Issue API 传不了二进制，原图走邮件/导出通道）
        val recognitionText = StringBuilder()
        if (type == Type.Recognition && record != null) {
            val dir = File(File(context.filesDir, "ocr"), record.id)
            for (n in 1..record.pages) {
                File(dir, "p$n.md").takeIf { it.isFile }
                    ?.let { runCatching { it.readText().take(4000) }.getOrNull() }
                    ?.let { recognitionText.append("\n\n--- 第 $n 页识别结果 ---\n").append(it) }
            }
        }
        val typeLabel = if (type == Type.Recognition) "识别问题" else "体验问题"
        val body = buildString {
            append("## 类型\n$typeLabel\n\n## 描述\n$description")
            if (contact.isNotBlank()) append("\n\n## 联系方式\n$contact")
            append("\n\n## 环境\n- 版本：${BuildConfig.VERSION_NAME}\n- 设备：${android.os.Build.MODEL}（API ${android.os.Build.VERSION.SDK_INT}）")
            if (record != null) {
                append("\n- 记录：${record.title} · ${record.engine} · ${record.pages} 页 · ${record.duration}")
            }
            if (recognitionText.isNotEmpty()) {
                append("\n\n<details><summary>识别结果（正文内联，原图请走邮件/导出通道）</summary>")
                append(recognitionText)
                append("\n</details>")
            }
        }
        val payload = JSONObject().apply {
            put("title", "[反馈][$typeLabel] ${description.lineSequence().firstOrNull()?.take(30) ?: ""}")
            put("body", body)
            put("labels", org.json.JSONArray().put("app-feedback"))
        }
        val conn = URL("$GITHUB_API${BuildConfig.FEEDBACK_REPO}/issues").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000   // 国内不可达时快速失败，引导走备用通道
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.FEEDBACK_TOKEN}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            val out = conn.outputStream
            out.write(body)
            out.flush()
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code != 201) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw IllegalStateException("GitHub 提交失败（HTTP $code${if (msg.isNotBlank()) "：$msg" else ""}）——请改用邮件或导出反馈包")
            }
            JSONObject(text).getString("html_url")
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("连不上 GitHub（国内网络常见）——请改用邮件或导出反馈包", e)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连不上 GitHub——请改用邮件或导出反馈包", e)
        } finally {
            conn.disconnect()
        }
    }

    /** zip 的内容 Uri（FileProvider 授权，可分享/做附件） */
    fun zipUri(context: Context, zip: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)

    /**
     * 邮件通道（国内备用首选）：zip 附件 + 正文交给系统邮件 App。
     * 应用不需要任何网络权限——投递由用户的邮箱客户端完成。
     */
    fun mailIntent(context: Context, zip: File, subject: String, body: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri(context, zip))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (feedbackEmail.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 导出通道（终极备用）：系统分享面板，微信 / QQ / 网盘任选 */
    fun shareIntent(context: Context, zip: File): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, zipUri(context, zip))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
