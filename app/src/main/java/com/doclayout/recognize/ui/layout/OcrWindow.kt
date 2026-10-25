package com.doclayout.recognize.ui.layout

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 布局选择（和内容渲染彻底分开）。
 *
 * 判定只用两件事，都是系统给的、不靠猜：
 *   1. `Configuration.orientation` —— 横竖屏的唯一权威来源。旋转/折叠会重建 Activity
 *      （Manifest 已不再声明 configChanges），Compose 状态靠 rememberSaveable 恢复；
 *      Configuration 变化会触发本处重组，读到的始终是当前值，不需要监听器。
 *   2. `screenWidthDp` 落在哪个断点 —— 等价于 material3 的 WindowWidthSizeClass
 *      （Compact <600 / Medium 600-840 / Expanded ≥840）。
 *      加这一条是为了"横屏但是分屏小窗"的情况：只有 520dp 宽还硬分栏，两边都不可用。
 *
 * 为什么不用 `maxWidth > maxHeight`：那个判断依赖父容器尺寸，标题栏、导航条、
 * 键盘弹起都可能让"横屏"变成"高比宽大"，布局就莫名切回去了。方向就该问方向。
 *
 * 想要官方的 size class，把这个文件换成：
 *   implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
 *   @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
 *   val sizeClass = calculateWindowSizeClass(activity)
 *   val compact = sizeClass.widthSizeClass == WindowWidthSizeClass.Compact
 * 断点和这里完全一致，只是多引入一个依赖、还要传 Activity 进来。
 */
enum class OcrLayoutMode {
    /** 上下：全屏试卷 + 底部可拖抽屉（竖屏/窄屏） */
    Stacked,

    /** 左右：可拖动分栏 + 吸附档位（横屏/宽屏） */
    Split,
}

enum class OcrWidthClass { Compact, Medium, Expanded }

data class OcrWindowInfo(
    val isLandscape: Boolean,
    val width: Dp,
    val widthClass: OcrWidthClass,
) {
    val layout: OcrLayoutMode
        get() = if (isLandscape && widthClass != OcrWidthClass.Compact) {
            OcrLayoutMode.Split
        } else {
            OcrLayoutMode.Stacked
        }
}

@Composable
fun rememberOcrWindowInfo(): OcrWindowInfo {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp.dp
    val widthClass = when {
        width < 600.dp -> OcrWidthClass.Compact
        width < 840.dp -> OcrWidthClass.Medium
        else -> OcrWidthClass.Expanded
    }
    return OcrWindowInfo(
        isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        width = width,
        widthClass = widthClass,
    )
}
