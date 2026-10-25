package com.doclayout.recognize.ui.screen

import com.doclayout.recognize.data.local.OcrMode
import com.doclayout.recognize.data.remote.OcrVlApi
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.RowDivider
import com.doclayout.recognize.ui.component.SectionLabel
import com.doclayout.recognize.ui.component.SettingCard
import com.doclayout.recognize.ui.component.SettingRow
import com.doclayout.recognize.ui.component.SwitchRow
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Space
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.ui.theme.LocalOcrColors
import com.doclayout.recognize.ui.theme.OcrColors

/**
 * 设置页的「整页形态」：顶栏 + 内容。
 *
 * 现在 App 里的入口是 OcrApp 的右侧滑面板（OcrApp.kt 的面板 Box），面板本身不做
 * insets 让位，系统栏让位由本文件 OcrSettingsScreen 的 safeDrawingPadding 负责；
 * 这个整页形态保留着，因为 adb 调试参数（--ei ocr 1）和以后可能的平板分栏还要用它。
 *
 * 一个设置页最容易做丑的地方是"每行都一样重"。这里的办法是三层字重：
 *   小节标签（12sp / 弱化色 / 加宽字距）→ 行标题（14sp 墨色）→ 行值（13sp 弱化色）。
 * 视线先扫标签分组，再看组里的项，不需要分割线也很清楚。
 */
@Composable
fun OcrSettingsScreen(
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    recordCount: Int,
    mode: OcrMode,
    baseUrl: String,
    model: String,
    credential: String,
    /** 端侧模式就绪徽标：null=查询中（见 OcrApp 的轮询） */
    deviceReady: Boolean?,
    onModeChange: (OcrMode) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onClearRecords: () -> Unit,
    /** 「管理端侧模型」入口 → OcrRoute.Models */
    onOpenModels: () -> Unit,
    /** 「反馈与建议」入口 → OcrRoute.Feedback（体验反馈） */
    onFeedback: () -> Unit = {},
    onBack: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        OcrTopBar(title = "设置", onBack = onBack)
        OcrSettingsContent(
            modifier = Modifier.weight(1f),
            dark = dark,
            onDarkChange = onDarkChange,
            recordCount = recordCount,
            mode = mode,
            baseUrl = baseUrl,
            model = model,
            credential = credential,
            deviceReady = deviceReady,
            onModeChange = onModeChange,
            onBaseUrlChange = onBaseUrlChange,
            onCredentialChange = onCredentialChange,
            onClearRecords = onClearRecords,
            onOpenModels = onOpenModels,
            onFeedback = onFeedback,
            onExit = onExit,
        )
    }
}

/**
 * 设置项本体。
 *
 * 和上面那个整页版本的区别只有三点，缺一不可：
 *   1. 没有 [OcrTopBar] —— 顶栏属于"容器"，抽屉有自己的把手
 *   2. 不调 safeDrawingPadding() —— insets 由外层容器负责：整页/面板形态用
 *      [OcrSettingsScreen]（内含 safeDrawingPadding）包一层；直接复用本组件时，
 *      调用方必须自己补 insets padding，否则内容会顶进状态栏
 *   3. 不写 fillMaxSize() —— 高度交给容器决定（整页时是 weight，抽屉时按内容算）
 *
 * 抽出来的意义：设置项只有一份，页面和抽屉共用，不会出现"某天改了一边忘另一边"。
 */
@Composable
fun OcrSettingsContent(
    modifier: Modifier = Modifier,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    recordCount: Int,
    mode: OcrMode,
    baseUrl: String,
    model: String,
    credential: String,
    deviceReady: Boolean?,
    onModeChange: (OcrMode) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onClearRecords: () -> Unit,
    onOpenModels: () -> Unit,
    onFeedback: () -> Unit = {},
    onExit: () -> Unit,
) {
    val c = Ocr.colors
    // 正在编辑哪一项：null / "mode" / "url" / "token"（saveable：编辑中转屏弹窗不丢）
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    when (editing) {
        "mode" -> ModeChoiceDialog(
            current = mode,
            onSelect = { onModeChange(it); editing = null },
            onDismiss = { editing = null },
        )

        "url" -> EditValueDialog(
            title = "服务地址",
            initial = baseUrl,
            hint = "https://paddleocr.aistudio-app.com（异步任务接口根地址）",
            secret = false,
            onConfirm = onBaseUrlChange,
            onDismiss = { editing = null },
        )

        "token" -> EditValueDialog(
            title = "Access Token",
            initial = credential,
            hint = "星河社区 Access Token，或千帆 API Key（bce-v3 开头）",
            secret = true,
            onConfirm = onCredentialChange,
            onDismiss = { editing = null },
        )

        else -> Unit
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = Space.contentMax)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xl),
        ) {
            // ── 识别服务：这两项决定"扫描"能不能真的跑起来
            SectionLabel("识别服务")
            SettingCard {
                // 三选（云端 / 本地 / 端侧）改用对话框，不再二值翻转：
                // 三个选项循环点会绕晕，列表一眼可选。
                SettingRow(
                    label = "接口模式",
                    value = mode.label,
                    onClick = { editing = "mode" },
                )
                when (mode) {
                    OcrMode.Cloud -> {
                        RowDivider()
                        SettingRow(
                            label = "服务地址",
                            value = baseUrl.removePrefix("https://").removePrefix("http://"),
                            onClick = { editing = "url" },
                        )
                        RowDivider()
                        SettingRow(label = "模型", value = model)
                        RowDivider()
                        SettingRow(
                            label = "Access Token",
                            value = if (credential.isBlank()) "未配置" else maskToken(credential),
                            onClick = { editing = "token" },
                        )
                        RowDivider()
                        SettingRow(
                            label = "服务状态",
                            value = if (credential.isNotBlank()) "已配置" else "缺 Token",
                        )
                    }

                    OcrMode.Local -> {
                        RowDivider()
                        SettingRow(
                            label = "服务地址",
                            value = baseUrl.removePrefix("https://").removePrefix("http://"),
                            onClick = { editing = "url" },
                        )
                        RowDivider()
                        SettingRow(label = "服务状态", value = "本地直连（可留空 Token）")
                    }

                    // 端态没有地址/Token 的概念：隐藏这两行，改显就绪徽标 + 模型管理入口
                    OcrMode.OnDevice -> {
                        RowDivider()
                        SettingRow(
                            label = "端侧模型状态",
                            value = when (deviceReady) {
                                true -> "模型文件已就绪"
                                false -> "模型文件未就绪"
                                null -> "检查中…"
                            },
                        )
                        RowDivider()
                        SettingRow(
                            label = "管理端侧模型",
                            value = "下载 · 暂停 · 删除",
                            onClick = onOpenModels,
                        )
                        RowDivider()
                        SettingRow(
                            label = "服务状态",
                            value = if (deviceReady == true) "模型就绪，可离线识别" else "请先在模型页完成下载",
                        )
                    }
                }
            }

            // ── 识别
            SectionLabel("识别")
            SettingCard {
                SettingRow(
                    label = "识别引擎",
                    value = when (mode) {
                        OcrMode.Cloud -> "PaddleOCR-VL 1.6（云端）"
                        OcrMode.Local -> "PaddleOCR-VL 1.6（本地服务）"
                        OcrMode.OnDevice -> "PP-DocLayoutV3 + GLM-OCR（端侧）"
                    },
                    onClick = {},
                )
                RowDivider()
                SettingRow(label = "输出格式", value = "Markdown + LaTeX", onClick = {})
                RowDivider()
                SettingRow(label = "公式渲染", value = "WebView + KaTeX", onClick = {})
                RowDivider()
                SwitchRow(label = "自动裁边与透视校正", checked = true, onCheckedChange = {})
                RowDivider()
                SwitchRow(label = "去除手写笔迹", checked = false, onCheckedChange = {})
            }

            // ── 外观
            SectionLabel("外观")
            SettingCard {
                SwitchRow(
                    label = "深色外观",
                    hint = "深色也会保留奶油底，不用纯黑",
                    checked = dark,
                    onCheckedChange = onDarkChange,
                )
                RowDivider()
                SettingRow(label = "界面字号", value = "标准", onClick = {})
                RowDivider()
                SettingRow(label = "图片压缩", value = "长边 2048 px", onClick = {})
            }

            // ── 数据
            SectionLabel("数据")
            SettingCard {
                SettingRow(label = "识别记录", value = "$recordCount 条")
                RowDivider()
                SettingRow(
                    label = if (recordCount == 0) "清空识别记录" else "清空识别记录",
                    onClick = onClearRecords,
                    danger = true,
                )
                RowDivider()
                SettingRow(label = "缓存占用", value = "128 MB", onClick = {})
            }

            // ── 关于
            SectionLabel("关于")
            SettingCard {
                SettingRow(label = "版本", value = "1.0.0")
                RowDivider()
                SettingRow(label = "反馈与建议", value = "体验 · 识别问题", onClick = onFeedback)
                RowDivider()
                SettingRow(label = "设计风格", value = "Claude · design-md", onClick = {})
                RowDivider()
                SettingRow(label = "开源许可", value = "MIT", onClick = {})
            }

            Spacer(Modifier.height(Space.lg))
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onExit).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(text = "退出演示 · 返回课程列表", style = OcrType.Caption, color = c.primary)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    text = "OCR Layout · Jetpack Compose 教学示例",
                    style = OcrType.Caption.copy(fontSize = 12.sp),
                    color = c.mutedSoft,
                )
            }
        }
    }
}

/**
 * 单项编辑弹窗。
 *
 * 没有用系统的 TextField 放进设置行里：设置行只有 52dp 高，内嵌输入框会撑破节奏。
 * 点一下 → 弹窗里输 → 确定，交互更接近 iOS 的"设置项编辑"，也不会误触。
 */
@Composable
private fun EditValueDialog(
    title: String,
    initial: String,
    hint: String,
    secret: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ocr.colors
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.canvas,
        title = { Text(title, style = OcrType.TitleMd, color = c.ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = OcrType.BodySm.copy(color = c.ink),
                    placeholder = {
                        Text(hint, style = OcrType.Caption, color = c.mutedSoft)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(hint, style = OcrType.Caption.copy(fontSize = 12.sp), color = c.mutedSoft)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text("保存", color = c.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.muted) }
        },
    )
}

/**
 * 接口模式三选一弹窗。
 *
 * 用了什么组件：AlertDialog + 可点击行（选中项前置珊瑚色圆点）。
 * 之前「接口模式」是二值翻转（点一下 Cloud↔Local），加入端侧后三态循环点会绕晕，
 * 列表式一眼可选；每行带一句说明，新手不用猜"端侧"是什么。
 */
@Composable
private fun ModeChoiceDialog(
    current: OcrMode,
    onSelect: (OcrMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ocr.colors
    val options = listOf(
        Triple(OcrMode.Cloud, "星河社区异步接口", "需要 Access Token，联网识别"),
        Triple(OcrMode.Local, "自建 PaddleX 服务", "paddlex --serve，需填服务地址"),
        Triple(OcrMode.OnDevice, "模型下载到本机", "离线识别，需先在模型页完成下载"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.canvas,
        title = { Text("识别服务用哪个？", style = OcrType.TitleMd, color = c.ink) },
        text = {
            Column {
                options.forEachIndexed { i, (m, title, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(m) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (m == current) c.primary else c.hairline),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(text = m.label, style = OcrType.BodySm, color = c.ink)
                            Text(
                                text = title + " · " + desc,
                                style = OcrType.Caption.copy(fontSize = 12.sp),
                                color = c.mutedSoft,
                            )
                        }
                    }
                    if (i < options.lastIndex) RowDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.muted) }
        },
    )
}

/** Token 只露前后各 4 位，截图/演示时不会把整串 Key 泄出去 */
private fun maskToken(token: String): String =
    if (token.length <= 10) "••••" else token.take(4) + "••••" + token.takeLast(4)

/* ------------------------------------------------------------------ *
 * 预览组：设置面板宽/窄两形态（右侧滑面板在横屏平板下更宽）。无 IO，Layoutlib 可跑。
 * ------------------------------------------------------------------ */
@Preview(name = "phone", showBackground = true, device = Devices.PHONE)
@Preview(name = "landscape", showBackground = true, widthDp = 800, heightDp = 380)
@Preview(name = "foldable", showBackground = true, device = Devices.FOLDABLE)
@Preview(name = "tablet", showBackground = true, device = Devices.TABLET)
@Composable
private fun OcrSettingsScreenPreview() {
    CompositionLocalProvider(LocalOcrColors provides OcrColors.Light) {
        OcrSettingsScreen(
            dark = false,
            onDarkChange = {},
            recordCount = 3,
            mode = OcrMode.Cloud,
            baseUrl = OcrVlApi.DEFAULT_BASE,
            model = OcrVlApi.MODEL,
            credential = "",
            deviceReady = null,
            onModeChange = {},
            onBaseUrlChange = {},
            onCredentialChange = {},
            onClearRecords = {},
            onOpenModels = {},
            onBack = {},
            onExit = {},
        )
    }
}
