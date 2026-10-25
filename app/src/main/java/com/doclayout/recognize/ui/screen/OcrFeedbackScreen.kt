package com.doclayout.recognize.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.local.FeedbackClient
import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 反馈页（三通道）。
 *
 * - **GitHub Issue**：直连提交（只收文本，识别结果内联正文；原图走另外两条通道）。
 *   国内网络不可达时这里会快速失败并提示走备用——这正是设计内的行为。
 * - **邮件发送**（国内备用首选）：完整 zip 附件（描述 + 原图 + 识别结果）交给
 *   系统邮件 App，应用自身不需要能访问外网。
 * - **导出反馈包**（终极备用）：zip 走系统分享面板，微信 / QQ / 网盘任选。
 *
 * 识别问题反馈从结果页进入（带 [record]），附件自动包含该记录的原图与识别文本；
 * 从设置进入是体验反馈（record 为 null，类型可切换）。
 */
@Composable
fun OcrFeedbackScreen(
    onBack: () -> Unit,
    /** 预选类型；从结果页进入时为识别问题 */
    initialType: FeedbackClient.Type = FeedbackClient.Type.Experience,
    /** 随附的识别记录（null = 纯体验反馈） */
    record: OcrRecord? = null,
) {
    val c = Ocr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(if (record != null) FeedbackClient.Type.Recognition else initialType) }
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var packaging by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hintText) { if (hintText != null) { delay(4000); hintText = null } }

    // 附件预览：识别记录的原图缩略（第一页）
    val thumb by produceState<Bitmap?>(null, record?.id) {
        val r = record ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val path = File(File(context.filesDir, "ocr"), r.id).resolve("p1.jpg").absolutePath
                BitmapFactory.decodeFile(path)?.let { bmp ->
                    val s = 320f / maxOf(bmp.width, bmp.height)
                    Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                }
            }.getOrNull()
        }
    }

    fun openChooser(intent: Intent, title: String) {
        runCatching {
            context.startActivity(Intent.createChooser(intent, title))
        }.onFailure { hintText = it.message ?: "没有可处理该分享的应用" }
    }

    fun withZip(action: (File) -> Unit) {
        if (description.isBlank()) { hintText = "请先填写问题描述"; return }
        scope.launch {
            packaging = true
            val zip = runCatching {
                FeedbackClient.buildZip(context, type, description, contact, record)
            }.onFailure {
                packaging = false
                hintText = "打包反馈失败：${it.message}"
                return@launch
            }.getOrNull()
            packaging = false
            if (zip != null) action(zip)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .imePadding(),
    ) {
        OcrTopBar(title = "反馈", subtitle = "体验问题或识别问题都会被认真对待", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg),
        ) {
            Spacer(Modifier.height(Space.md))

            // ── 类型（带记录时锁定识别问题：附件才有意义）
            Text("反馈类型", style = OcrType.Caption, color = c.muted)
            Spacer(Modifier.height(Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TypeChip("体验问题", type == FeedbackClient.Type.Experience, record == null) {
                    type = FeedbackClient.Type.Experience
                }
                TypeChip("识别问题", type == FeedbackClient.Type.Recognition, record == null) {
                    type = FeedbackClient.Type.Recognition
                }
            }

            Spacer(Modifier.height(Space.md))

            // ── 附件预览（识别问题）：原图缩略 + 记录摘要
            if (type == FeedbackClient.Type.Recognition) {
                if (record != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.md))
                            .background(c.surfaceCard)
                            .padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        thumb?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "原图缩略",
                                modifier = Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(Radii.sm)),
                            )
                        } ?: Box(
                            Modifier.size(width = 72.dp, height = 96.dp)
                                .clip(RoundedCornerShape(Radii.sm)).background(c.hairline),
                        )
                        Spacer(Modifier.width(Space.md))
                        Column {
                            Text(record.title, style = OcrType.Body, color = c.ink, maxLines = 1)
                            Text(
                                "${record.pages} 页 · ${record.engine} · ${record.duration}",
                                style = OcrType.Caption.copy(fontSize = 12.sp), color = c.muted,
                            )
                            Text(
                                "附件：原图 + 识别结果（随邮件/导出通道发送）",
                                style = OcrType.Caption.copy(fontSize = 11.sp), color = c.mutedSoft,
                            )
                        }
                    }
                } else {
                    Text(
                        "（从识别记录页进入时可附原图与识别结果）",
                        style = OcrType.Caption.copy(fontSize = 12.sp), color = c.mutedSoft,
                    )
                }
                Spacer(Modifier.height(Space.md))
            }

            // ── 描述
            Text("问题描述 *", style = OcrType.Caption, color = c.muted)
            Spacer(Modifier.height(Space.xs))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = {
                    Text(
                        if (type == FeedbackClient.Type.Recognition) "哪一页的哪里识别错了？期望是什么样？" else "用着哪里不顺手、哪里看不懂？",
                        style = OcrType.Body, color = c.mutedSoft,
                    )
                },
                textStyle = OcrType.Body.copy(color = c.ink),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.primary,
                    unfocusedBorderColor = c.hairline,
                    focusedContainerColor = c.paper,
                    unfocusedContainerColor = c.paper,
                ),
            )

            Spacer(Modifier.height(Space.md))

            // ── 联系方式（可选）
            Text("联系方式（可选）", style = OcrType.Caption, color = c.muted)
            Spacer(Modifier.height(Space.xs))
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("邮箱或微信号，方便回访", style = OcrType.Body, color = c.mutedSoft) },
                textStyle = OcrType.Body.copy(color = c.ink),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.primary,
                    unfocusedBorderColor = c.hairline,
                    focusedContainerColor = c.paper,
                    unfocusedContainerColor = c.paper,
                ),
            )

            Spacer(Modifier.height(Space.lg))

            // ── 通道一：GitHub Issue（直连，文本）
            ChannelButton(
                text = if (submitting) "提交 GitHub 中…" else "提交 GitHub Issue",
                primary = true,
                enabled = !submitting && !packaging && FeedbackClient.githubEnabled && description.isNotBlank(),
            ) {
                scope.launch {
                    submitting = true
                    runCatching {
                        FeedbackClient.submitGithubIssue(context, type, description, contact, record)
                    }.onSuccess { url ->
                        hintText = "已提交：$url（识别文本已内联；原图请再走邮件/导出发一次）"
                    }.onFailure { e ->
                        hintText = e.message ?: "提交失败"
                    }
                    submitting = false
                }
            }
            if (!FeedbackClient.githubEnabled) {
                Text(
                    "GitHub 通道未配置 Token（或国内网络不可达），请走下面两条通道",
                    style = OcrType.Caption.copy(fontSize = 11.sp), color = c.mutedSoft,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(Space.sm))

            // ── 通道二：邮件（国内备用首选，完整附件）
            ChannelButton(
                text = if (packaging) "打包中…" else "邮件发送（含原图与识别结果）",
                enabled = !submitting && !packaging,
            ) {
                withZip { zip ->
                    openChooser(
                        FeedbackClient.mailIntent(
                            context, zip,
                            subject = "[试卷识别反馈] ${description.lineSequence().firstOrNull()?.take(20) ?: ""}",
                            body = description + if (contact.isNotBlank()) "\n\n联系方式：$contact" else "",
                        ),
                        "选择邮件应用",
                    )
                }
            }

            Spacer(Modifier.height(Space.sm))

            // ── 通道三：导出反馈包（终极备用，微信/QQ/网盘）
            ChannelButton(
                text = "导出反馈包（微信 / QQ 等分享）",
                enabled = !submitting && !packaging,
            ) {
                withZip { zip -> openChooser(FeedbackClient.shareIntent(context, zip), "分享反馈包") }
            }

            Spacer(Modifier.height(Space.xl))
        }

        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            if (submitting || packaging) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.primary)
                    Text("处理中…", style = OcrType.Caption.copy(fontSize = 12.sp), color = c.muted)
                }
            } else {
                hintText?.let { Text(it, style = OcrType.Caption.copy(fontSize = 12.sp), color = c.danger) }
            }
        }
    }
}

@Composable
private fun TypeChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Ocr.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    !enabled -> c.surfaceCard.copy(alpha = 0.5f)
                    selected -> c.primary.copy(alpha = 0.14f)
                    else -> c.surfaceCard
                },
            )
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = OcrType.Caption,
            color = when {
                !enabled -> c.mutedSoft
                selected -> c.primary
                else -> c.muted
            },
        )
    }
}

@Composable
private fun ChannelButton(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Ocr.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(
                when {
                    !enabled -> c.surfaceCard.copy(alpha = 0.5f)
                    primary -> c.primary.copy(alpha = 0.12f)
                    else -> c.surfaceCard
                },
            )
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OcrType.Body,
            color = if (!enabled) c.mutedSoft else if (primary) c.primary else c.ink,
        )
    }
}
