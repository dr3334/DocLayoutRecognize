package com.doclayout.recognize

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

private enum class OcrRoute { Home, Settings, Result }

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

    var route by rememberSaveable {
        mutableStateOf(
            when (startRoute) {
                1 -> OcrRoute.Settings
                2 -> OcrRoute.Result
                else -> OcrRoute.Home
            }
        )
    }
    var dark by rememberSaveable { mutableStateOf(startDark) }
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
        Box(Modifier.fillMaxSize().background(colors.canvas)) {
            when (route) {
                OcrRoute.Home -> OcrHomeScreen(
                    records = records,
                    onOpenSettings = { route = OcrRoute.Settings },
                    onCapture = ::launchCamera,
                    onImport = ::launchPicker,
                    onOpenRecord = { record ->
                        currentIndex = records.indexOf(record).coerceAtLeast(0)
                        route = OcrRoute.Result
                    },
                )

                OcrRoute.Settings -> OcrSettingsScreen(
                    dark = dark,
                    onDarkChange = { dark = it },
                    recordCount = scanned.size,
                    mode = settings.mode,
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    credential = settings.credential,
                    onModeChange = { next ->
                        // 切模式时，如果地址还是上一个模式的默认值，就跟着换成新模式的默认值；
                        // 用户自己填过的地址（比如局域网 IP）不动
                        val wasDefault = settings.baseUrl == OcrSettingsStore.defaultBaseFor(settings.mode)
                        settings.mode = next
                        if (wasDefault) settings.baseUrl = OcrSettingsStore.defaultBaseFor(next)
                    },
                    onBaseUrlChange = { settings.baseUrl = it },
                    onCredentialChange = { settings.credential = it },
                    onClearRecords = {
                        scope.launch {
                            store.clear()
                            scanned = emptyList()
                        }
                    },
                    onBack = { route = OcrRoute.Home },
                    onExit = onExit,
                )

                OcrRoute.Result -> OcrResultScreen(
                    record = current,
                    onBack = { route = OcrRoute.Home },
                    startOpen = startOpen || current.isReal,
                    startSplit = startSplit,
                )
            }
        }

        ScanOverlay(
            state = scanState,
            onDismiss = { scanState = ScanState.Idle },
            onOpenSettings = {
                scanState = ScanState.Idle
                route = OcrRoute.Settings
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
            when (route) {
                OcrRoute.Home -> onExit()
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
