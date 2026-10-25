package com.doclayout.recognize.ui.screen

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.local.LocalIrExport
import com.doclayout.recognize.data.local.LocalJobStatus
import com.doclayout.recognize.data.local.LocalOcrEngine
import com.doclayout.recognize.data.local.LocalStreamRegion
import com.doclayout.recognize.data.local.OcrStore
import com.doclayout.recognize.data.local.decodeSampled
import com.doclayout.recognize.data.model.OcrStreamPage
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.component.SettingCard
import com.doclayout.recognize.ui.icon.IconDoc
import com.doclayout.recognize.ui.layout.OcrLayoutMode
import com.doclayout.recognize.ui.layout.ResizableRow
import com.doclayout.recognize.ui.layout.rememberOcrWindowInfo
import com.doclayout.recognize.ui.layout.rememberSplitPaneState
import com.doclayout.recognize.ui.render.OcrMarkdownView
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/*
 * ─────────────────────────────────────────────────────────────
 * 流水识别测试页（用户指定的第一步验证载体，WP4）
 *
 * 验证目标只有一句话：端侧流水线「版面检测 → 逐区域生成 → export 产物」全程跑得通。
 *
 * 数据流（全部经 data/local/LocalOcrEngine 门面，本页不做任何直接 IO）：
 *   选图（Photo Picker）或深链 --es scanpath 直达
 *     → createInput(落盘记录并 autoStart) + start(兜底启动)
 *     → 每 400ms 轮询 status(id) 快照驱动重组
 *     → 阶段徽标 + region i/n + 逐区域 token 流式文本
 *     → 终态后 readExport(id) 读 document.json / document.md / run-manifest.json
 *     → 整页 Markdown（WebView+KaTeX）与区域框视图（OcrStreamPage）。
 *
 * 三个关键约定：
 *   1. 流式文本按 stream.revision 变化才更新 —— revision 相同的快照直接丢弃，
 *      不触发重组；raw 已由 native 层截断成合法 UTF-8，但句子可能不完整，
 *      前端原样显示、不做任何"补全句号"之类的假设。
 *   2. status 快照按 sequence 去重 —— 引擎没变就不写状态，避免每 400ms 白重组一次。
 *   3. 取消必须带 jobId（cancel(id, jobId)），jobId 从轮询的 status 快照里拿。
 * ─────────────────────────────────────────────────────────────
 */

/**
 * 流水识别测试页：深链 `--ei ocr 4`；首页「流水识别（测试）」临时入口也到这里。
 *
 * @param initialPath 深链 --es scanpath 直达的图片绝对路径（必须是本应用可读的位置，
 *                    如 run-as 推进 files/inbox/ 的文件）；null 表示等用户选图。
 * @param onOpenStream 跳到「流式渲染演示」页：那边用内置样本回放同一套 OcrStreamPage 模型，
 *                     两边可以互跳对照"真引擎产物"与"样本数据"的渲染效果。
 */
@Composable
fun OcrPipelineScreen(
    onBack: () -> Unit,
    engine: LocalOcrEngine,
    onOpenStream: () -> Unit = {},
    initialPath: String? = null,
) {
    val c = Ocr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 只为复用 stageUri（content Uri → cacheDir 文件）；记录仓库本体是引擎的 documents 目录
    val store = remember { OcrStore(context) }

    // ── 作业状态（imagePath/docId/jobId 走 saveable：重建后 LaunchedEffect(docId)
    //    重新轮询，终态自动 readExport 回填，识别会话 UI 继续跟进）
    var imagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var docId by rememberSaveable { mutableStateOf<String?>(null) }
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<LocalJobStatus?>(null) }
    // 流式快照：只在 stream.revision 变化时才写入（见轮询循环），驱动流文本区重组
    var regions by remember { mutableStateOf<List<LocalStreamRegion>>(emptyList()) }
    var export by remember { mutableStateOf<LocalIrExport?>(null) }
    var starting by remember { mutableStateOf(false) }

    // ── 展示状态
    // finishedTab 也 saveable：恢复后轮询只回填 export、不再碰 Tab（复位在 runOne），
    // 「运行中/终态恢复」都保得住这个选择
    var finishedTab by rememberSaveable { mutableIntStateOf(0) } // 0=Markdown 1=区域框 2=流式记录
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    val running = status?.running == true

    fun runOne(file: File) {
        scope.launch {
            starting = true
            error = null
            try {
                // 端态门槛与 OcrApp.runScan 同源：先磁盘对账（回写引擎内存 readiness），
                // 模型没就绪就明说，不让用户等 createInput 之后才收到晦涩的报错。
                val ready = engine.refreshReadiness()
                if (!ready.filesReady) {
                    error = "模型还没就绪（${ready.state}）：${ready.message}\n去 设置 → 管理端侧模型 处理"
                    return@launch
                }
                // 新一轮：清掉上一轮的展示状态（Tab 复位在这里做 —— 轮询循环里
                // 不再无条件 finishedTab = 0，否则 saveable 恢复的 Tab 会被
                // 恢复后第一次轮询到终态时覆盖回 0）
                imagePath = file.absolutePath
                docId = null; jobId = null; status = null
                regions = emptyList(); export = null
                finishedTab = 0
                // createInput 落盘记录并 autoStart（模型就绪时直接开跑）；再显式 start 一次
                // 兜住"autoStart 只落盘没启动"的分支。start 对运行中的同记录作业是幂等返回。
                val summary = engine.createInput(title = OcrStore.titleFor(1), image = file)
                docId = summary.id
                runCatching { engine.start(summary.id, again = false) }
            } catch (e: Exception) {
                error = e.message ?: "启动流水线失败"
            } finally {
                starting = false
            }
        }
    }

    // 状态轮询：LaunchedEffect(docId) 里的 while 循环，页面离开组合自动取消。
    // 400ms 在 300-500ms 的要求区间内：比引擎回调（状态监听）慢，但足够顺滑且省电。
    LaunchedEffect(docId) {
        val id = docId ?: return@LaunchedEffect
        var lastSequence = -1L
        var lastRevision = -1L
        while (isActive) {
            val s = runCatching { engine.status(id) }.getOrNull()
            if (s != null) {
                // sequence 没变就不写状态：引擎无进展时这里每 400ms 重复一次读，但零重组
                if (s.sequence != lastSequence) {
                    lastSequence = s.sequence
                    status = s
                    // jobId 只有运行快照里才有（start 返回的 doc JSON 不带），取消要用
                    if (s.jobId.isNotBlank()) jobId = s.jobId
                }
                // 流式文本按 stream.revision 变化才更新；raw 是合法 UTF-8 但可能不完整，
                // 这里原样交给 Text 显示，不做补全/截断。
                val rev = s.stream?.revision ?: 0L
                if (rev != lastRevision) {
                    lastRevision = rev
                    regions = s.stream?.regions.orEmpty()
                }
                if (s.terminal) {
                    // 只回填 export，不动 finishedTab：Tab 是用户的选择（saveable），
                    // 「新一轮默认落整页 Markdown」的产品语义由 runOne 的复位保证
                    export = runCatching { engine.readExport(id) }.getOrNull()
                    break
                }
            }
            delay(400)
        }
    }

    // 深链直达：--ei ocr 4 --es scanpath <应用可读路径>。路径必须配合 ocr 4 使用，
    // 因为只有 Pipeline 页组合时这段才会跑（OcrApp 没实现 onNewIntent，深链前先 force-stop）。
    // 消费标志必须 saveable：旋转重建后 intent extra 原样重投，不挡住就会重复 createInput
    var initialPathConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPath) {
        val path = initialPath
        if (path == null || initialPathConsumed) return@LaunchedEffect
        initialPathConsumed = true
        val file = File(path)
        if (!file.isFile) {
            error = "找不到调试图片：$path"
        } else {
            runOne(file)
        }
    }

    fun cancelJob() {
        val id = docId ?: return
        val jid = jobId
        if (jid.isNullOrBlank()) {
            hint = "还没拿到作业号（引擎还在准备），稍后再取消"
            return
        }
        scope.launch {
            runCatching { engine.cancel(id, jid) }
                .onFailure { hint = "取消失败：${it.message}" }
        }
    }

    // Photo Picker 单选（系统选择器，任何版本都不需要存储权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { store.stageUri(context, uri, 0) }
                    .onSuccess { runOne(it) }
                    .onFailure { error = it.message ?: "读不到这张图片" }
            }
        }
    }

    fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            OcrTopBar(
                title = "流水识别（测试）",
                subtitle = "端侧 PP-DocLayoutV3 → GLM-OCR 逐区域流式",
                onBack = onBack,
            )

            // ── 输入卡：当前图 + 动作按钮
            // SettingCard 没有 modifier 参数（设计系统组件都是通栏的），水平边距由外层 Box 出
            Box(Modifier.padding(horizontal = Space.lg)) {
                SettingCard {
                    Column(Modifier.fillMaxWidth().padding(Space.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(Radii.md))
                                .background(c.creamStrong)
                                .border(1.dp, c.hairline, RoundedCornerShape(Radii.md)),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 缩略图解码走 IO（与下方大图同一纪律）；未就绪时 IconDoc 占位自然显示。
                            // 先读进局部值：thumb 是委托属性，Kotlin 不能对它 smart cast
                            val thumb by produceState<Bitmap?>(null, imagePath) {
                                val path = imagePath ?: return@produceState
                                value = decodeSampled(path, 256)
                            }
                            val resolvedThumb = thumb
                            if (resolvedThumb != null) {
                                Image(
                                    bitmap = resolvedThumb.asImageBitmap(),
                                    contentDescription = "输入图缩略",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                IconDoc(c.muted, size = 22.dp)
                            }
                        }
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = imagePath?.let { File(it).name } ?: "还没有选图",
                                style = OcrType.BodySm,
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = docId?.let { "记录 $it · filesDir/documents/$it" }
                                    ?: "选一张试卷图，立即开始端侧流水线",
                                style = OcrType.Caption.copy(fontSize = 11.5.sp),
                                color = c.mutedSoft,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SmallChip(label = "选图", onClick = ::launchPicker, enabled = !starting && !running)
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (running) {
                            SmallChip(label = "取消识别", danger = true, onClick = ::cancelJob)
                        } else {
                            SmallChip(
                                label = if (starting) "启动中…" else "重新识别本图",
                                enabled = imagePath != null && !starting,
                                onClick = { imagePath?.let { runOne(File(it)) } },
                            )
                        }
                        SmallChip(label = "流式演示对照", onClick = onOpenStream)
                    }
                    }
                }
            }

            // ── 进度行：阶段徽标 + 区域 i/n + 引擎消息
            val s = status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Pill(
                    text = stageLabel(s),
                    background = when {
                        s == null || s.state == "waiting" -> c.surfaceCard
                        s.terminal -> if (s.state == "succeeded" || s.state == "partial") c.surfaceCard else c.danger.copy(alpha = 0.10f)
                        else -> c.primary.copy(alpha = 0.12f)
                    },
                    color = when {
                        s == null || s.state == "waiting" -> c.muted
                        s.terminal -> if (s.state == "succeeded" || s.state == "partial") c.success else c.danger
                        else -> c.primary
                    },
                )
                if (s != null && s.regionTotal > 0) {
                    Text(
                        text = "区域 ${s.regionCompleted} / ${s.regionTotal}",
                        style = OcrType.Caption.copy(fontSize = 12.sp),
                        color = c.muted,
                    )
                }
                if (s != null && s.message.isNotBlank()) {
                    Text(
                        text = s.message,
                        style = OcrType.Caption.copy(fontSize = 11.5.sp),
                        color = c.mutedSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (running && s != null) {
                // regionTotal=0（版面还没出数）时用不确定态转圈，出数后切比例条
                if (s.regionTotal > 0) {
                    val frac = (s.regionCompleted.toFloat() / s.regionTotal).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.lg)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = c.primary,
                        trackColor = c.creamStrong,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.lg)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = c.primary,
                        trackColor = c.creamStrong,
                    )
                }
            }

            // ── 完成后的视图切换
            if (s?.terminal == true) {
                Spacer(Modifier.height(Space.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    SmallChip(label = "整页 Markdown", accent = finishedTab == 0) { finishedTab = 0 }
                    SmallChip(label = "区域框", accent = finishedTab == 1) { finishedTab = 1 }
                    SmallChip(label = "流式记录", accent = finishedTab == 2) { finishedTab = 2 }
                }
            }

            // ── 主体
            // 横屏/平板且识别完成：左区域框右正文分栏（与结果页同一套 ResizableRow）。
            // wide 口径与项目既定标准一致（OcrWindow.layout == Split）：横屏但分屏小窗
            // 只有 520dp 宽还硬分栏，两边都不可用。运行中主体只有流式文本列表，
            // 横屏本身可读，维持上下结构。finishedTab==1（纯区域框）也维持全宽。
            // split 状态无条件 remember：翻 Tab 不丢分栏比例
            val splitState = rememberSplitPaneState()
            val wide = rememberOcrWindowInfo().layout == OcrLayoutMode.Split
            if (s?.terminal == true && wide && finishedTab != 1) {
                ResizableRow(
                    state = splitState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.xs),
                    left = { m ->
                        // m 必须真正传进视图根节点：ResizableRow 的测量阶段把固定宽度
                        // 写在这个 Modifier 上，不透传会让内容不消费约束、高度塌陷
                        RegionOverlayView(
                            imagePath = export?.inputPath ?: imagePath,
                            page = export?.page,
                            modifier = m,
                        )
                    },
                    right = { m ->
                        if (finishedTab == 2) {
                            RegionStreamList(regions = regions, modifier = m)
                        } else {
                            ExportMarkdownView(export = export, modifier = m)
                        }
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.xs),
                ) {
                    when {
                        s == null && regions.isEmpty() -> EmptyGuide(starting = starting)
                        s?.terminal == true && finishedTab == 1 -> RegionOverlayView(
                            imagePath = export?.inputPath ?: imagePath,
                            page = export?.page,
                            modifier = Modifier.fillMaxSize(),
                        )
                        s?.terminal == true && finishedTab == 2 -> RegionStreamList(
                            regions = regions,
                            modifier = Modifier.fillMaxSize(),
                        )
                        s?.terminal == true -> ExportMarkdownView(
                            export = export,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> RegionStreamList(regions = regions, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        // ── 轻提示浮条（同 OcrModelScreen / OcrStreamScreen 的样式）
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

        // 错误走弹窗（信息量比浮条大：模型未就绪 / 深链路径错误 / start 被拒都到这）
        error?.let { message ->
            AlertDialog(
                onDismissRequest = { error = null },
                containerColor = c.canvas,
                title = { Text("流水识别出了问题", style = OcrType.TitleMd, color = c.ink) },
                text = {
                    Text(
                        message,
                        style = OcrType.BodySm.copy(fontSize = 13.sp, lineHeight = 20.sp),
                        color = c.muted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { error = null }) { Text("知道了", color = c.muted) }
                },
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 * 主体三个视图
 * ------------------------------------------------------------------ */

/** 空闲引导：没选图、还没开始时的占位说明 */
@Composable
private fun EmptyGuide(starting: Boolean) {
    val c = Ocr.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (starting) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = c.primary)
                Spacer(Modifier.height(Space.sm))
                Text("正在创建记录并启动流水线…", style = OcrType.Caption, color = c.muted)
            } else {
                Text(
                    text = "选一张图（或用深链 --es scanpath 直达）",
                    style = OcrType.BodySm,
                    color = c.muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "端侧流水线：版面检测 → 按区域裁剪 → GLM-OCR 逐区域流式生成",
                    style = OcrType.Caption.copy(fontSize = 11.5.sp),
                    color = c.mutedSoft,
                )
            }
        }
    }
}

/**
 * 逐区域 token 流式文本列表（运行中与完成后"流式记录"Tab 共用）。
 *
 * raw 显示纪律：原样渲染，不补句号、不截断 —— native 层已保证是合法 UTF-8，
 * 但句子可能中途被快照，这本身就是流式的样子。
 */
@Composable
private fun RegionStreamList(regions: List<LocalStreamRegion>, modifier: Modifier = Modifier) {
    val c = Ocr.colors
    if (regions.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("等待区域输出…", style = OcrType.Caption, color = c.mutedSoft)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // key 用 requestId#attempt（稳定字符串 + 重试区分）：流式期间列表整替时
        // 按位置 diff 会让区域插入/重排之后的所有行全部重建
        itemsIndexed(
            regions,
            key = { _, r -> "${r.requestId}#${r.attempt}" },
            contentType = { _, _ -> "region" },
        ) { index, region ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(c.paper)
                    .border(1.dp, c.hairline, RoundedCornerShape(Radii.md))
                    .padding(horizontal = Space.sm, vertical = Space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        text = "${index + 1} · ${region.type.ifBlank { "region" }}",
                        style = OcrType.Caption.copy(fontSize = 11.5.sp),
                        color = c.muted,
                        modifier = Modifier.weight(1f),
                    )
                    if (region.attempt > 1) {
                        Pill(text = "重试 ${region.attempt}", background = c.surfaceCard, color = c.amber)
                    }
                    Pill(
                        text = if (region.done) "完成" else "生成中…",
                        background = if (region.done) c.surfaceCard else c.primary.copy(alpha = 0.12f),
                        color = if (region.done) c.success else c.primary,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = region.raw.ifBlank { "（尚无输出）" },
                    style = OcrType.Mono.copy(fontSize = 11.5.sp, lineHeight = 17.sp),
                    color = c.ink,
                )
            }
        }
    }
}

/**
 * 整页 Markdown 视图：document.md 走结果页同款 WebView + KaTeX 渲染，
 * 顶部一条产物摘要（document.json 元信息 + run-manifest 键值，可点开/收起）。
 */
@Composable
private fun ExportMarkdownView(export: LocalIrExport?, modifier: Modifier = Modifier) {
    val c = Ocr.colors
    var showManifest by remember { mutableStateOf(false) }
    Column(modifier) {
        if (export != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(c.surfaceCard)
                    .clickable { showManifest = !showManifest }
                    .padding(horizontal = Space.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "产物 document.json · schema ${export.schemaVersion.ifBlank { "?" }} · " +
                        "${export.pageCount} 页 · run-manifest ${export.manifestSummary.size} 键" +
                        if (showManifest) "（点收起）" else "（点看详情）",
                    style = OcrType.Caption.copy(fontSize = 11.5.sp),
                    color = c.mutedSoft,
                )
            }
            if (showManifest) {
                export.manifestSummary.forEach { (key, value) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = 1.dp)) {
                        Text(
                            text = key,
                            style = OcrType.Mono.copy(fontSize = 10.5.sp),
                            color = c.muted,
                            modifier = Modifier.width(132.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = value,
                            style = OcrType.Mono.copy(fontSize = 10.5.sp),
                            color = c.mutedSoft,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
        }
        val markdown = export?.markdown
        when {
            markdown.isNullOrBlank() -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (export == null) "还没有产物：识别成功后这里展示 document.md"
                    else "产物里没有 document.md（空白页或导出未完成）",
                    style = OcrType.Caption,
                    color = c.mutedSoft,
                )
            }
            else -> OcrMarkdownView(
                markdown = markdown,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(c.canvas, RoundedCornerShape(Radii.md)),
            )
        }
    }
}

/**
 * 区域框视图：输入图 + DocumentIR 的 bbox 矩形（复用 OcrStreamPage 数据模型）。
 *
 * 坐标系注意：bbox 是引擎**规范化输入图**（记录目录 source.png）的栅格坐标，
 * 所以优先用 export.inputPath（规范化图），没有才退回用户选的原图 ——
 * 用原图时如果分辨率策略改过尺寸，框会有整体缩放偏差，属已知取舍。
 *
 * 画法与 OcrStreamScreen 相同（ContentScale.Fit 等效换算 + Canvas 描边），
 * 但不搬它的调序/合并交互 —— 测试页只要"框在哪、齐不齐"。
 */
@Composable
private fun RegionOverlayView(
    imagePath: String?,
    page: OcrStreamPage?,
    modifier: Modifier = Modifier,
) {
    val c = Ocr.colors
    BoxWithConstraints(modifier.background(c.paper, RoundedCornerShape(Radii.md))) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // 大图解码是磁盘 IO：produceState + Dispatchers.IO，不在组合里同步做。
        // 写法说明：必须「判空提前返回 + value = <调用> 顶层直赋」——Compose lint 的
        // ProduceStateDoesNotAssignValue 有已知误报（issuetracker 349411310/376491756），
        // `value = x?.let{…}` 与 if 表达式赋值都识别不了，别改回那两种写法
        val bitmap by produceState<Bitmap?>(null, imagePath) {
            val path = imagePath
            if (path == null) return@produceState
            value = withContext(Dispatchers.IO) { decodeSampled(path, 2048) }
        }
        when {
            page == null -> Text(
                text = "document.json 里没有区域数据",
                style = OcrType.Caption,
                color = c.mutedSoft,
                modifier = Modifier.align(Alignment.Center),
            )
            bitmap == null -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp).align(Alignment.Center),
                strokeWidth = 2.dp,
                color = c.primary,
            )
            else -> {
                val bmp = bitmap!!
                val scale = min(boxW / page.width, boxH / page.height)
                val drawW = page.width * scale
                val drawH = page.height * scale
                val dx = (boxW - drawW) / 2f
                val dy = (boxH - drawH) / 2f
                Box(
                    modifier = Modifier
                        .size(with(density) { drawW.toDp() }, with(density) { drawH.toDp() })
                        .align(Alignment.Center),
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "规范化输入图",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(Modifier.matchParentSize()) {
                        page.ordered.forEach { block ->
                            val b = block.bbox
                            if (b.size != 4) return@forEach
                            val topLeft = Offset(dx + b[0] * scale, dy + b[1] * scale)
                            val size = Size(
                                ((b[2] - b[0]) * scale).coerceAtLeast(1f),
                                ((b[3] - b[1]) * scale).coerceAtLeast(1f),
                            )
                            drawRect(color = c.primary.copy(alpha = 0.10f), topLeft = topLeft, size = size, style = Fill)
                            drawRect(
                                color = c.primary,
                                topLeft = topLeft,
                                size = size,
                                style = Stroke(
                                    width = 1.5f.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 小工具
 * ------------------------------------------------------------------ */

/**
 * 小号动作按钮（与 OcrModelScreen.ChipButton 同款，34dp 胶囊）。
 * 两页各留一份私有副本而不是提到公共组件：名字相同、语义不同
 * （那边挂 SettingCard 动作行，这边挂输入卡/Tab 行），合并只会造出第二层参数。
 */
@Composable
private fun SmallChip(
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
        danger -> c.danger.copy(alpha = 0.10f)
        accent -> c.primary.copy(alpha = 0.12f)
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

/** 阶段徽标文案：运行中用引擎原样 state（preparing/recognizing/saving/cancelling），终态给具体值。 */
private fun stageLabel(s: LocalJobStatus?): String = when {
    s == null -> "待开始"
    s.state == "waiting" -> "待开始"
    else -> s.state
}
