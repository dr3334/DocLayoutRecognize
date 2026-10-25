package com.doclayout.recognize.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.local.CropGeometry
import com.doclayout.recognize.data.local.DocumentBoundary
import com.doclayout.recognize.data.local.UvdocModel
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.Pill
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot

/**
 * 纸张边界裁剪页（算法在 data 层 [DocumentBoundary] / [CropGeometry]）。
 *
 * 流程：进入即自动检测四角（失败回退全图四角并提示手动调整）→
 * 拖角微调 / 拖框内平移 / 重新检测 →「确认」全分辨率透视校正 → JPEG 落地回调。
 * 「不裁剪」以原图继续（截屏/电子件等无纸张边界的图走这条）。
 *
 * 交互对齐 suishou 的 web/document-crop.jsx（四角拖动 + 整框拖动），
 * 坐标换算复用流式渲染页的 ContentScale.Fit 公式。
 */
@Composable
fun OcrCropScreen(
    onBack: () -> Unit,
    /** 待裁剪图片的本地文件路径（已 stage 到应用可读位置） */
    imagePath: String,
    /** 第几张（1 起）——多选导入时逐张过裁剪 */
    index: Int,
    total: Int,
    /** 本张处理完成：croppedPath = 裁剪产物；null = 用户选了「不裁剪」，用原图 */
    onDone: (croppedPath: String?) -> Unit,
) {
    val c = Ocr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 底图：IO 解码到显示尺寸（长边 1440，看得清角点也不爆内存）
    val bitmap by produceState<Bitmap?>(null, imagePath) {
        value = withContext(Dispatchers.IO) { decodeScaled(imagePath, 1440) }
    }
    // 归一化四角 [左上,右上,右下,左下]
    var corners by remember(imagePath) { mutableStateOf<Array<DoubleArray>?>(null) }
    var hintText by remember(imagePath) { mutableStateOf<String?>(null) }
    var detecting by remember(imagePath) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var workingLabel by remember { mutableStateOf("") }

    // ── 卷曲矫正（UVDoc MNN，阶段 B）：独立于识别引擎的 8.3MB 模型，
    // 未下载不影响任何识别功能；裁剪页内联下载（SHA-256 校验在 UvdocModel）
    var uvdocOn by rememberSaveable { mutableStateOf(true) }
    var uvdocReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        uvdocReady = withContext(Dispatchers.IO) { UvdocModel.isDownloaded(context) }
    }
    var dlProgress by remember { mutableStateOf(-1f) } // -1 = 未在下载

    fun runDetect(bmp: Bitmap) {
        scope.launch {
            detecting = true
            val detected = withContext(Dispatchers.IO) { detectCorners(bmp) }
            corners = detected ?: CropGeometry.full()
            hintText = if (detected == null) "没找到纸张边缘，可手动拖动四角" else null
            detecting = false
        }
    }

    LaunchedEffect(bitmap) { bitmap?.let { runDetect(it) } }
    LaunchedEffect(hintText) {
        if (hintText != null) { delay(2200); hintText = null }
    }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        OcrTopBar(
            title = "调整纸张边界",
            subtitle = if (total > 1) "第 $index / $total 张 · 拖角微调，拖框内平移" else "拖角微调，拖框内平移",
            onBack = onBack,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Space.md, vertical = Space.xs),
        ) {
            val bmp = bitmap
            val cs = corners
            if (bmp != null && cs != null) {
                // ContentScale.Fit 等效换算（与流式页同式）：容器 Dp 转 px，
                // 手柄命中（pointerInput）与 Canvas 绘制都在 px 域
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val dx = (boxW - bmp.width * scale) / 2f
                val dy = (boxH - bmp.height * scale) / 2f
                val imgW = (bmp.width - 1) * scale
                val imgH = (bmp.height - 1) * scale
                fun toScreen(p: DoubleArray) = Offset(dx + p[0].toFloat() * imgW, dy + p[1].toFloat() * imgH)

                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "待裁剪原图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                // 拖拽：命中角（56px 热区）拖角，否则整框平移。
                // pointerInput 的 key 不含 corners —— 拖动更新四角会换数组，key 若含它
                // 每次重组都重启手势，拖到一半就断（sheetDrag 同源的坑）；当前值用
                // rememberUpdatedState 兜住读
                val currentCorners by rememberUpdatedState(cs)
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(scale, dx, dy, imgW, imgH) {
                            var mode = -1 // -1 无 / 0..3 拖第 n 角 / 4 整框
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { start ->
                                    var hit = -1
                                    var best = 56f
                                    for (i in 0 until 4) {
                                        val s = toScreen(currentCorners[i])
                                        val d = hypot((s.x - start.x).toDouble(), (s.y - start.y).toDouble()).toFloat()
                                        if (d < best) { best = d; hit = i }
                                    }
                                    mode = if (hit >= 0) hit else 4
                                    last = start
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val cur = change.position
                                    val delta = Offset(cur.x - last.x, cur.y - last.y)
                                    last = cur
                                    val src = currentCorners
                                    val next = when (mode) {
                                        in 0..3 -> Array(4) { i ->
                                            if (i == mode) doubleArrayOf(
                                                (src[i][0] + delta.x / imgW).coerceIn(0.0, 1.0),
                                                (src[i][1] + delta.y / imgH).coerceIn(0.0, 1.0),
                                            ) else src[i]
                                        }
                                        4 -> {
                                            // 平移量以「贴边即 clamp」收住，四角同向移动
                                            val dnx = delta.x / imgW
                                            val dny = delta.y / imgH
                                            Array(4) { i ->
                                                doubleArrayOf(
                                                    (src[i][0] + dnx).coerceIn(0.0, 1.0),
                                                    (src[i][1] + dny).coerceIn(0.0, 1.0),
                                                )
                                            }
                                        }
                                        else -> return@detectDragGestures
                                    }
                                    // 折叠/交叉在松手确认时由 CropGeometry 校验兜底
                                    corners = next
                                },
                                onDragEnd = { mode = -1 },
                                onDragCancel = { mode = -1 },
                            )
                        },
                ) {
                    val screen = cs.map { toScreen(it) }
                    val quad = Path().apply {
                        moveTo(screen[0].x, screen[0].y)
                        for (i in 1 until 4) lineTo(screen[i].x, screen[i].y)
                        close()
                    }
                    // 选区指示：框内微暗 + 白描边 + 角手柄（双圈）
                    drawPath(quad, c.ink.copy(alpha = 0.08f))
                    drawPath(quad, c.paper.copy(alpha = 0.95f), style = Stroke(width = 3.dp.toPx()))
                    screen.forEach { s ->
                        drawCircle(c.primary.copy(alpha = 0.30f), radius = 13.dp.toPx(), center = s)
                        drawCircle(c.paper, radius = 5.dp.toPx(), center = s)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Pill(
                        text = if (detecting) "正在检测纸张边缘…" else "加载图片…",
                        background = c.surfaceCard,
                        color = c.muted,
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
            if (working) {
                Text(text = workingLabel, style = OcrType.Caption.copy(fontSize = 12.sp), color = c.muted)
            } else {
                hintText?.let {
                    Text(text = it, style = OcrType.Caption.copy(fontSize = 12.sp), color = c.danger)
                }
            }
        }

        // 卷曲矫正开关行（三态：就绪开关 / 未下载可下载 / 下载中进度）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                dlProgress >= 0 -> "下载矫正模型中 ${(dlProgress * 100).toInt()}%"
                uvdocReady -> if (uvdocOn) "卷曲矫正 · 开" else "卷曲矫正 · 关"
                else -> "下载卷曲矫正模型（8.3MB）"
            }
            val active = dlProgress >= 0 || (uvdocReady && uvdocOn)
            ActionCapsule(
                text = label,
                fg = if (active) c.primary else c.muted,
                bg = if (active) c.primary.copy(alpha = 0.12f) else c.surfaceCard,
                enabled = !working && dlProgress < 0,
            ) {
                when {
                    // 下载中不可点；已就绪 → 切开关；未下载 → 触发下载
                    uvdocReady -> uvdocOn = !uvdocOn
                    else -> scope.launch {
                        dlProgress = 0f
                        runCatching { UvdocModel.download(context) { p -> dlProgress = p } }
                            .onSuccess { uvdocReady = true; uvdocOn = true }
                            .onFailure { hintText = it.message ?: "下载失败"; delay(2200); hintText = null }
                        dlProgress = -1f
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "展平卷曲书页（UVDoc）",
                style = OcrType.Caption.copy(fontSize = 11.sp),
                color = c.mutedSoft,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionCapsule("重新检测", c.ink, c.surfaceCard, enabled = !detecting && !working) {
                bitmap?.let { runDetect(it) }
            }
            ActionCapsule("不裁剪", c.muted, c.surfaceCard, enabled = !working) { onDone(null) }
            Spacer(Modifier.weight(1f))
            ActionCapsule(
                "确认裁剪",
                c.primary,
                c.primary.copy(alpha = 0.12f),
                enabled = !detecting && !working && corners != null,
            ) {
                val cs0 = corners ?: return@ActionCapsule
                working = true
                scope.launch {
                    workingLabel = "透视校正中…"
                    val out = withContext(Dispatchers.IO) { warpToJpeg(imagePath, cs0) }
                    if (out == null) {
                        working = false
                        hintText = "裁剪失败：几何校验未通过，请调整四角"
                    } else if (uvdocOn && uvdocReady) {
                        workingLabel = "卷曲矫正中…"
                        val uv = UvdocModel.unwarp(context, File(out))
                        working = false
                        // 矫正失败不阻断：回退用透视校正图
                        onDone(uv?.absolutePath ?: out)
                    } else {
                        working = false
                        onDone(out)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * IO helpers（纯函数）
 * ------------------------------------------------------------------ */

/** 解码并按长边限制降采样（检测/显示 1440；warp 4096，与引擎 normalize 同域） */
private fun decodeScaled(path: String, maxSide: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** 缩小灰度图（最长边 ≤960）上跑四角检测；OpenCV 未就绪/失败返回 null */
private fun detectCorners(bmp: Bitmap): Array<DoubleArray>? {
    if (!DocumentBoundary.ensureOpenCv()) return null
    val scale = 960f / maxOf(bmp.width, bmp.height)
    val small = if (scale < 1f) {
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
    } else bmp
    val rgba = Mat()
    val gray = Mat()
    return try {
        Utils.bitmapToMat(small, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        DocumentBoundary.detect(gray)
    } catch (e: Exception) {
        null
    } finally {
        rgba.release(); gray.release()
        if (small !== bmp) small.recycle()
    }
}

/** 全分辨率（≤4096）透视校正并落地 JPEG；几何不合法/OpenCV 未就绪返回 null */
private fun warpToJpeg(path: String, corners: Array<DoubleArray>): String? = runCatching {
    if (!DocumentBoundary.ensureOpenCv()) return null
    val bmp = decodeScaled(path, 4096) ?: return null
    val rgba = Mat(); val rgb = Mat(); var warped: Mat? = null
    val out: Bitmap
    try {
        Utils.bitmapToMat(bmp, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        warped = DocumentBoundary.warp(rgb, corners)
        out = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, out)
    } finally {
        rgba.release(); rgb.release()
    }
    warped?.release()
    val target = File.createTempFile("crop_", ".jpg", File(path).parentFile)
    target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    bmp.recycle(); out.recycle()
    target.absolutePath
}.getOrNull()

/** 胶囊按钮（自绘，风格与流式页工具条一致） */
@Composable
private fun ActionCapsule(
    text: String,
    fg: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Ocr.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = OcrType.Caption,
            color = if (enabled) fg else c.mutedSoft,
        )
    }
}
