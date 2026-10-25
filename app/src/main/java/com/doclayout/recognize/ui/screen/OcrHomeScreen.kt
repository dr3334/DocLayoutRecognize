package com.doclayout.recognize.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import com.doclayout.recognize.ui.icon.IconChevronRight
import com.doclayout.recognize.ui.theme.Radii
import com.doclayout.recognize.ui.component.CircleIconButton
import com.doclayout.recognize.data.model.OcrRecord
import com.doclayout.recognize.ui.component.ActionCard
import com.doclayout.recognize.ui.component.Dot
import com.doclayout.recognize.ui.component.EmptyRecords
import com.doclayout.recognize.ui.component.OcrTopBar
import com.doclayout.recognize.ui.component.RecordCard
import com.doclayout.recognize.ui.component.SectionHeader
import com.doclayout.recognize.ui.icon.IconCamera
import com.doclayout.recognize.ui.icon.IconGallery
import com.doclayout.recognize.ui.icon.IconSliders
import com.doclayout.recognize.ui.theme.Ocr
import com.doclayout.recognize.ui.theme.OcrType
import com.doclayout.recognize.ui.theme.Space
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doclayout.recognize.data.sample.sampleRecords
import com.doclayout.recognize.ui.theme.LocalOcrColors
import com.doclayout.recognize.ui.theme.OcrColors

/**
 * 首页。
 *
 * 版式节奏（DESIGN.md 的"区块节奏"）：奶油画布 → 一块珊瑚全幅卡 → 奶油卡 → 深色看图页。
 * 这里只有前两段：两块并排主操作卡（一块珊瑚、一块奶油）承担视觉焦点，
 * 下面"最近识别"用横向卡片，最后一行页脚用弱化色收尾。
 *
 * 空状态不是"另一个页面"：数据为空就把最近记录区换成插画 + 引导文案，
 * 主操作区一动不动 —— 用户永远能立刻拍照，这是首页唯一不能消失的东西。
 */
@Composable
fun OcrHomeScreen(
    records: List<OcrRecord>,
    onOpenSettings: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onImportPdf: () -> Unit = {},
    onOpenRecord: (OcrRecord) -> Unit,
    onOpenStream: () -> Unit,
    onOpenStreamRec: () -> Unit = {},
    onOpenPipeline: () -> Unit = {},
) {
    val c = Ocr.colors
    // "查看全部"是否展开：saveable，进程回收/重建后不丢
    var showAll by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        OcrTopBar(
            title = "试卷识别",
            subtitle = "纸质试卷 → 结构化 Markdown",
            mark = true,
            action = {
                CircleIconButton(onClick = onOpenSettings) {
                    IconSliders(c.ink, size = 18.dp)
                }
            },
        )

        // BoxWithConstraints 只为读可视高度：手机横屏可视高约 340-390dp，
        // 内容约 640dp 且 Column 不可滚动，weight 子项会全部归零 —— 矮屏切换滚动兜底
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            // 矮屏判定：低于 560dp（横屏手机/分屏小窗）就走滚动 + 固定高度
            val short = maxHeight < 560.dp
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .widthIn(max = Space.contentMax)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 矮屏时整列可滚：verticalScroll 需要限高，这里外层 weight(1f) 已给定高度
                    .then(if (short) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(horizontal = Space.lg),
            ) {
                Spacer(Modifier.height(Space.xs))

                // ── 主操作区：两块并排，占上半部（矮屏压到 160dp，常规 198dp 不变）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    ActionCard(
                        label = "拍照识别",
                        hint = "自动找边 · 校正透视",
                        primary = true,
                        onClick = onCapture,
                        modifier = Modifier.weight(1f),
                        height = if (short) 160.dp else 198.dp,
                    ) { fg -> IconCamera(fg, size = 22.dp) }
                    ActionCard(
                        label = "相册导入",
                        // Photo Picker 只认图片；PDF 走下面的 PdfEntry（OpenDocument）——
                        // WP5 起整卷 PDF 试卷真的可以导入了，但不是从这个入口
                        hint = "支持 JPG / PNG",
                        primary = false,
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                        height = if (short) 160.dp else 198.dp,
                    ) { fg -> IconGallery(fg, size = 22.dp) }
                }

                Spacer(Modifier.height(14.dp))

                // PDF 试卷导入（WP5）：一份 PDF = 一份试卷的多页，逐页栅格化后整卷合并
                PdfEntry(onClick = onImportPdf)

                Spacer(Modifier.height(Space.md))

                // 端侧流水线测试入口（WP4）：正式分流（runScan 端侧分支）已在 WP5 接好，
                // 这行保留为独立验证工具——单图快速重跑、看逐区域流式输出与 export 产物
                // 流式识别渲染：流式渲染演示页的主体 + 真实端侧识别（选图后边生成边渲染，
                // 完成后区域框/调序/导出全可用）。放三行入口最上面 —— 它是唯一"真识别"的那行，
                // 流水识别（测试）保留为调试对照，流式渲染演示是样本回放。
                StreamRecEntry(onClick = onOpenStreamRec)
                Spacer(Modifier.height(Space.sm))

                PipelineEntry(onClick = onOpenPipeline)
                Spacer(Modifier.height(Space.sm))

                StreamEntry(onClick = onOpenStream)
                Spacer(Modifier.height(Space.md))

                if (records.isEmpty()) {
                    // ── 空状态：主操作不动，只在下面给出引导（矮屏给固定高，配合外层滚动）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (short) Modifier.height(240.dp) else Modifier.weight(1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyRecords()
                    }
                } else {
                    SectionHeader(
                        title = if (showAll) "全部记录" else "最近识别",
                        actionText = if (showAll) "收起" else "查看全部",
                        onAction = { showAll = !showAll },
                    )
                    Spacer(Modifier.height(Space.sm))

                    if (showAll) {
                        LazyVerticalGrid(
                            // Adaptive：手机竖屏仍约两列，横屏/平板自动加列
                            columns = GridCells.Adaptive(180.dp),
                            // LazyVerticalGrid 在滚动容器内必须限高（否则无限高测量直接崩）
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (short) Modifier.height(360.dp) else Modifier.weight(1f)),
                            verticalArrangement = Arrangement.spacedBy(Space.md),
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            contentPadding = PaddingValues(bottom = Space.md),
                        ) {
                            items(records, key = { it.id }, contentType = { "record" }) { record ->
                                RecordCard(
                                    record = record,
                                    onClick = { onOpenRecord(record) },
                                    width = null,
                                    thumbHeight = 168.dp,
                                )
                            }
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(records, key = { it.id }, contentType = { "record" }) { record ->
                                RecordCard(record = record, onClick = { onOpenRecord(record) })
                            }
                        }
                        if (short) Spacer(Modifier.height(Space.md)) else Spacer(Modifier.weight(1f))
                    }
                }

                HomeFooter()
            }
        }
    }
}

/** 页脚：一句"数据不出本机"的信任说明，弱化色、居中 */
@Composable
private fun HomeFooter() {
    val c = Ocr.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "识别走 PaddleOCR-VL 云端", style = OcrType.Caption.copy(fontSize = 12.sp), color = c.mutedSoft)
        Spacer(Modifier.width(8.dp))
        Dot()
        Spacer(Modifier.width(8.dp))
        Text(text = "结果只留本机", style = OcrType.Caption.copy(fontSize = 12.sp), color = c.mutedSoft)
    }
}

/**
 * 首页的 PDF 试卷导入入口（WP5）。
 *
 * 「相册导入」的 Photo Picker 只能选图片，而试卷 PDF 一般躺在下载/文件目录，
 * 所以单独一行、点开走 OpenDocument("application/pdf")（见 OcrApp.launchPdfPicker）：
 * 选中的一份 PDF 会被逐页栅格化（data/local/PdfPageSource），整卷合并成一条多页记录。
 * 做成整行卡片而不是第三张 ActionCard：与 PipelineEntry/StreamEntry 同级的"整行入口"，
 * 主操作区两张大卡的位置留给最高频的拍照/相册。
 */
@Composable
private fun PdfEntry(onClick: () -> Unit) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(c.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "PDF 试卷导入", style = OcrType.BodySm, color = c.ink)
            Text(
                text = "整份 PDF 逐页栅格化 · 合并为一卷",
                style = OcrType.Caption.copy(fontSize = 12.5.sp),
                color = c.muted,
            )
        }
        IconChevronRight(c.mutedSoft, size = 14.dp, stroke = 1.5.dp)
    }
}

/**
 * 首页的「流式识别渲染」入口（流式渲染演示页 + 真实识别的合体）。
 *
 * 做成整行卡片（与 [PipelineEntry] / [StreamEntry] 同级）：演示/验证类入口不是主操作。
 * 页内行为见 OcrStreamScreen 的 StreamPhase 注释：识别中实时渲染逐区域输出，
 * 完成后切 DocumentIR 区域页，框/正文联动、调序、导出、打字机回放全可用。
 */
@Composable
private fun StreamRecEntry(onClick: () -> Unit) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(c.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "流式识别渲染", style = OcrType.BodySm, color = c.ink)
            Text(
                text = "端侧真识别 · 边生成边渲染 · 可调序导出",
                style = OcrType.Caption.copy(fontSize = 12.5.sp),
                color = c.muted,
            )
        }
        IconChevronRight(c.mutedSoft, size = 14.dp, stroke = 1.5.dp)
    }
}

/**
 * 首页的流水识别测试入口（WP4）。
 *
 * 做成整行卡片而不是第三张 ActionCard：与 [StreamEntry] 同级 —— 都是"演示/验证页"，
 * 不是拍照/相册那种主操作。验证期过后（WP5 正式分流接好）这行会摘掉。
 */
@Composable
private fun PipelineEntry(onClick: () -> Unit) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(c.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "流水识别（测试）", style = OcrType.BodySm, color = c.ink)
            Text(
                text = "端侧 PP-DocLayoutV3 → GLM-OCR · 区域逐字流式",
                style = OcrType.Caption.copy(fontSize = 12.5.sp),
                color = c.muted,
            )
        }
        IconChevronRight(c.mutedSoft, size = 14.dp, stroke = 1.5.dp)
    }
}

/**
 * 首页底部的流式渲染入口。
 *
 * 做成整行卡片而不是第三张 ActionCard：ActionCard 是"拍照/相册"那种主操作，
 * 这个是演示页入口，视觉分量应当低于主操作。
 */
@Composable
private fun StreamEntry(onClick: () -> Unit) {
    val c = Ocr.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(c.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "流式渲染演示", style = OcrType.BodySm, color = c.ink)
            Text(
                text = "区域 JSON → 逐字出公式与样图",
                style = OcrType.Caption.copy(fontSize = 12.5.sp),
                color = c.muted,
            )
        }
        IconChevronRight(c.mutedSoft, size = 14.dp, stroke = 1.5.dp)
    }
}

/* ------------------------------------------------------------------ *
 * 预览组：phone / landscape（380dp 高，锁定矮屏滚动兜底）/ foldable / tablet。
 * 只喂假样例（imagePath 为 null → PaperThumb 走 Canvas 纸面），无 IO，Layoutlib 可跑。
 * ------------------------------------------------------------------ */
@Preview(name = "phone", showBackground = true, device = Devices.PHONE)
@Preview(name = "landscape", showBackground = true, widthDp = 800, heightDp = 380)
@Preview(name = "foldable", showBackground = true, device = Devices.FOLDABLE)
@Preview(name = "tablet", showBackground = true, device = Devices.TABLET)
@Composable
private fun OcrHomeScreenPreview() {
    CompositionLocalProvider(LocalOcrColors provides OcrColors.Light) {
        OcrHomeScreen(
            records = sampleRecords,
            onOpenSettings = {},
            onCapture = {},
            onImport = {},
            onOpenRecord = {},
            onOpenStream = {},
        )
    }
}
