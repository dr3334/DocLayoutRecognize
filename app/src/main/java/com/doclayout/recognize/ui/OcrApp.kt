@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.doclayout.recognize.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import com.doclayout.recognize.ui.theme.LocalOcrColors
import com.doclayout.recognize.data.local.OcrMode
import com.doclayout.recognize.data.local.OcrSettingsStore
import com.doclayout.recognize.data.local.OcrStore
import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.data.remote.OcrVlApi
import com.doclayout.recognize.data.sample.sampleRecords
import com.doclayout.recognize.ui.screen.OcrStreamScreen
import com.doclayout.recognize.ui.screen.OcrSettingsScreen
import com.doclayout.recognize.ui.screen.OcrSettingsContent
import com.doclayout.recognize.ui.screen.OcrHomeScreen
import com.doclayout.recognize.ui.screen.OcrResultScreen
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrColors
import com.doclayout.recognize.ui.theme.OcrType
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.io.File

/**
 * OCR 演示的入口。
 *
 * 只做四件事：选路由、持有跨页面状态（外观、记录列表）、把颜色令牌往下发、
 * 以及串起"选图 → 调云端 → 落盘 → 回到最近识别"这条业务流程。
 *
 * 没有用 Navigation 库：页面只有三个，而且状态要共享，一个 enum + when 更好读。
 */

/*
 * ─────────────────────────────────────────────────────────────
 * 学习导读（全项目最值得精读的文件）
 *
 * 它同时承担三件事，读的时候要分开看：
 *   1. 状态：哪些值必须跨页面共享（dark / records / 设置镜像 / settingsOpen）
 *   2. 路由：一个 enum + when，没有引入 Navigation 库（理由见上方注释）
 *   3. 流程：选图 → runScan → 落盘 → 回到列表；整条链只有 parse/save 两步离开 ui 层
 *
 * 三个反复出现的模式：
 *   - 双写：改设置时同时改 State 和 SharedPreferences（漏一边必出 bug，见 mode/baseUrl）
 *   - 状态提升：Screen 只吃数据和回调，所有决策留在这里
 *   - 覆盖而非挤压：设置面板盖在主界面上，主界面尺寸不变（见文件末尾）
 *
 * 配套阅读：LEARNING.md 第一节「State 与重组」
 * ─────────────────────────────────────────────────────────────
 */

private enum class OcrRoute { Home, Result, Stream }

/** 一次扫描的整体状态。UI 只认这三个，不关心底下有多少张图 */
private sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int, val label: String) : ScanState
    data class Failed(val message: String) : ScanState
}

/**
 * @param startRoute 0=首页 1=设置 2=结果页（给截图/演示用，正常点击进入时都是 0）
 * @param startDark  启动即深色
 * @param startEmpty 启动即"无记录"空状态（同时禁用示例数据）
 * @param startRecord 直接打开第几条示例记录
 */
@Composable
fun OcrApp(
    onExit: () -> Unit,
    startRoute: Int = 0,
    startDark: Boolean = false,
    startEmpty: Boolean = false,
    startRecord: Int = 0,
    startOpen: Boolean = false,
    startSplit: Float = 0.5f,
    /** 调试用：启动后直接扫这个文件（adb --es scan /data/.../inbox/x.jpg），正常流程为 null */
    startScanPath: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OcrStore(context) }
    val settings = remember { OcrSettingsStore(context) }

    // 设置项的真值存在 SharedPreferences 里，但 SP 不是 Compose State：
    // 把 settings.mode 直接读出来传进界面，写回之后不会触发重组 —— 界面还显示旧值，
    // 连 `if (mode == OcrMode.Cloud)` 控制的「模型」行也不会出现/消失。
    // 所以各镜像一份 State：State 负责刷新界面，SP 负责重启后还在。
    // 注意写入必须两边一起写（见下面的回调），漏一边就会出现「界面变了下次启动又回去」或反之。
    var mode by remember { mutableStateOf(settings.mode) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var credential by remember { mutableStateOf(settings.credential) }
    // 模型名不可编辑，只跟着 mode 的默认值显示，不需要 State
    val model = settings.model

    var route by rememberSaveable {
        mutableStateOf(
            when (startRoute) {
                2 -> OcrRoute.Result
                3 -> OcrRoute.Stream
                else -> OcrRoute.Home
            }
        )
    }
    var dark by rememberSaveable { mutableStateOf(startDark) }
    // 设置从"页面"变成"浮层"：没有 OcrRoute.Settings 了，只有一个开关。
    // 用 rememberSaveable 而不是 remember —— 抽屉开着转屏，它还应该开着。
    var settingsOpen by rememberSaveable { mutableStateOf(startRoute == 1) }
    // 真实记录列表：从 filesDir 读，扫描成功后整体重读一遍（页数很少，不做增量）
    var scanned by remember { mutableStateOf<List<OcrRecord>>(emptyList()) }
    var currentIndex by rememberSaveable {
        mutableIntStateOf(startRecord.coerceIn(0, sampleRecords.lastIndex))
    }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    // 一次选进来的图，等用户确认"合并 / 分开"后再开跑
    var pendingUris by remember { mutableStateOf<List<Uri>?>(null) }
    var camUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { scanned = store.list() }


    // 示例数据只在"没有任何真实记录"时兜底，避免首页空成一片
    val records: List<OcrRecord> =
        if (scanned.isEmpty() && !startEmpty) sampleRecords else scanned
    val current = records.getOrNull(currentIndex) ?: records.firstOrNull() ?: sampleRecords[0]

    val colors = if (dark) OcrColors.Dark else OcrColors.Light
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }

    suspend fun runScan(uris: List<Uri>, merge: Boolean) {
        if (!settings.configured) {
            scanState = ScanState.Failed(
                when (settings.mode) {
                    OcrMode.Cloud -> "云端模式还没填 Access Token：设置 → 识别服务"
                    OcrMode.Local -> "本地模式还没填服务地址：设置 → 识别服务"
                },
            )
            return
        }
        scanState = ScanState.Running(0, uris.size, "准备中…")
        val staged = mutableListOf<File>()
        try {
            uris.forEachIndexed { i, u -> staged += store.stageUri(context, u, i) }
            val results = mutableListOf<Pair<File, String>>()
            var elapsed = 0L
            staged.forEachIndexed { i, file ->
                scanState = ScanState.Running(i, uris.size, "识别第 ${i + 1} / ${uris.size} 张…")
                val page = OcrVlApi.parse(
                    mode = settings.mode,
                    baseUrl = settings.baseUrl,
                    token = settings.credential,
                    image = file.readBytes(),
                    model = settings.model,
                )
                elapsed += page.elapsedMs
                results += file to page.markdown
            }
            scanState = ScanState.Running(uris.size, uris.size, "保存中…")

            val engine = "PaddleOCR-VL 1.6"
            if (merge) {
                val rec = store.save(
                    title = OcrStore.titleFor(results.size),
                    engine = engine,
                    elapsedMs = elapsed,
                    pages = results,
                )
                scanned = listOf(rec) + scanned
            } else {
                // 每张一条。倒序插到最前，这样列表头部还是"最后识别的那张"排第一
                val created = results.map { (file, md) ->
                    store.save(
                        title = OcrStore.titleFor(1),
                        engine = engine,
                        elapsedMs = elapsed / results.size,
                        pages = listOf(file to md),
                    )
                }
                scanned = created.reversed() + scanned
            }
            currentIndex = 0
            route = OcrRoute.Result
            scanState = ScanState.Idle
        } catch (e: Exception) {
            scanState = ScanState.Failed(e.message ?: "识别失败")
        } finally {
            // 临时文件用完就删，别把 cache 撑爆
            staged.forEach { it.delete() }
        }
    }

    // 调试通道：adb 直接指定一张本应用可读的图片，跳过相册选择
    LaunchedEffect(startScanPath) {
        val path = startScanPath ?: return@LaunchedEffect
        val file = File(path)
        if (!file.exists()) {
            scanState = ScanState.Failed("找不到调试图片：$path")
            return@LaunchedEffect
        }
        runScan(listOf(Uri.fromFile(file)), merge = true)
    }

    // 相册多选（Photo Picker，API 26+ 走系统文件选择器，任何版本都不需要权限）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> if (uris.isNotEmpty()) pendingUris = uris }

    // 拍照：写到 cacheDir/camera，再用 FileProvider 换一个相机能写的 Uri
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = camUri
        if (ok && uri != null) pendingUris = listOf(uri)
    }

    fun launchPicker() {
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchCamera() {
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            camUri = uri
            takePhoto.launch(uri)
        }.onFailure {
            // 没有相机（平板无摄像头 / 模拟器）就退回相册，别把用户卡死
            launchPicker()
        }
    }

    CompositionLocalProvider(LocalOcrColors provides colors) {
        // 面板宽度驱动一切：主界面右侧让出 panelW，面板贴右边长出来。
        // 用 BoxWithConstraints 是因为宽度要按 maxWidth 的 75% 算。
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.canvas)) {
            val panelTarget = maxWidth * 0.75f
            // 动画只改 translationX（纯绘制变换），不改宽度。
            // 改宽度的话每一帧都要重新 measure/layout 整个设置列表（30 个 SettingRow + Switch），
            // 打开时会一顿一顿；平移只改图层，不触发测量。
            val panelSlide by animateFloatAsState(
                targetValue = if (settingsOpen) 1f else 0f,
                animationSpec = tween(durationMillis = 260),
                label = "settingsPanel",
            )
            val panelOffsetPx = with(LocalDensity.current) { panelTarget.toPx() } * (1f - panelSlide)
            val scrimAlpha by animateFloatAsState(
                targetValue = if (settingsOpen) 1f else 0f,
                animationSpec = tween(durationMillis = 260),
                label = "settingsScrim",
            )
            Box(Modifier.fillMaxSize()) {
                when (route) {
                OcrRoute.Stream -> OcrStreamScreen(onBack = { route = OcrRoute.Home })

                OcrRoute.Home -> OcrHomeScreen(
                    records = records,
                    onOpenSettings = { settingsOpen = true },
                    onOpenStream = { route = OcrRoute.Stream },
                    onCapture = ::launchCamera,
                    onImport = ::launchPicker,
                    onOpenRecord = { record ->
                        currentIndex = records.indexOf(record).coerceAtLeast(0)
                        route = OcrRoute.Result
                    },
                )

                OcrRoute.Result -> OcrResultScreen(
                    record = current,
                    onBack = { route = OcrRoute.Home },
                    startOpen = startOpen || current.isReal,
                    startSplit = startSplit,
                )
                }
            }

            // ── 右侧设置面板
            // 不是覆盖式抽屉，也不是贴底 sheet：主界面被压到左边 1/4，面板自己占 3/4。
            // 宽度用 animateDpAsState 而不是 AnimatedVisibility：后者是整体淡入淡出，
            // 拿不到"被逐渐拉开"的过程，主界面也不会跟着缩。
            // 主界面保持原比例，只盖一层暗色 —— 面板是"覆盖"，不是"挤压"。
            // 点暗处关闭，手感和 ModalBottomSheet 的 scrim 一致。
            if (scrimAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.scrim.copy(alpha = 0.30f * scrimAlpha))
                        .clickable { settingsOpen = false },
                )
            }

            // 面板始终挂着（收起时只是平移到屏幕外）：否则第一次打开要现组合 30 个设置行，
            // 会有一下明显的停顿；组合过一次之后就一直复用。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelTarget)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = panelOffsetPx }
                    .clipToBounds()
                    .background(colors.canvas),
            ) {
                        OcrSettingsScreen(
                            dark = dark,
                            onDarkChange = { dark = it },
                            recordCount = scanned.size,
                            mode = mode,
                            baseUrl = baseUrl,
                            model = model,
                            credential = credential,
                            onModeChange = { next ->
                                // 切模式时，如果地址还是上一个模式的默认值，就跟着换成新模式的默认值；
                                // 用户自己填过的地址（比如局域网 IP）不动
                                val wasDefault = baseUrl == OcrSettingsStore.defaultBaseFor(mode)
                                mode = next
                                settings.mode = next
                                if (wasDefault) {
                                    val nextDefault = OcrSettingsStore.defaultBaseFor(next)
                                    baseUrl = nextDefault
                                    settings.baseUrl = nextDefault
                                }
                            },
                            onBaseUrlChange = { baseUrl = it; settings.baseUrl = it },
                            onCredentialChange = { credential = it; settings.credential = it },
                            onClearRecords = {
                                scope.launch {
                                    store.clear()
                                    scanned = emptyList()
                                }
                            },
                            onBack = { settingsOpen = false },
                            onExit = onExit,
                        )
            }
        }

        ScanOverlay(
            state = scanState,
            onDismiss = { scanState = ScanState.Idle },
            onOpenSettings = {
                scanState = ScanState.Idle
                settingsOpen = true
            },
        )

        // 多选后先问一句：合并成一份多页试卷，还是每张各成一条
        pendingUris?.let { uris ->
            if (uris.size > 1) {
                MergeChoiceDialog(
                    count = uris.size,
                    onMerge = {
                        pendingUris = null
                        scope.launch { runScan(uris, merge = true) }
                    },
                    onSeparate = {
                        pendingUris = null
                        scope.launch { runScan(uris, merge = false) }
                    },
                    onCancel = { pendingUris = null },
                )
            } else {
                LaunchedEffect(uris) {
                    pendingUris = null
                    scope.launch { runScan(uris, merge = true) }
                }
            }
        }

        // 系统返回键：结果→首页→退出演示
        BackHandler {
            when {
                settingsOpen -> settingsOpen = false
                route == OcrRoute.Home -> onExit()
                else -> route = OcrRoute.Home
            }
        }
    }
}

/** 识别进度 / 失败提示 */
@Composable
private fun ScanOverlay(state: ScanState, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Ocr.colors
    when (state) {
        ScanState.Idle -> Unit

        is ScanState.Running -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            containerColor = c.canvas,
            title = { Text("正在识别", style = OcrType.TitleMd, color = c.ink) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = c.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(state.label, style = OcrType.BodySm, color = c.ink)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已处理 ${state.done} / ${state.total} 张",
                        style = OcrType.Caption,
                        color = c.muted,
                    )
                }
            },
        )

        is ScanState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.canvas,
            title = { Text("识别没成功", style = OcrType.TitleMd, color = c.ink) },
            text = {
                Text(
                    state.message,
                    style = OcrType.BodySm.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    color = c.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = onOpenSettings) { Text("去设置", color = c.primary) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("知道了", color = c.muted) }
            },
        )
    }
}

/** 多选之后的二选一：合并 / 分开 */
@Composable
private fun MergeChoiceDialog(
    count: Int,
    onMerge: () -> Unit,
    onSeparate: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Ocr.colors
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.canvas,
        title = { Text("$count 张图片怎么归档？", style = OcrType.TitleMd, color = c.ink) },
        text = {
            Text(
                "合并：当成同一份试卷的 $count 页，记录里可以逐页翻。\n分开：每张各成一条记录。",
                style = OcrType.BodySm.copy(fontSize = 13.sp, lineHeight = 21.sp),
                color = c.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onMerge) { Text("合并为一卷", color = c.primary) }
        },
        dismissButton = {
            TextButton(onClick = onSeparate) { Text("分开成 $count 条", color = c.muted) }
        },
    )
}
