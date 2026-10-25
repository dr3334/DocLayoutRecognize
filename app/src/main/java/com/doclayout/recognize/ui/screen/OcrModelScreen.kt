package com.doclayout.recognize.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.local.LocalCatalogFile
import com.doclayout.recognize.data.local.LocalDownloadActivity
import com.doclayout.recognize.data.local.LocalModelChoice
import com.doclayout.recognize.data.local.LocalModelSummary
import com.doclayout.recognize.data.local.LocalModelTask
import com.doclayout.recognize.data.local.LocalOcrEngine
import com.doclayout.recognize.data.local.LocalReadiness
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.component.RowDivider
import com.doclayout.recognize.ui.component.SectionLabel
import com.doclayout.recognize.ui.component.SettingCard
import com.doclayout.recognize.ui.component.SettingRow
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * ─────────────────────────────────────────────────────────────
 * 端侧模型管理页（下载 / 暂停 / 删除 / 就绪状态）
 *
 * 数据来源只有一个：data/local/LocalOcrEngine（引擎门面）。本页**不做任何 IO**——
 *   • modelSummary() / readiness() 是内存快照，600ms 轮询一次（服务侧 700ms 节流，
 *     轮太快只会白读，600ms 刚好比它密一点，进度条又够顺滑）；
 *   • refreshCatalog / startDownload / pauseDownload / removeModel 是用户动作，
 *     全部在 scope.launch 协程里调，真正的网络/磁盘都发生在门面内的 Dispatchers.IO。
 *
 * 必须遵守的下载时序（引擎写死的协议，顺序错了会抛错）：
 *   先「查看文件清单」(refreshCatalog，网络 + 写 catalog.json 缓存)
 *   → 勾选文件 → startDownload（内部校验清单缓存，没缓存直接抛"请先查看仓库文件清单"）
 *   → 下载是前台服务（DownloadService，dataSync），可暂停、断点续传、SHA-256 校验。
 *
 * POST_NOTIFICATIONS（Android 13+）：通知只是进度提示，被拒不影响下载本身；
 *   但请求必须发生在**前台**（本页的按钮回调就是前台），拿结果后再启动前台服务。
 * ─────────────────────────────────────────────────────────────
 */

/** 端侧模型管理页：深链 --ei ocr 5；设置浮层「管理端侧模型」也跳这里。 */
@Composable
fun OcrModelScreen(
    onBack: () -> Unit,
    engine: LocalOcrEngine,
) {
    val c = Ocr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 轮询来的两份快照（null = 还没拿到第一帧）
    var summary by remember { mutableStateOf<LocalModelSummary?>(null) }
    var readiness by remember { mutableStateOf<LocalReadiness?>(null) }

    // ── 文件清单与勾选：repo → 清单 / repo → 勾选的 path 集合
    var catalogs by remember { mutableStateOf<Map<String, List<LocalCatalogFile>>>(emptyMap()) }
    // 勾选的下载清单要跨进程回收存活（勾完半路被杀，回来还得从"全选"再来一遍）；
    // repo/path 都不含 \u0001 控制字符，用它拼接成扁平串列表存取最省事
    var selectedByRepo by rememberSaveable(stateSaver = SelectedPathsSaver) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var expandedRepo by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshingRepo by remember { mutableStateOf<String?>(null) }

    // ── 操作态
    var busy by remember { mutableStateOf(false) }
    // 权限弹窗期间的挂起下载请求：拿到权限结果（无论给没给）都继续
    var pendingDownload by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    // ── 「识别模型」切换（WP5）：choices 是 model-profiles.json 的可选清单（ovis/glm），
    //    来自 assets 静态文件，进程内不变，拉一次即可；pickingModel 控制选择弹窗。
    var pickingModel by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<LocalModelChoice>>(emptyList()) }

    // 轮询循环：LaunchedEffect 离开组合时协程自动取消，页面销毁轮询即停。
    // readiness 必须走 refreshReadiness（磁盘对账并回写内存）：深链直达本页时 OcrApp 的
    // 轮询不会跑，纯内存版永远停在 unchecked；600ms 对账一次只是 state.json + 若干文件 stat，
    // 同时保证「下载完成 → 文件状态变就绪」无需离开页面。
    LaunchedEffect(Unit) {
        // 模型清单与轮询无关，拉一次就够（失败不打断轮询，弹窗里显示空清单）
        runCatching { choices = engine.modelChoices() }
        while (isActive) {
            runCatching { summary = engine.modelSummary() }
            runCatching { readiness = engine.refreshReadiness() }
            delay(600)
        }
    }

    // 轻提示 3s 自动消失（同 OcrStreamScreen 的 hintText 节奏）
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(3000)
            hint = null
        }
    }

    // POST_NOTIFICATIONS 运行时权限：结果回来（给没给都算）继续挂起的下载。
    // 通知被拒只是没有进度条通知，下载服务照常跑 —— 所以这里不分 granted 分支。
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        val job = pendingDownload
        pendingDownload = null
        if (job != null) scope.launch { startDownload(engine, job.first, job.second) { hint = it } }
    }

    // 权限已给（或 < Android 13 无需请求）→ 直接启动；否则挂起请求，结果回来再启动
    fun beginDownload(repo: String, paths: List<String>) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch { startDownload(engine, repo, paths) { hint = it } }
        } else {
            pendingDownload = repo to paths
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun pause() {
        scope.launch {
            runCatching { engine.pauseDownload() }
                .onFailure { hint = "暂停失败：${it.message}" }
        }
    }

    fun remove(repo: String) {
        scope.launch {
            busy = true
            runCatching { engine.removeModel(repo) }
                .onSuccess { hint = "已删除 $repo 的下载文件" }
                .onFailure { hint = "删除失败：${it.message}" }
            busy = false
        }
    }

    // 切换识别模型（WP5，用户要求的「降级做成设置项」）：setModel 会卸载并重新加载引擎，
    // 需要几秒钟；运行中的识别会被引擎拒绝（错误信息走 hint）。切换后 600ms 轮询自动刷新徽标。
    fun switchModel(id: String) {
        pickingModel = false
        scope.launch {
            busy = true
            runCatching { engine.setModel(id) }
                .onSuccess {
                    hint = "已切换到 ${choices.firstOrNull { it.id == id }?.name ?: id}，引擎重新加载需要几秒"
                }
                .onFailure { hint = "切换失败：${it.message}" }
            busy = false
        }
    }

    fun openCatalog(repo: String) {
        if (refreshingRepo != null) return
        scope.launch {
            refreshingRepo = repo
            runCatching { engine.refreshCatalog(repo) }
                .onSuccess { files ->
                    catalogs = catalogs + (repo to files)
                    // 默认全选：仓库里的文件对识别流水线基本都是必需的，
                    // 让用户从"全要"开始做减法，比从空开始做加法不容易漏
                    selectedByRepo = selectedByRepo + (repo to files.map { it.path }.toSet())
                    expandedRepo = repo
                }
                .onFailure { hint = "清单获取失败：${it.message}" }
            refreshingRepo = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            OcrTopBar(
                title = "端侧模型",
                subtitle = "ModelScope 下载 · 断点续传 · SHA-256 校验",
                onBack = onBack,
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = Space.contentMax)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.lg)
                        .padding(bottom = Space.xl),
                ) {
                    // ── 引擎就绪：这是设置页「端侧模型状态」徽标与 runScan 配置检查的同源数据
                    SectionLabel("引擎就绪")
                    SettingCard {
                        // 「识别模型」可点切换（WP5）：当前 profile 名（ovis→OvisOCR2 / glm→GLM-OCR），
                        // 点击弹出选择；busy（下载/删除/切换进行中）时禁点，避免与引擎状态竞争
                        SettingRow(
                            label = "识别模型",
                            value = choices.firstOrNull { it.id == readiness?.modelChoice }?.name
                                ?: readiness?.modelChoice?.takeIf { it.isNotBlank() }
                                ?: "默认",
                            onClick = if (busy) null else ({ pickingModel = true }),
                        )
                        RowDivider()
                        SettingRow(label = "文件状态", value = readinessText(readiness))
                        readiness?.problems?.take(3)?.forEach { (path, reason) ->
                            RowDivider()
                            SettingRow(label = path, value = reason)
                        }
                    }

                    // ── 仓库列表：默认三仓库 + 用户自加（repos 由引擎给出，UI 不写死）
                    SectionLabel("模型仓库")
                    val s = summary
                    if (s == null) {
                        SettingCard {
                            SettingRow(label = "正在读取下载状态…")
                        }
                    } else {
                        s.repos.forEach { repo ->
                            RepoCard(
                                repo = repo,
                                tasks = s.tasks.filter { it.repo == repo },
                                running = s.running,
                                activity = s.activity,
                                catalog = catalogs[repo],
                                selected = selectedByRepo[repo],
                                expanded = expandedRepo == repo,
                                refreshing = refreshingRepo == repo,
                                busy = busy,
                                onOpenCatalog = { openCatalog(repo) },
                                onToggleExpand = { expandedRepo = if (expandedRepo == repo) null else repo },
                                onSelectChange = { sel -> selectedByRepo = selectedByRepo + (repo to sel) },
                                onDownload = {
                                    val paths = selectedByRepo[repo].orEmpty().toList()
                                    if (paths.isEmpty()) hint = "请先勾选要下载的文件"
                                    else beginDownload(repo, paths)
                                },
                                onPause = { pause() },
                                onRemove = { confirmRemove = repo },
                            )
                            Spacer(Modifier.height(Space.sm))
                        }
                        Text(
                            text = "剩余空间 ${formatBytes(s.freeBytes)} · 模型保存在应用私有目录，卸载 App 即清除",
                            style = OcrType.Caption.copy(fontSize = 12.sp),
                            color = c.mutedSoft,
                            modifier = Modifier.padding(top = Space.xs, start = 4.dp),
                        )
                    }
                }
            }
        }

        // ── 轻提示浮条（同 OcrStreamScreen 的样式：深色胶囊、底部居中、自动消失）
        // navigationBarsPadding 让开三键导航栏：fillMaxSize 根 Box 不吃 insets，
        // 只留 32dp 会被约 48dp 的导航栏盖住下半截
        AnimatedVisibility(
            visible = hint != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Space.xl),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Text(
                text = hint.orEmpty(),
                style = OcrType.Caption,
                color = c.canvas,
                modifier = Modifier
                    .background(c.ink.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                    .padding(horizontal = Space.md, vertical = Space.xs),
            )
        }
    }

    // ── 删除确认：删除是破坏性动作（连校验记录一起清），必须二次确认
    confirmRemove?.let { repo ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = c.canvas,
            title = { Text("删除已下载文件", style = OcrType.TitleMd, color = c.ink) },
            text = {
                Text(
                    "将删除 $repo 下载的全部文件与校验记录，断点续传进度一并清除。删除前需要先暂停下载。",
                    style = OcrType.BodySm,
                    color = c.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; remove(repo) }) {
                    Text("删除", color = c.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消", color = c.muted) }
            },
        )
    }

    // ── 识别模型切换弹窗（WP5）：清单来自 model-profiles.json（LocalOcrEngine.modelChoices），
    //    当前选中项打点；选中即执行切换（对话框直接关，进度/结果走 hint 浮条）
    if (pickingModel) {
        AlertDialog(
            onDismissRequest = { pickingModel = false },
            containerColor = c.canvas,
            title = { Text("识别模型用哪个？", style = OcrType.TitleMd, color = c.ink) },
            text = {
                Column {
                    choices.forEachIndexed { i, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { switchModel(choice.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (choice.id == readiness?.modelChoice) c.primary
                                        else c.hairline,
                                    ),
                            )
                            Spacer(Modifier.width(Space.sm))
                            Column(Modifier.weight(1f)) {
                                Text(choice.name, style = OcrType.BodySm, color = c.ink)
                                Text(
                                    text = when (choice.id) {
                                        "glm" -> "中文试卷更稳（当前真机验证的主力）"
                                        "ovis" -> "英文/混排更强，试卷图上有重复生成问题"
                                        else -> choice.repo
                                    },
                                    style = OcrType.Caption.copy(fontSize = 11.5.sp),
                                    color = c.muted,
                                )
                            }
                        }
                        if (i != choices.lastIndex) RowDivider()
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = "切换会卸载并重新加载引擎，需要几秒钟；识别进行中时切换会被拒绝。",
                        style = OcrType.Caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                        color = c.mutedSoft,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingModel = false }) { Text("取消", color = c.muted) }
            },
        )
    }
}

/** 启动下载（校验清单缓存 → 占用 → 前台服务）。失败统一走 [onHint]。 */
private suspend fun startDownload(
    engine: LocalOcrEngine,
    repo: String,
    paths: List<String>,
    onHint: (String) -> Unit,
) {
    runCatching { engine.startDownload(repo, paths) }
        .onSuccess { started -> if (!started) onHint("下载未能启动，请稍后重试") }
        .onFailure { onHint("下载启动失败：${it.message}") }
}

/* ------------------------------------------------------------------ *
 * 仓库卡片
 * ------------------------------------------------------------------ */

/**
 * 单个模型仓库卡：头部状态 + 动作行 + 可展开的文件清单。
 *
 * 用了什么组件：SettingCard（奶白容器）/ Pill（状态徽标）/ ChipButton（本文件私有小按钮）
 * / LinearProgressIndicator（进行中下载的活动条）/ Checkbox（清单勾选，整行可点）。
 */
@Composable
private fun RepoCard(
    repo: String,
    tasks: List<LocalModelTask>,
    running: Boolean,
    activity: LocalDownloadActivity?,
    catalog: List<LocalCatalogFile>?,
    selected: Set<String>?,
    expanded: Boolean,
    refreshing: Boolean,
    busy: Boolean,
    onOpenCatalog: () -> Unit,
    onToggleExpand: () -> Unit,
    onSelectChange: (Set<String>) -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Ocr.colors
    val activeStatuses = setOf("queued", "downloading", "verifying")
    val busyHere = tasks.any { it.status in activeStatuses }
    val doneBytes = tasks.filter { it.status in setOf("verified", "downloaded") }.sumOf { it.size }
    val speed = tasks.filter { it.status in activeStatuses }.sumOf { it.bytesPerSecond }

    SettingCard {
        Column(Modifier.fillMaxWidth().padding(Space.md)) {
            // 头部：仓库名 + 状态徽标（点名字也能展开/收起）
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    if (catalog != null) onToggleExpand() else onOpenCatalog()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = repo,
                    style = OcrType.BodySm,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.widthIn(min = 6.dp))
                Pill(text = repoStatus(tasks, running), background = c.surfaceCard, color = c.muted)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    tasks.isEmpty() -> "尚未下载 · 先查看文件清单再选择文件"
                    else -> "已下载 ${formatBytes(doneBytes)}" +
                        (if (speed > 0) " · ${formatBytes(speed)}/s" else "")
                },
                style = OcrType.Caption.copy(fontSize = 12.sp),
                color = c.mutedSoft,
            )

            // 进行中的下载活动（通知栏同源数据）：进度条 + 当前文件
            if (activity?.repo == repo && activity.total > 0) {
                Spacer(Modifier.height(8.dp))
                val frac = (activity.bytes.toFloat() / activity.total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = c.primary,
                    trackColor = c.creamStrong,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${activity.path} · ${formatBytes(activity.bytes)} / ${formatBytes(activity.total)}",
                    style = OcrType.Caption.copy(fontSize = 12.sp),
                    color = c.mutedSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            // 动作行：清单入口 + 暂停 + 删除
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                ChipButton(
                    label = when {
                        refreshing -> "清单获取中…"
                        expanded -> "收起清单"
                        else -> "查看文件清单"
                    },
                    onClick = { if (expanded) onToggleExpand() else onOpenCatalog() },
                    enabled = !refreshing,
                )
                if (busyHere) ChipButton(label = "暂停下载", onClick = onPause)
                if (tasks.isNotEmpty()) ChipButton(label = "删除已下载", onClick = onRemove, danger = true)
            }

            // 展开的文件清单：勾选行 + 全选/清空/下载所选
            if (expanded && catalog != null) {
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    ChipButton(label = "全选") { onSelectChange(catalog.map { it.path }.toSet()) }
                    ChipButton(label = "清空") { onSelectChange(emptySet()) }
                    ChipButton(
                        label = "下载所选 (${selected?.size ?: 0})",
                        onClick = onDownload,
                        enabled = !busy && !running && (selected?.isNotEmpty() == true),
                        accent = true,
                    )
                }
                Spacer(Modifier.height(Space.xs))
                val byPath = tasks.associateBy { it.path }
                catalog.forEach { file ->
                    val checked = selected?.contains(file.path) == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectChange(toggle(selected.orEmpty(), file.path)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null, // 整行可点，勾选框只做状态显示
                            colors = CheckboxDefaults.colors(
                                checkedColor = c.primary,
                                uncheckedColor = c.mutedSoft,
                                checkmarkColor = c.onPrimary,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = file.path,
                                style = OcrType.Caption,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${taskStatusText(byPath[file.path])} · ${formatBytes(file.size)}",
                                style = OcrType.Caption.copy(fontSize = 11.5.sp),
                                color = c.mutedSoft,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 小号动作按钮（34dp 高的胶囊），Models 页专属：
 * 公共 PrimaryButton 是 44dp 的页面级主按钮，塞进卡片动作行太大。
 * accent = 珊瑚强调（下载），danger = 危险色（删除）。
 * 注意 onClick 必须是**最后一个参数**：Kotlin 尾 lambda 只能绑定最后一个参数，
 * `ChipButton("全选") { ... }` 这种写法才成立。
 */
@Composable
private fun ChipButton(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Ocr.colors
    val fg = when {
        danger -> c.danger
        accent -> c.primary
        else -> c.ink
    }
    val bg = when {
        accent -> c.primary.copy(alpha = 0.12f)
        danger -> c.danger.copy(alpha = 0.10f)
        else -> c.surfaceCard
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption,
            color = if (enabled) fg else fg.copy(alpha = 0.35f),
        )
    }
}

/* ------------------------------------------------------------------ *
 * 展示辅助（纯函数，无状态）
 * ------------------------------------------------------------------ */

/** 集合增删一个元素（勾选行用） */
private fun toggle(set: Set<String>, item: String): Set<String> =
    if (item in set) set - item else set + item

/** 勾选的下载清单的 Saver：Map<repo, paths> ↔ ["repo\u0001path", ...]（rememberSaveable 用） */
private val SelectedPathsSaver = Saver<Map<String, Set<String>>, List<String>>(
    save = { state -> state.flatMap { (repo, paths) -> paths.map { "$repo\u0001$it" } } },
    restore = { saved ->
        saved.mapNotNull { entry ->
            val parts = entry.split('\u0001')
            if (parts.size == 2) parts[0] to parts[1] else null
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, paths) -> paths.toSet() }
    },
)

/** 仓库状态徽标文案 */
private fun repoStatus(tasks: List<LocalModelTask>, running: Boolean): String = when {
    tasks.any { it.status in setOf("queued", "downloading", "verifying") } -> "下载中"
    tasks.any { it.status == "error" } -> "出错可续传"
    tasks.any { it.status == "paused" } -> "已暂停"
    tasks.isNotEmpty() && tasks.all { it.status in setOf("verified", "downloaded") } -> "已就绪"
    tasks.isNotEmpty() -> "下载 ${tasks.size} 文件"
    running -> "有任务进行中"
    else -> "未下载"
}

/** 单个文件的状态文案 */
private fun taskStatusText(task: LocalModelTask?): String = when (task?.status) {
    null -> "未下载"
    "verified" -> "已校验"
    "downloaded" -> "已下载"
    "paused" -> "已暂停"
    "downloading", "verifying", "queued" -> "下载中"
    "error" -> if (task != null) "出错：" + task.error else "出错"
    else -> task.status
}

/** 引擎就绪卡的状态行文案（与 LocalReadiness.state 对应） */
private fun readinessText(r: LocalReadiness?): String = when {
    r == null -> "检查中…"
    r.engineReady -> "引擎已加载，可离线识别"
    r.filesReady -> "模型文件已就绪"
    r.state == "missing-models" -> "缺少 ${r.problems.size} 个模型文件"
    r.state == "checking" -> "正在检查文件…"
    r.state == "loading" -> "正在加载引擎…"
    r.state == "load-failed" -> "引擎加载失败：" + r.message
    else -> "尚未检查（完成下载后自动检查）"
}

/** 字节数 → 人话（MB/GB，一位小数足够） */
private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", b / 1024.0)
    else -> "$b B"
}
