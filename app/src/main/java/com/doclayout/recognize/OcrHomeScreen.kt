package com.doclayout.recognize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onOpenRecord: (OcrRecord) -> Unit,
) {
    val c = Ocr.colors
    var showAll by remember { mutableStateOf(false) }

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

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = Space.contentMax)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = Space.lg),
            ) {
                Spacer(Modifier.height(Space.xs))

                // ── 主操作区：两块并排，占上半部
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
                    ) { fg -> IconCamera(fg, size = 22.dp) }
                    ActionCard(
                        label = "相册导入",
                        hint = "支持 JPG / PNG / PDF",
                        primary = false,
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                    ) { fg -> IconGallery(fg, size = 22.dp) }
                }

                Spacer(Modifier.height(34.dp))

                if (records.isEmpty()) {
                    // ── 空状态：主操作不动，只在下面给出引导
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
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
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Space.md),
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            contentPadding = PaddingValues(bottom = Space.md),
                        ) {
                            items(records, key = { it.id }) { record ->
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
                            items(records, key = { it.id }) { record ->
                                RecordCard(record = record, onClick = { onOpenRecord(record) })
                            }
                        }
                        Spacer(Modifier.weight(1f))
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
