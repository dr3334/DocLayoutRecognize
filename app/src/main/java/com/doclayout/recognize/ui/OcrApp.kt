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
import com.doclayout.recognize.ui.screen.OcrPipelineScreen
import com.doclayout.recognize.ui.screen.OcrSettingsScreen
import com.doclayout.recognize.ui.screen.OcrSettingsContent
import com.doclayout.recognize.ui.screen.OcrHomeScreen
import com.doclayout.recognize.ui.screen.OcrModelScreen
import com.doclayout.recognize.ui.screen.OcrResultScreen
import com.doclayout.recognize.data.local.LocalOcrEngine
import com.doclayout.recognize.data.local.OnDevicePage
import com.doclayout.recognize.data.local.PdfPageSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

private enum class OcrRoute { Home, Result, Stream, Models, Pipeline, StreamRec }

/** 一次扫描的整体状态。UI 只认这三个，不关心底下有多少张图 */
private sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int, val label: String) : ScanState
    data class Failed(val message: String) : ScanState
}

/** runScan 的一页输入：识别用的临时页文件（PDF 已先栅格化成页 PNG，与图片页同构） */
private data class PageInput(val file: File)

/** 一页识别完的归一产物：端侧带引擎裁剪图目录与 profile 动态 engine 标签，云端两者为占位 */
private data class PageOutcome(
    val file: File,
    val markdown: String,
    val engine: String,
    val elapsedMs: Long,
    val assetsDir: File?,
)

/**
 * @param startRoute 0=首页 1=设置 2=结果页 3=流式演示 4=流水识别测试 5=端侧模型（给截图/演示用，正常点击进入时都是 0）
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
    /** 调试用：--ei ocr 4（流水识别测试页）配套的直达图片路径（adb --es scanpath /data/.../inbox/x.jpg） */
    startPipelinePath: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OcrStore(context) }
    val settings = remember { OcrSettingsStore(context) }
    // 端侧引擎门面（进程级单例）：Models 页与设置浮层的就绪徽标共用
    val ocrEngine = remember { LocalOcrEngine.get(context) }

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
                4 -> OcrRoute.Pipeline
                5 -> OcrRoute.Models
                6 -> OcrRoute.StreamRec
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

    // 端侧模式就绪徽标：只在「端侧已选中或设置浮层开着」时轮询；deviceReady 三态
    // （null=查询中/true/false）供设置页「端侧模型状态」行与 runScan 配置语义使用。
    // 端态必须用 refreshReadiness（磁盘对账并回写内存 readiness）：纯内存版进程冷启动
    // 恒为 unchecked 且无人推进 → 门槛永远拒绝 → 死锁；对账成本仅 state.json + 22 个文件 stat。
    // 非端态（仅浮层打开）读内存快照即可，不做无谓的文件对账。
    var deviceReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(mode, settingsOpen) {
        if (mode != OcrMode.OnDevice && !settingsOpen) {
            deviceReady = null
            return@LaunchedEffect
        }
        while (isActive) {
            deviceReady = runCatching {
                if (mode == OcrMode.OnDevice) ocrEngine.refreshReadiness().filesReady
                else ocrEngine.readiness().filesReady
            }.getOrNull()
            delay(1000)
        }
    }


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

    suspend fun runScan(uris: List<Uri>, merge: Boolean, pdfUri: Uri? = null) {
        // 端态门槛是磁盘事实（模型文件就绪）。readiness 是内存状态、冷启动恒为 unchecked，
        // 判定前先做一次磁盘对账把它推到位 —— 否则深链 --es scan 冷启动时门槛会赶在
        // 就绪轮询的第一次对账之前读到过期内存值，模型明明齐全却秒拒。
        if (settings.mode == OcrMode.OnDevice) {
            runCatching { ocrEngine.refreshReadiness() }
        }
        if (!settings.configured) {
            scanState = ScanState.Failed(
                when (settings.mode) {
                    OcrMode.Cloud -> "云端模式还没填 Access Token：设置 → 识别服务"
                    OcrMode.Local -> "本地模式还没填服务地址：设置 → 识别服务"
                    OcrMode.OnDevice -> "端侧模式还没有就绪的模型：设置 → 管理端侧模型"
                },
            )
            return
        }
        scanState = ScanState.Running(0, 0, "准备中…")
        val staged = mutableListOf<File>()
        // PDF 栅格化的页 PNG 目录：用完整目录删（页数不定，逐个记文件反而容易漏）
        var rasterDir: File? = null
        try {
            // 输入落地成"页文件"序列：普通图 stageUri；PDF 先逐页栅格化成 PNG，
            // 之后与图片页走完全相同的识别/落盘链路（引擎天生只吃图）
            val pages = mutableListOf<PageInput>()
            uris.forEachIndexed { i, u ->
                val f = store.stageUri(context, u, i)
                staged += f
                pages += PageInput(f)
            }
            if (pdfUri != null) {
                scanState = ScanState.Running(0, 0, "PDF 栅格化中…")
                rasterDir = File(context.cacheDir, "scan_pdf_${System.currentTimeMillis()}")
                val pdfPages = PdfPageSource.rasterize(context, pdfUri, rasterDir)
                pdfPages.forEach { pages += PageInput(it) }
            }
            val total = pages.size
            // 防御：uris 与 pdfUri 都为空的调用不应存在，但真发生时给个安静复位而不是
            // 在 results.first() 上抛 NoSuchElementException
            if (total == 0) {
                scanState = ScanState.Idle
                return
            }

            val results = mutableListOf<PageOutcome>()
            var elapsed = 0L
            pages.forEachIndexed { i, page ->
                scanState = ScanState.Running(i, total, "识别第 ${i + 1} / $total 页…")
                if (settings.mode == OcrMode.OnDevice) {
                    // 端侧分流（WP5）：单图全流程在 data 层（LocalOcrEngine.recognizeFile），
                    // 这里只喂进度回调——区域 i/n 写进 ScanState，ScanOverlay 直接展示
                    val r = ocrEngine.recognizeFile(
                        image = page.file,
                        title = OcrStore.titleFor(total),
                    ) { st ->
                        if (st.regionTotal > 0) {
                            scanState = ScanState.Running(
                                i, total,
                                "识别第 ${i + 1} / $total 页 · 区域 ${st.regionCompleted} / ${st.regionTotal}…",
                            )
                        }
                    }
                    elapsed += r.elapsedMs
                    results += PageOutcome(
                        file = page.file,
                        markdown = r.markdown,
                        engine = LocalOcrEngine.engineLabel(r.modelChoice),
                        elapsedMs = r.elapsedMs,
                        assetsDir = r.assetsDir,
                    )
                } else {
                    val pageResult = OcrVlApi.parse(
                        mode = settings.mode,
                        baseUrl = settings.baseUrl,
                        token = settings.credential,
                        image = page.file.readBytes(),
                        model = settings.model,
                    )
                    elapsed += pageResult.elapsedMs
                    results += PageOutcome(
                        file = page.file,
                        markdown = pageResult.markdown,
                        engine = "PaddleOCR-VL 1.6",
                        elapsedMs = pageResult.elapsedMs,
                        assetsDir = null,
                    )
                }
            }
            scanState = ScanState.Running(total, total, "保存中…")

            // engine 标签按模式动态生成：端侧带 profile 名（"端侧 PP-DocLayoutV3 + GLM-OCR"），
            // 云端/本地仍是 "PaddleOCR-VL 1.6"；同一次扫描内 profile 不会变（切模型要卸载引擎）
            if (settings.mode == OcrMode.OnDevice) {
                if (merge || pdfUri != null) {
                    // PDF 天然是一份试卷的多页（页序固定），不弹合并选择，直接整卷合并
                    val rec = store.saveOnDevice(
                        title = OcrStore.titleFor(results.size),
                        engine = results.first().engine,
                        elapsedMs = elapsed,
                        pages = results.map { OnDevicePage(it.file, it.markdown, it.assetsDir) },
                    )
                    scanned = listOf(rec) + scanned
                } else {
                    // 每张一条。倒序插到最前，这样列表头部还是"最后识别的那张"排第一
                    val created = results.map { r ->
                        store.saveOnDevice(
                            title = OcrStore.titleFor(1),
                            engine = r.engine,
                            elapsedMs = r.elapsedMs,
                            pages = listOf(OnDevicePage(r.file, r.markdown, r.assetsDir)),
                        )
                    }
                    scanned = created.reversed() + scanned
                }
            } else {
                val engine = results.first().engine
                if (merge) {
                    val rec = store.save(
                        title = OcrStore.titleFor(results.size),
                        engine = engine,
                        elapsedMs = elapsed,
                        pages = results.map { it.file to it.markdown },
                    )
                    scanned = listOf(rec) + scanned
                } else {
                    // 每张一条。倒序插到最前，这样列表头部还是"最后识别的那张"排第一
                    val created = results.map { r ->
                        store.save(
                            title = OcrStore.titleFor(1),
                            engine = engine,
                            elapsedMs = r.elapsedMs,
                            pages = listOf(r.file to r.markdown),
                        )
                    }
                    scanned = created.reversed() + scanned
                }
            }
            currentIndex = 0
            route = OcrRoute.Result
            scanState = ScanState.Idle
        } catch (e: Exception) {
            scanState = ScanState.Failed(e.message ?: "识别失败")
        } finally {
            // 临时文件用完就删，别把 cache 撑爆（stageUri 的单图缓存 + PDF 的页 PNG 目录）
            staged.forEach { it.delete() }
            rasterDir?.deleteRecursively()
        }
    }

    // 调试通道：adb 直接指定一张本应用可读的图片（或一份 PDF），跳过相册/文件选择。
    // PDF 按扩展名识别：栅格化成页 PNG 后整卷合并（见 runScan 的 pdfUri 分支）
    LaunchedEffect(startScanPath) {
        val path = startScanPath ?: return@LaunchedEffect
        val file = File(path)
        if (!file.exists()) {
            scanState = ScanState.Failed("找不到调试图片：$path")
            return@LaunchedEffect
        }
        if (file.extension.equals("pdf", ignoreCase = true)) {
            runScan(emptyList(), merge = true, pdfUri = Uri.fromFile(file))
        } else {
            runScan(listOf(Uri.fromFile(file)), merge = true)
        }
    }

    // 相册多选（Photo Picker，API 26+ 走系统文件选择器，任何版本都不需要权限）
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> if (uris.isNotEmpty()) pendingUris = uris }

    // PDF 试卷选择（WP5）：Photo Picker 只认图片，试卷 PDF 一般在下载/文件目录，
    // 走 OpenDocument("application/pdf") 单选一份。OpenDocument 不需要存储权限。
    var pendingPdf by remember { mutableStateOf<Uri?>(null) }
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingPdf = uri }

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

    fun launchPdfPicker() {
        // SAF 偶发「文档树被吊销」的 SecurityException：包一层别让应用崩在用户手上
        runCatching { pickPdf.launch(arrayOf("application/pdf")) }
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

                // 流式识别渲染页：演示页主体 + 真实端侧识别（选图 / 深链 --ei ocr 6 --es scanpath），
                // 识别中下半区实时渲染逐区域流式输出，终态切 DocumentIR 区域页（框/调序/导出/回放）
                OcrRoute.StreamRec -> OcrStreamScreen(
                    onBack = { route = OcrRoute.Home },
                    engine = ocrEngine,
                    initialPath = startPipelinePath,
                )

                // 流水识别测试页（WP4）：选图/深链 scanpath → 端侧流水线 → 区域流式 → export 产物。
                // engine 用进程级单例门面；本页自轮询自展示，OcrApp 不介入识别状态。
                OcrRoute.Pipeline -> OcrPipelineScreen(
                    onBack = { route = OcrRoute.Home },
                    engine = ocrEngine,
                    onOpenStream = { route = OcrRoute.Stream },
                    initialPath = startPipelinePath,
                )

                // 端侧模型管理页：下载/暂停/删除/就绪状态（深链 --ei ocr 5）
                OcrRoute.Models -> OcrModelScreen(
                    onBack = { route = OcrRoute.Home },
                    engine = ocrEngine,
                )

                OcrRoute.Home -> OcrHomeScreen(
                    records = records,
                    onOpenSettings = { settingsOpen = true },
                    onOpenStream = { route = OcrRoute.Stream },
                    onOpenStreamRec = { route = OcrRoute.StreamRec },
                    onOpenPipeline = { route = OcrRoute.Pipeline },
                    onCapture = ::launchCamera,
                    onImport = ::launchPicker,
                    onImportPdf = ::launchPdfPicker,
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
                            deviceReady = deviceReady,
                            onModeChange = { next ->
                                // 切模式时，如果地址还是上一个模式的默认值，就跟着换成新模式的默认值；
                                // 用户自己填过的地址（比如局域网 IP）不动。
                                // 端侧没有"地址"概念，跳过跟随逻辑：既不读 defaultBaseFor(OnDevice)（空串），
                                // 也不往 SP 写 base_url。
                                val wasDefault = baseUrl == OcrSettingsStore.defaultBaseFor(mode)
                                mode = next
                                settings.mode = next
                                if (next != OcrMode.OnDevice && wasDefault) {
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
                            onOpenModels = {
                                settingsOpen = false
                                route = OcrRoute.Models
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

        // PDF 选中后直接开跑（WP5）：一份 PDF = 一份试卷的多页，页序固定，
        // 不进 MergeChoiceDialog，整卷合并成一条多页记录
        pendingPdf?.let { uri ->
            LaunchedEffect(uri) {
                pendingPdf = null
                runScan(emptyList(), merge = true, pdfUri = uri)
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
