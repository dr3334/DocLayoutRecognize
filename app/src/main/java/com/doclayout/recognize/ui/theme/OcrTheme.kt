package com.doclayout.recognize.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Claude 风格设计令牌（把 DESIGN.md 里的 CSS 变量搬成 Kotlin 常量）。
 *
 * 为什么要单独一个文件、而不是各处写 Color(0xFF...)?
 *   因为设计系统的最小单位是「令牌」而不是「颜色值」。
 *   canvas 是"画布"这个角色，将来整体调暖一点，只改这里一处，全 App 跟着变；
 *   散落 30 个 Color(0xFFFAF9F5) 就只能靠全局搜索了。
 *
 * 令牌来源：github.com/VoltAgent/awesome-design-md → design-md/claude/DESIGN.md
 */

@Immutable
data class OcrColors(
    /** 画布（默认底）：奶油白，绝不能是纯白 */
    val canvas: Color,
    /** 柔和表面：次级区块、空状态插画底 */
    val surfaceSoft: Color,
    /** 卡片表面：功能卡 */
    val surfaceCard: Color,
    /** 强奶油：开关轨道、按下态 */
    val creamStrong: Color,
    /** 分隔线 */
    val hairline: Color,
    val hairlineSoft: Color,
    /** 墨色：标题正文本 */
    val ink: Color,
    /** 默认正文 */
    val body: Color,
    /** 弱化文字 */
    val muted: Color,
    val mutedSoft: Color,
    /** 珊瑚主色：只给主 CTA 和全幅卡片 */
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val teal: Color,
    val amber: Color,
    val success: Color,
    val danger: Color,
    /** 深色底（看图时的画布） */
    val scrim: Color,
    val scrimElevated: Color,
    val onScrim: Color,
    val onScrimSoft: Color,
    /** 试卷纸面：始终是浅色，它是"照片内容"，不跟主题走 */
    val paper: Color,
    val paperInk: Color,
) {
    companion object {
        /** 浅色：Claude 官方画布 */
        val Light = OcrColors(
            canvas = Color(0xFFFAF9F5),
            surfaceSoft = Color(0xFFF5F0E8),
            surfaceCard = Color(0xFFEFE9DE),
            creamStrong = Color(0xFFE8E0D2),
            hairline = Color(0xFFE6DFD8),
            hairlineSoft = Color(0xFFEBE6DF),
            ink = Color(0xFF141413),
            body = Color(0xFF3D3D3A),
            muted = Color(0xFF6C6A64),
            mutedSoft = Color(0xFF8E8B82),
            primary = Color(0xFFCC785C),
            primaryPressed = Color(0xFFA9583E),
            onPrimary = Color(0xFFFFFFFF),
            teal = Color(0xFF5DB8A6),
            amber = Color(0xFFE8A55A),
            success = Color(0xFF5DB872),
            danger = Color(0xFFC64545),
            scrim = Color(0xFF181715),
            scrimElevated = Color(0xFF252320),
            onScrim = Color(0xFFFAF9F5),
            onScrimSoft = Color(0xFFA09D96),
            paper = Color(0xFFFDFCF8),
            paperInk = Color(0xFF2A2A27),
        )

        /** 深色：DESIGN.md 的 surface-dark 一套 */
        val Dark = OcrColors(
            canvas = Color(0xFF181715),
            surfaceSoft = Color(0xFF1F1E1B),
            surfaceCard = Color(0xFF252320),
            creamStrong = Color(0xFF2E2B27),
            hairline = Color(0xFF332F2A),
            hairlineSoft = Color(0xFF272420),
            ink = Color(0xFFFAF9F5),
            body = Color(0xFFD8D4CB),
            muted = Color(0xFFA09D96),
            mutedSoft = Color(0xFF7E7B74),
            primary = Color(0xFFCC785C),
            primaryPressed = Color(0xFFE08A6C),
            onPrimary = Color(0xFFFFFFFF),
            teal = Color(0xFF5DB8A6),
            amber = Color(0xFFE8A55A),
            success = Color(0xFF5DB872),
            danger = Color(0xFFD96A6A),
            scrim = Color(0xFF0F0E0D),
            scrimElevated = Color(0xFF252320),
            onScrim = Color(0xFFFAF9F5),
            onScrimSoft = Color(0xFFA09D96),
            paper = Color(0xFFF3F1EA),
            paperInk = Color(0xFF2A2A27),
        )
    }
}

/**
 * 排版：展示级用衬线、正文用无衬线、公式用衬线斜体。
 * 关键规则来自 DESIGN.md：展示字号字重固定 400，绝不加粗；负字距不可省略。
 * （Android 的 FontFamily.Serif 会落到系统中的 NotoSerifCJK，中文真有衬线）
 */
object OcrType {
    val Display = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.6).sp,
    )
    val DisplaySm = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.3).sp,
    )
    val TitleMd = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp)
    val BodySm = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val Label = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
    )
    val Formula = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    )
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
    val Button = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}

/** 间距：4px 基准栅格 */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 内容最大宽度：对齐 Claude 的 1200px 封顶，手机/平板都靠它保持编辑式留白 */
    val contentMax = 560.dp
}

/** 圆角：卡 12 / 输入 8 / 大容器 16 / 胶囊 999 */
object Radii {
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

val LocalOcrColors = staticCompositionLocalOf { OcrColors.Light }

object Ocr {
    val colors: OcrColors
        @Composable @ReadOnlyComposable get() = LocalOcrColors.current
}
