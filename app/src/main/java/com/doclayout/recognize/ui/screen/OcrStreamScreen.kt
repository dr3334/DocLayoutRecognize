package com.doclayout.recognize.ui.screen

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.doclayout.recognize.data.local.LocalIrExport
import com.doclayout.recognize.data.local.LocalJobStatus
import com.doclayout.recognize.data.local.LocalOcrEngine
import com.doclayout.recognize.data.local.LocalStreamRegion
import com.doclayout.recognize.data.local.OnDevicePage
import com.doclayout.recognize.data.local.OcrStore
import com.doclayout.recognize.data.local.OcrStreamDemo
import com.doclayout.recognize.data.local.OcrStreamExport
import com.doclayout.recognize.data.local.decodeSampled
import com.doclayout.recognize.data.local.decodeSampledAsset
import com.doclayout.recognize.data.model.OcrStreamPage
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.layout.OcrLayoutMode
import com.doclayout.recognize.ui.layout.ResizableRow
import com.doclayout.recognize.ui.layout.rememberOcrWindowInfo
import com.doclayout.recognize.ui.layout.rememberSplitPaneState
import com.doclayout.recognize.ui.render.OcrMarkdownView
import com.doclayout.recognize.ui.theme.LocalOcrColors
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrColors
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.doclayout.recognize.data.model.OcrStreamBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/*
 * ─────────────────────────────────────────────────────────────
 * 学习导读
 *
 * 这个文件用不到 200 行讲清"流式渲染"的全部机制：
 *   1. 数据是区域级（OcrStreamPage），不是整篇 markdown
 *   2. 循环里只做一件事：让 typing 这个 String 变长
 *   3. 界面自己跟着变（声明式），全程没有一处"刷新"调用
 *
 * 两个刻意的取舍：
 *   - 文本每 3 个字符刷一次（cut += 3），不是每字符：每次变化都要跨进程
 *     evaluateJavascript 一次，逐字符是 80+ 次/秒，WebView 会掉帧
 *   - 图片块整块出现，不逐字：半截的 ![](stream/assets/p00 只会闪一下纯文本
 *   - 真实识别的实时输出也不是整段直上：追赶循环每 26ms 放出 liveShownLen 附近的
 *     一小截，积压越多走得越快（见 liveMarkdown 下面的 LaunchedEffect），观感连续
 *
 * 公式能"边打边渲"靠的是 render.html 里的 balance()，不是这里的代码。
 * 配套阅读：LEARNING.md 第二节「公式怎么能边打边渲染」
 * ─────────────────────────────────────────────────────────────
 */

/**
 * 区域级流式渲染演示。
 *
 * 数据流和结果页完全不同：结果页拿到的是**一整篇 Markdown**（一次到齐），
 * 这里拿到的是**区域 JSON**，所以可以一块一块地吐出来，并且每块都能回到原图上定位：
 *
 *   document.json → pages[0].blocks[]（11 个区域，带 bbox + 自己的裁剪图）
 *                 → 按 reading_order 排序
 *                 → 逐区域转 Markdown → 逐字累积 → setMarkdown（render.html 做 diff，只重建最后一块）
 *
 * 公式能"边打边渲"靠的是 render.html 里现成的两条机制，不是这里做的：
 *   1. `parseBlocks` 把没闭合的 `$$...` 标成 closed=false
 *   2. `balance()` 把半截命令补齐（`\fra` 砍掉、`{` 补 `}`），于是 `$-\frac{y^{2}` 也能先渲一版
 */
@Composable
fun OcrStreamScreen(
    onBack: () -> Unit,
    /** 允许外部注入（测试/换数据源）；为 null 时读内置 assets 样本 */
    page: OcrStreamPage? = null,
    /** 点中某个区域时回调；默认空实现，所以现有调用点不用改 */
    onBlockClick: (OcrStreamBlock) -> Unit = {},
    /**
     * 端侧引擎门面：非 null 时本页升级为「流式识别渲染」—— 选图 / 深链 scanpath 直达后
     * 跑真实端侧流水线，识别中下半区实时渲染逐区域流式输出（边生成边渲染），
     * 终态自动切到 DocumentIR 区域页（框 / 正文联动 / 调序 / 导出 / 回放全部可用）。
     * null = 纯演示模式（内置样本 + 打字机回放），原有调用点行为完全不变。
     */
    engine: LocalOcrEngine? = null,
    /** 深链 --es scanpath 直达的图片绝对路径（应用可读位置）；仅 engine 非 null 时生效 */
    initialPath: String? = null,
    /**
     * 导入队列（拍照 / 相册 / PDF 在端侧模式下由 OcrApp 分流过来）：待识别页文件的绝对路径。
     * 页面逐张跑真实流水线——每张都完整经历「边生成边渲染 → 终态框联动」，一张完成自动
     * 开跑下一张；全部跑完后按 [queueMerge] 落识别历史（一条多页 / 每张一条）并回调
     * [onRecordsSaved]。空列表 = 普通入口（本页选图 / 深链），行为与从前完全一致。
     */
    initialQueue: List<String> = emptyList(),
    /** 导入队列的落盘方式：true=全部页合成一条（多选合并 / PDF 整卷），false=每张一条 */
    queueMerge: Boolean = false,
    /** 队列被页面接受时回调（OcrApp 清掉自己的 pending 副本，防重进页面重放） */
    onQueueAccepted: () -> Unit = {},
    /** 导入队列全部识别并落盘完成后回调（OcrApp 重读历史列表） */
    onRecordsSaved: () -> Unit = {},
) {
    val c = Ocr.colors
    val context = LocalContext.current
    // 内置样本解析（读+解析 262KB assets JSON）挪到 IO：先复位再异步加载（唯一正确写法），
    // 加载完成的短暂空窗由各消费端的判空占位兜住
    val demoData by produceState<OcrStreamPage?>(page, page) {
        value = page
        if (page == null) value = withContext(Dispatchers.IO) { OcrStreamDemo.load(context) }
    }

    // ── 真实识别会话（engine == null 时整段休眠，页面行为与纯演示一致）。
    // 状态机三态见 [StreamPhase]：没发起过识别走演示；跑起来后 jobStatus 非空；
    // 终态且拿到 DocumentIR 区域页（recPage）后进入"完成"，框与交互全部挂在真实数据上。
    val scope = rememberCoroutineScope()
    // 只为复用 stageUri（content Uri → cacheDir 文件）；记录仓库本体是引擎的 documents 目录
    val store = remember { OcrStore(context) }
    // 会话身份走 saveable：重建后 LaunchedEffect(jobDocId) 重新轮询，终态自动 readExport
    // 回填 recPage，识别 UI 继续跟进。jobStatus/recPage 等轮询快照保持 remember（重新拉）
    var jobDocId by rememberSaveable { mutableStateOf<String?>(null) }
    var jobJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var jobStatus by remember { mutableStateOf<LocalJobStatus?>(null) }
    // 流式快照：只在 stream.revision 变化时才写入（见轮询循环），驱动下半区实时重组
    var liveRegions by remember { mutableStateOf<List<LocalStreamRegion>>(emptyList()) }
    var recPage by remember { mutableStateOf<OcrStreamPage?>(null) }
    var recImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var recFramePath by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var recError by remember { mutableStateOf<String?>(null) }
    val running = jobStatus?.let { it.running && !it.terminal } == true

    // ── 导入队列（OcrApp 分流来的拍照/相册/PDF）。queue 是待跑列表（队首=正在跑或下一个），
    // queueDone 收集已完成页的引擎记录 id。全部 saveable：旋转/进程重建后队列继续——
    // 引擎作业在 :ocr 进程本来就活着，重进页面轮询会自动接上
    var queue by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(initialQueue) }
    var queueDone by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(emptyList<String>()) }
    var queueMergeMode by rememberSaveable { mutableStateOf(queueMerge) }
    var queueModel by rememberSaveable { mutableStateOf("") }

    val phase = when {
        recPage != null -> StreamPhase.Done
        jobStatus != null -> StreamPhase.Recognizing
        else -> StreamPhase.Demo
    }
    val data = if (phase == StreamPhase.Done) recPage else demoData
    // ordered 每次访问都是新 List（getter 里 mapNotNull），必须 remember：
    // 否则 pointerInput 的 key 每帧都变，手势会被不停重启（和 sheetDrag 那个坑同源），
    // colorOf 的 remember(ordered) 也会失效、每次重组重算一遍。
    val ordered = remember(data) { data?.ordered.orEmpty() }

    // ── 打字机 / 平滑上屏状态（声明在 runOne 之前：新一轮识别要把它们清零，见下）
    // playKey/doneText/typing/activeIndex 服务"回放"；liveShownLen 服务"识别中的平滑上屏"，
    // 它随下半区一起下沉进了 [StreamBodyPane] —— 26ms 一次的推进只重组下半区
    var playKey by remember { mutableIntStateOf(0) }
    var doneText by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf("") }
    var activeIndex by remember { mutableIntStateOf(-1) }
    // 终态回放是否进行中：false 时下半区直接给终稿全文，不逐字
    var replaying by remember { mutableStateOf(false) }

    // ── 真实识别的启动 / 轮询 / 取消 / 选图（逻辑与流水识别测试页同源，见 OcrPipelineScreen）
    fun runOne(file: File) {
        val eng = engine ?: return
        scope.launch {
            starting = true
            recError = null
            try {
                // 先磁盘对账回写引擎内存 readiness：模型没就绪就明说，别让用户等报错。
                // usable = files-ready 或 ready（引擎已加载）：只认 filesReady 会在引擎
                // 加载后的第二次识别误报"模型还没就绪"（真机踩过的坑，详见其定义处注释）
                val ready = eng.refreshReadiness()
                if (!ready.usable) {
                    recError = "模型还没就绪（${ready.state}）：${ready.message}\n去 设置 → 管理端侧模型 处理"
                    return@launch
                }
                // 新一轮：清上一轮展示状态，回到识别中形态。
                // playKey 归零很关键：终态靠 playKey==0 判断"本轮没按过重播 → 正文已经
                // 流式放过一遍，直接展示终稿"；不归零的话第二轮完成还会整屏重打。
                // liveShownLen 的清零在 StreamBodyPane 里：liveMarkdown 变空时自动归零
                recImagePath = file.absolutePath
                jobDocId = null; jobJobId = null; jobStatus = null
                liveRegions = emptyList(); recPage = null; recFramePath = null
                playKey = 0
                // createInput 落盘记录并 autoStart；再显式 start 一次兜住"只落盘没启动"的分支
                val summary = eng.createInput(title = OcrStore.titleFor(1), image = file)
                jobDocId = summary.id
                runCatching { eng.start(summary.id, again = false) }
            } catch (e: Exception) {
                recError = e.message ?: "启动流水线失败"
            } finally {
                starting = false
            }
        }
    }

    // 状态轮询：sequence 去重写 jobStatus；流式文本按 revision 变化才更新；
    // 终态读 export（document.json → OcrStreamPage + 规范化图路径），recPage 非空即切"完成"
    LaunchedEffect(jobDocId) {
        val eng = engine ?: return@LaunchedEffect
        val id = jobDocId ?: return@LaunchedEffect
        var lastSequence = -1L
        var lastRevision = -1L
        while (isActive) {
            val s = runCatching { eng.status(id) }.getOrNull()
            if (s != null) {
                if (s.sequence != lastSequence) {
                    lastSequence = s.sequence
                    jobStatus = s
                    if (s.jobId.isNotBlank()) jobJobId = s.jobId
                }
                val rev = s.stream?.revision ?: 0L
                if (rev != lastRevision) {
                    lastRevision = rev
                    liveRegions = s.stream?.regions.orEmpty()
                }
                if (s.terminal) {
                    val export: LocalIrExport? = runCatching { eng.readExport(id) }.getOrNull()
                    recPage = export?.page
                    recFramePath = export?.inputPath
                    // 导入队列模式：本页终态 → 收集记录 id、清掉队首；还有剩余页时**必须**
                    // 把会话身份一并清掉（jobDocId 置 null）——推进效应的门槛就是
                    // 「队列非空 && jobDocId == null」，不清的话第 2 张永远不会开跑
                    // （真机踩过：第 1 张终态后页面停在完成态，队列挂着不动）
                    if (queue.isNotEmpty()) {
                        if (id !in queueDone) {
                            queueDone = queueDone + id
                            if (s.modelChoice.isNotBlank()) queueModel = s.modelChoice
                        }
                        queue = queue.drop(1)
                        if (queue.isNotEmpty()) {
                            jobDocId = null
                            jobJobId = null
                            jobStatus = null
                        }
                    }
                    break
                }
            }
            delay(400)
        }
    }

    // 深链直达：--ei ocr 6 --es scanpath <应用可读路径>（OcrApp 没实现 onNewIntent，先 force-stop）。
    // 消费标志必须 saveable：旋转重建后 intent extra 原样重投，不挡住就会重复 createInput
    var initialPathConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPath) {
        if (engine == null) return@LaunchedEffect
        val path = initialPath
        if (path == null || initialPathConsumed) return@LaunchedEffect
        initialPathConsumed = true
        val file = File(path)
        if (!file.isFile) recError = "找不到图片：$path" else runOne(file)
    }

    // 导入队列驱动：页面空闲（没在跑、没报错）且队列还有待跑页 → 自动开跑队首。
    // 覆盖两种时机：首次带队列进入本页；上一页终态被轮询循环清掉队首之后
    LaunchedEffect(queue, jobDocId, recError, starting) {
        if (engine == null || queue.isEmpty()) return@LaunchedEffect
        if (jobDocId == null && recError == null && !starting) {
            runOne(File(queue.first()))
        }
    }
    // 队列被接受后立刻通知 OcrApp 清掉 pending 副本（重进页面不会把同一批再跑一遍）
    LaunchedEffect(initialQueue) {
        if (initialQueue.isNotEmpty()) onQueueAccepted()
    }

    fun cancelJob() {
        val eng = engine ?: return
        val id = jobDocId ?: return
        val jid = jobJobId ?: return
        scope.launch { runCatching { eng.cancel(id, jid) } }
    }

    // Photo Picker 单选（系统选择器，任何版本都不需要存储权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { store.stageUri(context, uri, 0) }
                    .onSuccess { runOne(it) }
                    .onFailure { recError = it.message ?: "读不到这张图片" }
            }
        }
    }

    // ── 顺序修正：模型最容易出错的地方（reading_order 会把某一段排到别处）
    // customOrder = null 表示没动过，用模型给的顺序；否则是调整后的 id 序列。
    // 调好的顺序走 saveable：进程回收后清空等于让用户白调。
    //
    // 注意：**只改顺序，不碰框**。框的位置、大小、数量全部保持模型原样 ——
    // 框偏了是模型自身的问题，属于事实，界面不该悄悄"修好"它，只把它当定位提示用。
    var customOrder by rememberSaveable(stateSaver = CustomOrderSaver) {
        mutableStateOf<List<String>?>(null)
    }
    // 撤销栈：每次调序前压一份快照（上限 30）。撤销 = 弹回上一张，不用写反向操作。
    var history by rememberSaveable(stateSaver = OrderHistorySaver) {
        mutableStateOf<List<List<String>?>>(emptyList())
    }
    // 按住连发期间为 true：整串只压一个撤销点（否则按住 3 秒要按 30 次撤销）
    var holding by remember { mutableStateOf(false) }

    // 当前生效的序列。所有下游统一从这里取：播放、正文、复制、合并高亮。
    // 只有这一处排序逻辑，不会出现"正文是新序、复制还是旧序"。
    val blocksNow = remember(ordered, customOrder) {
        val ids = customOrder
        if (ids == null) {
            ordered
        } else {
            val byId = ordered.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }

    // 上下联动 + 区域合并：选中的是一组 id。连续点几个块 = 多选，原图按并集画一个大框
    // （LinkedHashSet 可序列化，直接进 saveable）
    var selectedIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    // 长按拖动时的临时范围（空 = 没在拖）。拖动中用它做预览，松手才提交
    var dragAnchor by remember { mutableStateOf<Int?>(null) }
    var dragRange by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(playKey, data, phase) {
        // 识别进行中不回放打字机：下半区此刻展示真实流式输出（liveMarkdown）。
        if (phase == StreamPhase.Recognizing) return@LaunchedEffect
        doneText = ""
        typing = ""
        activeIndex = -1
        selectedIds = emptySet()
        // 重播 = 用当前顺序重放一遍。**不清空 customOrder**：那是刚调好的东西，
        // 点个重播就丢了会挨骂。要回模型原序，按工具条上的「还原」。
        if (data == null) { replaying = false; return@LaunchedEffect }
        // 真实识别刚完成（playKey==0 = 本轮没按过重播）：正文在识别中已经流式放过一遍，
        // 直接展示终稿。此刻整屏清空重打要十几秒，观感像倒退；想看打字机按「重播」。
        if (phase == StreamPhase.Done && playKey == 0) {
            replaying = false
            return@LaunchedEffect
        }
        replaying = true

        var acc = ""
        blocksNow.forEachIndexed { idx, block ->
            activeIndex = idx
            val md = OcrStreamDemo.toMarkdown(block)
            if (block.isImage) {
                // 图片块不逐字：`![](stream/assets/p00` 这类半截语法只会闪一下纯文本
                typing = md
                delay(320)
            } else {
                var cut = 1
                // 每 3 个字符刷一次：逐字符刷是 80+ 次/秒的 evaluateJavascript，WebView 会掉帧
                while (cut <= md.length) {
                    typing = md.substring(0, cut)
                    cut += 3
                    delay(26)
                }
                typing = md
                delay(140)
            }
            // 已完成区域带锚点；正在打字的那个不带，否则标记会被逐字打出来
            val tagged = "@@blk:${block.id}@@\n$md"
            acc = if (acc.isEmpty()) tagged else "$acc\n\n$tagged"
            doneText = acc
            typing = ""
        }
        activeIndex = -1
        replaying = false
    }

    // 流式态的正文：轻量，每次重组算一遍即可
    val streamed = when {
        typing.isEmpty() -> doneText
        doneText.isEmpty() -> typing
        // 空锚点把"正在打字"这段从上一个区域里隔离出来，点它不会命中错区域
        else -> "$doneText\n\n@@blk:@@\n$typing"
    }

    // 识别中的实时流式正文：把引擎逐区域的部分输出（raw 是合法 UTF-8 但句子可能不完整）
    // 按生成顺序拼起来 —— render.html 的 balance() 会把半截公式补齐着渲，这就是
    // "边生成边渲染"。锚点 / 框联动要等终态的 DocumentIR（recPage）。
    val liveMarkdown = remember(liveRegions) {
        liveRegions.mapNotNull { r -> r.raw.trim().takeIf { it.isNotEmpty() } }
            .joinToString("\n\n")
    }

    // 终稿全文（带锚点、按当前生效顺序）：三个用途 —— 调序后立即重建（逐字累积的
    // doneText 是旧序拼的，改不了）；真实识别完成直接展示终稿（不整屏清空重打）；
    // 回放结束后的兜底。没调过序时它正好等于回放完成时的 doneText，行为不变。
    val fullMarkdown = remember(blocksNow) {
        blocksNow.joinToString("\n\n") { "@@blk:${it.id}@@\n${OcrStreamDemo.toMarkdown(it)}" }
    }
    // 下半区正文的三态选择（识别中的 liveShown 前缀在 StreamBodyPane 内算 ——
    // 那里才有逐帧推进的 liveShownLen）：这里只依赖 phase/replaying/customOrder
    // 这些低频离散状态，留在父级没有逐帧成本
    val finalMarkdown = when (phase) {
        StreamPhase.Done -> if (replaying) streamed else fullMarkdown
        else -> if (customOrder != null && !replaying) fullMarkdown else streamed
    }
    val activeBlock = blocksNow.getOrNull(activeIndex)

    // activeIndex 就是进度：它之前的块都已经"识别过"，已识别集合不用另存状态
    // （加一个 mutableList 记录"画过的框"等于两份数据要同步，迟早不一致）。
    val revealed = remember(blocksNow, activeIndex) {
        if (activeIndex >= 0) blocksNow.take(activeIndex + 1) else blocksNow
    }

    // 选中区域 = 复制它的内容并给一条轻提示；再点同一块取消选中（不复制）
    val clipboard = LocalClipboardManager.current
    var hintText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hintText) {
        if (hintText != null) {
            delay(1600)
            hintText = null
        }
    }

    // 导入队列全部跑完（queue 已清空、queueDone 有货）→ 落识别历史。
    // 与 runScan 端侧分支同一套落盘语义：合并=一条多页记录，否则每张一条；
    // OnDevicePage.file 用引擎规范化图 source.png（staged 临时文件可能已被清理）
    LaunchedEffect(queue, queueDone) {
        val done = queueDone
        if (engine == null || done.isEmpty() || queue.isNotEmpty()) return@LaunchedEffect
        queueDone = emptyList<String>() // 先清：防旋转重建后同一批重复落盘
        val eng = engine
        scope.launch {
            val outcomes = done.mapNotNull { eng.exportOutcome(it, queueModel) }
            if (outcomes.isEmpty()) {
                hintText = "识别结束，但没有可保存的文字结果"
                return@launch
            }
            val label = LocalOcrEngine.engineLabel(queueModel)
            val created = if (queueMergeMode) {
                listOf(
                    store.saveOnDevice(
                        title = OcrStore.titleFor(outcomes.size),
                        engine = label,
                        elapsedMs = outcomes.sumOf { it.elapsedMs },
                        pages = outcomes.map {
                            OnDevicePage(File(it.inputPath ?: ""), it.markdown, it.assetsDir)
                        },
                    ),
                )
            } else {
                outcomes.map {
                    store.saveOnDevice(
                        title = OcrStore.titleFor(1),
                        engine = label,
                        elapsedMs = it.elapsedMs,
                        pages = listOf(OnDevicePage(File(it.inputPath ?: ""), it.markdown, it.assetsDir)),
                    )
                }
            }
            onRecordsSaved()
            hintText = if (created.size == 1) "已存入识别历史" else "已存入 ${created.size} 条识别历史"
        }
    }

    fun copyBlocks(picked: List<OcrStreamBlock>) {
        val text = picked.filterNot { it.isImage }
            .joinToString("\n\n") { OcrStreamDemo.toMarkdown(it).trim() }
            .trim()
        if (text.isEmpty()) {
            hintText = "选中的区域都是图片，没有可复制的文本"
            return
        }
        clipboard.setText(AnnotatedString(text))
        hintText = if (picked.size == 1) {
            "已复制 ${picked[0].id} · ${text.length} 字"
        } else {
            "已合并 ${picked.size} 个区域 · ${text.length} 字"
        }
    }

    // 单击：只选这一个，再点同一个则取消。合并走长按拖动，两者不重合。
    fun applySingle(block: OcrStreamBlock) {
        if (selectedIds == setOf(block.id)) {
            selectedIds = emptySet()
            return
        }
        selectedIds = setOf(block.id)
        copyBlocks(listOf(block))
    }

    // 长按拖动松手：把起点到终点之间的所有块合成一个区域（按当前生效的顺序取区间）
    fun commitDrag(ids: Set<String>) {
        val picked = blocksNow.filter { it.id in ids }
        if (picked.isEmpty()) return
        selectedIds = picked.map { it.id }.toSet()
        copyBlocks(picked)
    }

    // ── 调序操作。统一"先压快照再改"，撤销就是弹回，不用为每个操作写反向逻辑
    fun pushOrder(next: List<String>) {
        // holding 期间（按住连发）不重复压栈：整串连续移动只留一个撤销点
        if (!holding) history = (history + customOrder).takeLast(30)
        customOrder = next
    }

    fun moveBlock(block: OcrStreamBlock, delta: Int) {
        val ids = blocksNow.map { it.id }.toMutableList()
        val from = ids.indexOf(block.id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.size - 1)
        if (to == from) {
            hintText = if (delta < 0) "${block.id} 已经在最前面" else "${block.id} 已经在最后面"
            return
        }
        ids.removeAt(from)
        ids.add(to, block.id)
        pushOrder(ids)
        hintText = "${block.id} 移到第 ${to + 1}/${ids.size} 位"
    }

    fun undoOrder() {
        if (history.isEmpty()) return
        customOrder = history.last()
        history = history.dropLast(1)
        hintText = "已撤销调序"
    }

    fun resetOrder() {
        if (customOrder == null) return
        history = (history + customOrder).takeLast(30)
        customOrder = null
        hintText = "已还原为模型原始顺序"
    }

    /**
     * 导出修正后的顺序：json（原序 + 新序对比）+ md（按新序拼好的全文），
     * 落到应用私有目录，同时把全文复制到剪贴板。
     * 写文件的具体逻辑在 data 层（OcrStreamExport），这里只负责提示。
     */
    fun exportOrder() {
        val page = data ?: return
        if (customOrder == null) {
            hintText = "顺序没改过，不用导出"
            return
        }
        runCatching { OcrStreamExport.saveOrder(context, page, ordered, blocksNow) }
            .onSuccess { r ->
                clipboard.setText(AnnotatedString(r.markdown))
                hintText = "已导出 ${r.jsonName} · 复制 ${r.markdown.length} 字"
            }
            .onFailure { hintText = "导出失败：${it.message}" }
    }

    // 四色分配：纯计算，一次算好；不要写成在 DrawScope 里现算
    val colorOf = remember(ordered) { assignBlockColors(ordered) }

    // 拖动中看 dragRange（预览），松手后看 selectedIds（已提交）
    val effectiveIds = if (dragRange.isNotEmpty()) dragRange else selectedIds

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        OcrTopBar(
            title = if (phase == StreamPhase.Demo) "流式渲染" else "流式识别渲染",
            subtitle = when (phase) {
                StreamPhase.Demo -> "区域 JSON → 逐字出公式与样图"
                StreamPhase.Recognizing -> "端侧识别中 · 边生成边渲染"
                StreamPhase.Done -> "识别完成 · 框/正文联动，可调序导出"
            },
            onBack = onBack,
        )

        // ── 真实识别入口行（仅 engine 非 null）：缩略图 + 文件名 + 选图 / 重跑 / 取消
        if (engine != null) {
            RecognitionEntryRow(
                imagePath = recImagePath,
                starting = starting,
                running = running,
                phase = phase,
                status = jobStatus,
                onPick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRerun = { recImagePath?.let { runOne(File(it)) } },
                onCancel = ::cancelJob,
            )
        }

        // ── 三种图源的异步解码：内置样本按相位懒加载（Demo 才解），输入图/规范化图走 IO
        // —— 规范化 source.png 才是 DocumentIR bbox 的坐标系基准。原写法 demoBitmap 在
        // 组合期全尺寸解码 source.jpg（无采样），统一成 produceState + IO。
        val demoBitmap by produceState<Bitmap?>(null, phase) {
            if (phase == StreamPhase.Demo) {
                value = decodeSampledAsset(context, OcrStreamDemo.SOURCE_IMAGE, 2048)
            }
        }
        val inputBitmap by produceState<Bitmap?>(null, recImagePath) {
            val p = recImagePath
            if (p != null) value = withContext(Dispatchers.IO) { decodeSampled(p, 2048) }
        }
        val frameBitmap by produceState<Bitmap?>(null, recFramePath) {
            val p = recFramePath
            if (p != null) value = withContext(Dispatchers.IO) { decodeSampled(p, 2048) }
        }
        val bitmap = when (phase) {
            StreamPhase.Done -> frameBitmap
            StreamPhase.Recognizing -> inputBitmap
            StreamPhase.Demo -> demoBitmap
        }

        // 下半区正文共用参数（宽屏/窄屏两处调用一致，抽出来省得漂移）
        val bodyAutoScroll = effectiveIds.isEmpty() || phase == StreamPhase.Recognizing
        val bodyHighlight = if (phase == StreamPhase.Recognizing) {
            emptyList()
        } else {
            blocksNow.filter { it.id in effectiveIds }.map { it.id }
        }
        val bodyOnTap: (String) -> Unit = if (phase == StreamPhase.Recognizing) {
            {}
        } else {
            { id ->
                Log.d("LinkDebug", "text tap -> $id")
                blocksNow.firstOrNull { it.id == id }?.let { applySingle(it) }
            }
        }

        // ── 主体：宽屏（横屏且非 Compact —— 与 OcrWindow 既定口径一致，分屏小窗不硬分栏）
        // 左图右文分栏；窄屏维持「上原图 / 中工具条 / 下正文」三段
        val wide = rememberOcrWindowInfo().layout == OcrLayoutMode.Split
        if (wide) {
            val splitState = rememberSplitPaneState()
            ResizableRow(
                state = splitState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                left = { m ->
                    StreamImagePane(
                        phase = phase,
                        running = running,
                        jobStatus = jobStatus,
                        data = data,
                        bitmap = bitmap,
                        blocksNow = blocksNow,
                        revealed = revealed,
                        colorOf = colorOf,
                        effectiveIds = effectiveIds,
                        activeBlock = activeBlock,
                        onBlankTap = { selectedIds = emptySet() },
                        onBlockTap = { block ->
                            Log.d("LinkDebug", "image tap -> ${block.id}")
                            applySingle(block)
                            onBlockClick(block)
                        },
                        onDragPreview = { dragRange = it },
                        onCommitDrag = { commitDrag(it) },
                        // m 携带固定宽度约束，必须真正接到内容根节点，否则高度塌陷
                        modifier = m
                            .padding(horizontal = Space.md, vertical = Space.xs)
                            .background(c.paper, RoundedCornerShape(Radii.md)),
                    )
                },
                right = { m ->
                    StreamBodyPane(
                        phase = phase,
                        running = running,
                        liveMarkdown = liveMarkdown,
                        finalMarkdown = finalMarkdown,
                        autoScrollBottom = bodyAutoScroll,
                        highlightBlockIds = bodyHighlight,
                        onBlockTap = bodyOnTap,
                        modifier = m
                            .padding(horizontal = Space.md, vertical = Space.xs)
                            .background(c.canvas, RoundedCornerShape(Radii.md)),
                    )
                },
            )
        } else {
            StreamImagePane(
                phase = phase,
                running = running,
                jobStatus = jobStatus,
                data = data,
                bitmap = bitmap,
                blocksNow = blocksNow,
                revealed = revealed,
                colorOf = colorOf,
                effectiveIds = effectiveIds,
                activeBlock = activeBlock,
                onBlankTap = { selectedIds = emptySet() },
                onBlockTap = { block ->
                    Log.d("LinkDebug", "image tap -> ${block.id}")
                    applySingle(block)
                    onBlockClick(block)
                },
                onDragPreview = { dragRange = it },
                onCommitDrag = { commitDrag(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Space.md, vertical = Space.xs)
                    .background(c.paper, RoundedCornerShape(Radii.md)),
            )
        }

        // ── 进度 / 调序工具条 + 重播
        // 三态：选中一段 -> 调序工具条；没选中但改过顺序 -> 撤销/还原/导出；都没有 -> 原进度行。
        // 改过顺序就必须常驻撤销入口，否则点一下空白工具条消失，再也回不到原顺序。
        val editing = blocksNow.firstOrNull { it.id in selectedIds }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            when {
                // 识别中：阶段徽标 + 区域进度 + 取消（调序/回放都等完成）
                phase == StreamPhase.Recognizing -> {
                    val s = jobStatus
                    Pill(
                        text = s?.let { stageChip(it) } ?: "启动中",
                        background = c.primary.copy(alpha = 0.12f),
                        color = c.primary,
                    )
                    if (s != null && s.regionTotal > 0) {
                        Text(
                            text = "区域 ${s.regionCompleted} / ${s.regionTotal}",
                            style = OcrType.Caption.copy(fontSize = 12.sp),
                            color = c.muted,
                        )
                    }
                }

                editing != null -> {
                    // 这里只改"这段排第几"，不动它的框。
                    // 用按钮而不是拖拽：下方正文在 WebView 里，拖动排序要穿透 JS 层；
                    // 而且上移/下移是离散操作，按钮不会误触，按住还能连发。
                    Text(
                        text = "${editing.id} ${blocksNow.indexOfFirst { it.id == editing.id } + 1}/${blocksNow.size}",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                    HoldKey(
                        label = "↑",
                        fg = c.ink,
                        bg = c.surfaceCard,
                        onStep = { moveBlock(editing, -1) },
                        onPressChange = { holding = it },
                    )
                    HoldKey(
                        label = "↓",
                        fg = c.ink,
                        bg = c.surfaceCard,
                        onStep = { moveBlock(editing, 1) },
                        onPressChange = { holding = it },
                    )
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoOrder() }
                    ActionKey(
                        "导出",
                        c.primary,
                        c.primary.copy(alpha = 0.12f),
                        enabled = customOrder != null,
                    ) { exportOrder() }
                }

                customOrder != null -> {
                    Text(
                        text = "顺序已调整",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                    ActionKey("撤销", c.muted, c.surfaceCard, enabled = history.isNotEmpty()) { undoOrder() }
                    ActionKey("还原", c.danger, c.surfaceCard) { resetOrder() }
                    ActionKey("导出", c.primary, c.primary.copy(alpha = 0.12f)) { exportOrder() }
                }

                else -> {
                    val progress = if (activeIndex >= 0) "${activeIndex + 1}/${blocksNow.size}" else "完成"
                    Pill(text = progress, background = c.surfaceCard, color = c.muted)
                    if (activeBlock != null) {
                        Text(
                            text = "${activeBlock.type} · ${activeBlock.id}",
                            style = OcrType.Caption.copy(fontSize = 12.sp),
                            color = c.mutedSoft,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // 识别中把右侧让给取消按钮（重播无意义：下半区是实时输出不是回放）
            if (phase == StreamPhase.Recognizing) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.danger.copy(alpha = 0.10f))
                        .clickable { cancelJob() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(text = "取消", style = OcrType.Caption, color = c.danger)
                }
            } else {
                // 胶囊自绘：公共的 Pill 是只读展示组件，加 onClick 会牵动其它调用点
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.primary.copy(alpha = 0.12f))
                        .clickable { playKey++ }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(text = "重播", style = OcrType.Caption, color = c.primary)
                }
            }
        }

        // ── 下半：流式 Markdown（复用结果页同一套 WebView + KaTeX）
        // 宽屏时已在 ResizableRow 右栏，这里只补窄屏形态
        if (!wide) {
            StreamBodyPane(
                phase = phase,
                running = running,
                liveMarkdown = liveMarkdown,
                finalMarkdown = finalMarkdown,
                autoScrollBottom = bodyAutoScroll,
                highlightBlockIds = bodyHighlight,
                onBlockTap = bodyOnTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.15f)
                    .padding(horizontal = Space.md, vertical = Space.xs)
                    .background(c.canvas, RoundedCornerShape(Radii.md)),
            )
        }

        Spacer(Modifier.height(Space.sm))
    }

        // 复制提示：轻量浮条，1.6s 自动消失。用自绘而不用系统 Toast：
        // Toast 在部分 ROM 上会被限流，而且样式脱离这套设计语言。
        AnimatedVisibility(
            visible = hintText != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Space.xl),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Text(
                text = hintText.orEmpty(),
                style = OcrType.Caption,
                color = c.canvas,
                modifier = Modifier
                    .background(c.ink.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                    .padding(horizontal = Space.md, vertical = Space.xs),
            )
        }

        // 识别错误走弹窗（模型未就绪 / 深链路径错误 / start 被拒都到这）
        recError?.let { message ->
            AlertDialog(
                onDismissRequest = { recError = null },
                containerColor = c.canvas,
                title = { Text("识别出了问题", style = OcrType.TitleMd, color = c.ink) },
                text = {
                    Text(
                        message,
                        style = OcrType.BodySm.copy(fontSize = 13.sp, lineHeight = 20.sp),
                        color = c.muted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { recError = null }) { Text("知道了", color = c.muted) }
                },
            )
        }
    }
}

/* ---------------------- 真实识别的三态与辅助 ---------------------- */

/**
 * 页面形态：
 *   Demo        —— 没发起过识别（或 engine 为 null）：内置样本 + 打字机回放，行为同旧版演示页
 *   Recognizing —— 引擎跑作业中：上半用户原图 + 进度浮层，下半实时流式 markdown
 *   Done        —— 终态且拿到 DocumentIR 区域页：框/联动/调序/导出/回放全挂在真实数据上
 */
private enum class StreamPhase { Demo, Recognizing, Done }

/** 阶段徽标文案：运行中用引擎原样 state（preparing/recognizing/saving），waiting 显示待开始。 */
private fun stageChip(s: LocalJobStatus): String = if (s.state == "waiting") "待开始" else s.state

/**
 * 调序结果的 Saver：List<String>? ↔ List<String>（空列表还原成 null=未调序）。
 * 区域 id 非空且不含 \u0001，joinToString 往返无损。
 */
private val CustomOrderSaver = Saver<List<String>?, List<String>>(
    save = { it.orEmpty() },
    restore = { saved -> if (saved.isEmpty()) null else saved },
)

/** 撤销栈的 Saver：List<List<String>?> ↔ List<List<String>>（空列表还原成 null 快照） */
private val OrderHistorySaver = Saver<List<List<String>?>, List<List<String>>>(
    save = { history -> history.map { order -> order.orEmpty() } },
    restore = { saved -> saved.map { order -> if (order.isEmpty()) null else order } },
)

/** 导入队列/已完成列表的 Saver：List<String> 本身就能进 Bundle，原样往返 */
private val StringListSaver = Saver<List<String>, List<String>>(
    save = { it },
    restore = { it },
)

/**
 * 识别入口行（仅 engine 非 null 的调用点显示）：小缩略图 + 文件名 + 动作。
 * 识别中给"取消"，其余状态给"选图 / 重新识别本图"。
 */
@Composable
private fun RecognitionEntryRow(
    imagePath: String?,
    starting: Boolean,
    running: Boolean,
    phase: StreamPhase,
    status: LocalJobStatus?,
    onPick: () -> Unit,
    onRerun: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Ocr.colors
    // 缩略图解码走 IO（与同文件大图同一纪律），未就绪时给「图」字占位。
    // 先读进局部值：thumb 是委托属性，Kotlin 不能对它 smart cast
    val thumb by produceState<Bitmap?>(null, imagePath) {
        val p = imagePath ?: return@produceState
        value = decodeSampled(p, 256)
    }
    val resolvedThumb = thumb
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Radii.sm))
                .background(c.creamStrong),
            contentAlignment = Alignment.Center,
        ) {
            if (resolvedThumb != null) {
                Image(
                    bitmap = resolvedThumb.asImageBitmap(),
                    contentDescription = "输入图缩略",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("图", style = OcrType.Caption.copy(fontSize = 11.sp), color = c.mutedSoft)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = imagePath?.let { File(it).name } ?: "选一张图，立即开始端侧识别",
                style = OcrType.BodySm.copy(fontSize = 13.sp),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val s = status
            if (phase == StreamPhase.Recognizing && s != null && s.message.isNotBlank()) {
                Text(
                    text = s.message,
                    style = OcrType.Caption.copy(fontSize = 11.sp),
                    color = c.mutedSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (running) {
            EntryChip(label = "取消", danger = true, onClick = onCancel)
        } else {
            EntryChip(label = if (starting) "启动中…" else "选图", enabled = !starting, onClick = onPick)
            EntryChip(
                label = "重新识别",
                enabled = imagePath != null && !starting,
                onClick = onRerun,
            )
        }
    }
}

/** 入口行的小胶囊按钮（30dp 高，比工具条的 ActionKey 更轻量）。 */
@Composable
private fun EntryChip(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Ocr.colors
    val fg = if (danger) c.danger else c.ink
    val bg = if (danger) c.danger.copy(alpha = 0.10f) else c.surfaceCard
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 12.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.35f),
        )
    }
}

/* ---------------------- 调序工具条的小按键 ---------------------- */

/**
 * 是否在区域框左上角画序号（1、2、3…）。
 *
 * **默认关闭**。这一页要看的是"文字顺序对不对"，序号会把本来就小的框糊满；
 * 只有在需要确认"正文第 3 段对应原图哪个框"、或者调顺序调花眼时，改成 true 重新编译即可。
 */
private const val SHOW_BLOCK_INDEX = false

/**
 * 按住可以连发的按键：点一下走一步，按住 400ms 后每 90ms 自动走一步，松手即停。
 *
 * 用 pointerInput + detectTapGestures(onPress) 而不是 combinedClickable：
 * onPress 是 suspend 块，能精确拿到"按下 / 松开"两端，长按阈值也能自己定。
 *
 * 两个必须注意的点：
 *   1. 回调要用 rememberUpdatedState 兜住 —— pointerInput(Unit) 不会因重组重启，
 *      直接捕获 onStep 会永远执行第一次组合时的旧闭包（里面全是旧状态）
 *   2. onPressChange(true) 必须排在第一次 onStep **之后**，否则第一步就不压撤销快照，
 *      按住连发一百步之后一步都撤不回来
 */
@Composable
private fun HoldKey(
    label: String,
    fg: Color,
    bg: Color,
    enabled: Boolean = true,
    onStep: () -> Unit,
    onPressChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val canRun by rememberUpdatedState(enabled)
    val pressChange by rememberUpdatedState(onPressChange)

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (canRun) {
                            step()
                            pressChange(true)
                            val repeat = scope.launch {
                                delay(400)          // 先留出"只想点一下"的余量
                                while (isActive) {
                                    step()
                                    delay(90)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                            pressChange(false)
                        } else {
                            tryAwaitRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 15.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.30f),
        )
    }
}

/** 文字动作键：宽度跟着文字走，比方向键宽一点，避免中文被挤成两行 */
@Composable
private fun ActionKey(
    label: String,
    fg: Color,
    bg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OcrType.Caption.copy(fontSize = 13.sp),
            color = if (enabled) fg else fg.copy(alpha = 0.35f),
        )
    }
}

/* ---------------------- 区域框的四色着色 ---------------------- */

/** 四色：主色打头，其余取和纸张背景、正文墨色都不撞的深色 */
private val BLOCK_COLORS = listOf(
    Color(0xFFCC785C), // 珊瑚橘（primary）
    Color(0xFF1D9E75), // teal
    Color(0xFFBA7517), // amber
    Color(0xFF534AB7), // purple
)

/**
 * 贪心四色着色。
 *
 * 邻接判据用「间距 < gap」而不是「bbox 相交」：
 * 试卷上的文本块上下紧挨、边界几乎重合（实测 b0003 底边 457、b0004 顶边 461，只差 4px），
 * 用相交判断会得到一张空邻接表 → 每块都拿到第一个颜色 → 四色等于没做。
 *
 * 四色定理保证平面图 4 色足够；贪心在这里够用，因为它只需要"相邻不同色"，不追求最优。
 */
private fun assignBlockColors(
    blocks: List<OcrStreamBlock>,
    gap: Float = 12f,
): Map<String, Color> {
    val adj = buildAdjacency(blocks, gap)
    val result = HashMap<String, Color>(blocks.size)
    blocks.forEach { block ->
        val used = adj[block.id].orEmpty().mapNotNull { result[it] }.toSet()
        result[block.id] = BLOCK_COLORS.firstOrNull { it !in used } ?: BLOCK_COLORS[0]
    }
    return result
}

private fun buildAdjacency(
    blocks: List<OcrStreamBlock>,
    gap: Float,
): Map<String, List<String>> {
    val adj = blocks.associate { it.id to mutableListOf<String>() }
    for (i in blocks.indices) {
        for (j in i + 1 until blocks.size) {
            if (touching(blocks[i].bbox, blocks[j].bbox, gap)) {
                adj[blocks[i].id]?.add(blocks[j].id)
                adj[blocks[j].id]?.add(blocks[i].id)
            }
        }
    }
    return adj
}

/** 两个矩形"外扩 gap 后是否相交"。gap 用原图坐标，1400×1812 的页面取 12 左右分组刚好 */
private fun touching(a: List<Int>, b: List<Int>, gap: Float): Boolean {
    if (a.size != 4 || b.size != 4) return false
    return !(a[2] + gap < b[0] || b[2] + gap < a[0] ||
        a[3] + gap < b[1] || b[3] + gap < a[1])
}

/** 屏幕坐标反算回原图坐标，返回命中的区域。Compose 不需要叠透明矩形做 hit-test。 */
private fun hitAt(
    list: List<OcrStreamBlock>,
    pos: Offset,
    dx: Float,
    dy: Float,
    scale: Float,
): OcrStreamBlock? {
    if (scale <= 0f) return null
    val ix = (pos.x - dx) / scale
    val iy = (pos.y - dy) / scale
    return list.firstOrNull { b ->
        b.bbox.size == 4 &&
            ix >= b.bbox[0] && ix <= b.bbox[2] &&
            iy >= b.bbox[1] && iy <= b.bbox[3]
    }
}

/* ---------------------- 主体两个 pane（宽屏分栏 / 窄屏共用） ---------------------- */

/**
 * 上半原图区：原图/区域框/高亮/选中与合并手势。从页面主体机械搬出，绘制逻辑不变；
 * 位图由父级异步解码后传入，宽屏时作为 ResizableRow 左栏、窄屏独立占一段，
 * modifier（含 background）由调用方给定。
 */
@Composable
private fun StreamImagePane(
    phase: StreamPhase,
    running: Boolean,
    jobStatus: LocalJobStatus?,
    data: OcrStreamPage?,
    bitmap: Bitmap?,
    blocksNow: List<OcrStreamBlock>,
    revealed: List<OcrStreamBlock>,
    colorOf: Map<String, Color>,
    effectiveIds: Set<String>,
    activeBlock: OcrStreamBlock?,
    /** 点到空白：取消选中 */
    onBlankTap: () -> Unit,
    /** 点中某个区域：单选 + 复制（父级负责联动正文与剪贴板） */
    onBlockTap: (OcrStreamBlock) -> Unit,
    /** 长按拖动中的预览区间变化 */
    onDragPreview: (Set<String>) -> Unit,
    /** 松手提交合并 */
    onCommitDrag: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ocr.colors
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // 序号测量器：只在 SHOW_BLOCK_INDEX 打开时才会被调用，默认不影响性能
        val indexMeasurer = rememberTextMeasurer()
        // 拖动手势的临时锚点/预览区间：只在手势内部使用，收进 pane（重建即丢，无害）
        var dragAnchor by remember { mutableStateOf<Int?>(null) }
        var previewRange by remember { mutableStateOf<Set<String>>(emptySet()) }

        if (phase == StreamPhase.Recognizing) {
            // 识别中：引擎还没给 bbox（框要等 export 的 DocumentIR），只放原图 + 进度浮层
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "输入图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(c.canvas.copy(alpha = 0.88f), RoundedCornerShape(Radii.md))
                    .padding(horizontal = Space.md, vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = c.primary)
                    Spacer(Modifier.height(Space.xs))
                }
                val s = jobStatus
                Text(
                    text = when {
                        s == null || s.state == "waiting" -> "准备中…"
                        s.regionTotal > 0 -> "${stageChip(s)} · 区域 ${s.regionCompleted}/${s.regionTotal}"
                        else -> stageChip(s)
                    },
                    style = OcrType.Caption.copy(fontSize = 12.sp),
                    color = c.ink,
                )
            }
        } else if (bitmap != null && data != null) {
            // ContentScale.Fit 的等效换算：取小的那个比例，再居中留边
            val scale = min(boxW / data.width, boxH / data.height)
            val drawW = data.width * scale
            val drawH = data.height * scale
            val dx = (boxW - drawW) / 2f
            val dy = (boxH - drawH) / 2f

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    // 命中判断：屏幕坐标反算回原图坐标。Compose 不需要叠一层透明矩形
                    // （那是 View 的 hit-test 思路），直接算落在哪个 bbox 里就行。
                    .pointerInput(scale, dx, dy, blocksNow) {
                        // 单击 = 单选（再点同一块取消）；点空白也取消
                        detectTapGestures { pos ->
                            val hit = hitAt(blocksNow, pos, dx, dy, scale)
                            if (hit == null) {
                                onBlankTap()
                            } else {
                                Log.d("LinkDebug", "image tap -> ${hit.id}")
                                onBlockTap(hit)
                            }
                        }
                    }
                    // 长按 + 拖动 = 范围合并，松手才生效。
                    // 必须是独立的 pointerInput：一个 pointerInput 里只能 await 一种手势，
                    // 两个探测器塞一起会互相抢事件（表现为长按后单击也触发）。
                    .pointerInput(scale, dx, dy, blocksNow) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                val start = hitAt(blocksNow, pos, dx, dy, scale)
                                dragAnchor = start?.let { blocksNow.indexOf(it) }
                                previewRange = start?.let { setOf(it.id) } ?: emptySet()
                                onDragPreview(previewRange)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val anchor = dragAnchor
                                val cur = hitAt(blocksNow, change.position, dx, dy, scale)
                                    ?.let { blocksNow.indexOf(it) }
                                if (anchor != null && cur != null) {
                                    val a = minOf(anchor, cur)
                                    val b = maxOf(anchor, cur)
                                    previewRange = blocksNow.subList(a, b + 1).map { it.id }.toSet()
                                    onDragPreview(previewRange)
                                }
                            },
                            onDragEnd = {
                                val ids = previewRange
                                dragAnchor = null
                                previewRange = emptySet()
                                onDragPreview(emptySet())
                                onCommitDrag(ids)
                            },
                            onDragCancel = {
                                dragAnchor = null
                                previewRange = emptySet()
                                onDragPreview(emptySet())
                            },
                        )
                    },
            ) {
                // 合并框：选中 >= 2 个时取并集（左上取最小、右下取最大），画一个虚线大框
                if (effectiveIds.size >= 2) {
                    val picked = blocksNow.filter { it.id in effectiveIds && it.bbox.size == 4 }
                    if (picked.isNotEmpty()) {
                        val x1 = picked.minOf { it.bbox[0] }
                        val y1 = picked.minOf { it.bbox[1] }
                        val x2 = picked.maxOf { it.bbox[2] }
                        val y2 = picked.maxOf { it.bbox[3] }
                        val tl = Offset(dx + x1 * scale, dy + y1 * scale)
                        val sz = Size((x2 - x1) * scale, (y2 - y1) * scale)
                        drawRect(color = c.primary.copy(alpha = 0.10f), topLeft = tl, size = sz, style = Fill)
                        drawRect(
                            color = c.primary,
                            topLeft = tl,
                            size = sz,
                            style = Stroke(
                                width = 2f.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                            ),
                        )
                    }
                }

                revealed.forEach { block ->
                    val b = block.bbox
                    if (b.size != 4) return@forEach
                    val isCurrent = block.id == activeBlock?.id
                    val isSelected = block.id in effectiveIds
                    drawRect(
                        // 已识别的用低透明度 + 细线：29 个框全用不透明 2dp 会把原图糊满
                        color = (colorOf[block.id] ?: c.primary)
                            .copy(alpha = if (isCurrent || isSelected) 1f else 0.42f),
                        topLeft = Offset(dx + b[0] * scale, dy + b[1] * scale),
                        size = Size(
                            ((b[2] - b[0]) * scale).coerceAtLeast(1f),
                            ((b[3] - b[1]) * scale).coerceAtLeast(1f),
                        ),
                        style = Stroke(width = when {
                            isSelected -> 3f
                            isCurrent -> 2f
                            else -> 1f
                        }.dp.toPx()),
                    )

                    // 序号：默认不画（SHOW_BLOCK_INDEX = false）。
                    // 只有需要"正文第几段对应原图哪个框"时才打开，避免小框被数字糊满。
                    if (SHOW_BLOCK_INDEX) {
                        val order = blocksNow.indexOfFirst { it.id == block.id } + 1
                        if (order > 0) {
                            val layout = indexMeasurer.measure(
                                text = order.toString(),
                                style = TextStyle(
                                    color = colorOf[block.id] ?: c.primary,
                                    fontSize = 9.sp,
                                ),
                            )
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    dx + b[0] * scale + 3f,
                                    (dy + b[1] * scale - layout.size.height).coerceAtLeast(0f),
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                text = if (data == null) "正在准备区域数据…" else "图加载失败",
                style = OcrType.Caption,
                color = c.muted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 下半正文区：识别中的平滑上屏与 Markdown 渲染都收在这里。
 * 单独成 pane 的原因：追赶循环每 26ms 推进一次 liveShownLen，旧写法这个 State 挂在
 * 页面根部、每次推进重组整页 —— 而真正变的只有下半 WebView 的文本。
 * 宽屏时作为 ResizableRow 右栏、窄屏独立占一段，modifier（含 background）由调用方给定。
 */
@Composable
private fun StreamBodyPane(
    phase: StreamPhase,
    running: Boolean,
    liveMarkdown: String,
    finalMarkdown: String,
    autoScrollBottom: Boolean,
    highlightBlockIds: List<String>,
    onBlockTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ocr.colors
    // 识别中"已经显示到第几个字"，由下面的追赶循环推进
    var liveShownLen by remember { mutableIntStateOf(0) }

    // ── 平滑上屏（追赶式打字机）：引擎每 400ms 轮询回来一批新字，直接整段上屏就是
    // "突然蹦出一坨"。liveShownLen 记录"已经显示到第几个字"，本循环每 26ms 推进一步去追
    // liveMarkdown.length：积压越多一步走得越远（约 1/12，对数式收敛，一大段约 1 秒铺完），
    // 快追上时一步只走 1 个字 —— 远看整段滑入，近看逐字打出，且永远追得上生成速度。
    LaunchedEffect(liveMarkdown, phase) {
        // 新一轮识别开始时 liveMarkdown 变空：清掉上一轮的进度（承接 runOne 的清屏语义）
        if (liveMarkdown.isEmpty()) {
            liveShownLen = 0
            return@LaunchedEffect
        }
        if (phase != StreamPhase.Recognizing) return@LaunchedEffect
        while (liveShownLen < liveMarkdown.length) {
            val remaining = liveMarkdown.length - liveShownLen
            liveShownLen += ((remaining + 11) / 12).coerceIn(1, 24)
            delay(26) // 与演示打字机同节奏：这是 WebView 刷得动的上限（见文件头注释）
        }
    }

    // 识别中实际喂 WebView 的是 liveMarkdown 的前 liveShownLen 个字（末尾若劈开了
    // 代理对字符就让一位，避免 emoji / 生僻字显示成乱码），结尾跟一个假光标 "▌"：
    // "正在打字"的观感由它和 26ms 步进一起给出来，终态自动消失。
    val liveShown = run {
        var end = liveShownLen.coerceAtMost(liveMarkdown.length)
        if (end in 1 until liveMarkdown.length && Character.isHighSurrogate(liveMarkdown[end - 1])) end--
        liveMarkdown.substring(0, end) + if (running) " ▌" else ""
    }
    val shown = if (phase == StreamPhase.Recognizing) liveShown else finalMarkdown

    Box(modifier) {
        OcrMarkdownView(
            markdown = shown,
            // 选中某块时关掉自动滚底，否则每帧 setMarkdown 都把视线拽回底部；
            // 识别中永远自动滚底 —— 实时输出就该跟着走
            autoScrollBottom = autoScrollBottom,
            highlightBlockIds = highlightBlockIds,
            onBlockTap = onBlockTap,
            modifier = Modifier.fillMaxSize().padding(Space.sm),
        )
        if (shown.isEmpty()) {
            Text(
                text = if (phase == StreamPhase.Recognizing) "正在等待第一个区域的输出…" else "正在准备区域数据…",
                style = OcrType.Caption,
                color = c.mutedSoft,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
/* ------------------------------------------------------------------ *
 * 预览组：纯演示模式（engine=null，不建引擎门面，Layoutlib 拿不到安全实例）。
 * WebView 区域在预览中只显示占位文案属正常。锁定竖排三段结构在宽屏下的观感。
 * ------------------------------------------------------------------ */
@Preview(name = "phone", showBackground = true, device = Devices.PHONE)
@Preview(name = "landscape", showBackground = true, widthDp = 800, heightDp = 380)
@Preview(name = "foldable", showBackground = true, device = Devices.FOLDABLE)
@Preview(name = "tablet", showBackground = true, device = Devices.TABLET)
@Composable
private fun OcrStreamScreenPreview() {
    CompositionLocalProvider(LocalOcrColors provides OcrColors.Light) {
        OcrStreamScreen(onBack = {})
    }
}
