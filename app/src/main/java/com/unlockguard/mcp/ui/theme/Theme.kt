package com.unlockguard.mcp.ui.theme

/**
 * 解锁守护 · MCP 手机端 —— 设计令牌（UI 设计稿 v1.2 严格对应）
 * 颜色/间距/圆角/字体与高保真原型一致。
 */

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================ 颜色常量 ============================ */

val IrisBlue     = Color(0xFF4C5FD5)
val IrisBlueDeep = Color(0xFF3A49BE)
val IrisBlueSoft = Color(0xFFEAEDFB)
val IrisBlueInk  = Color(0xFF2B3480)

val OkBase  = Color(0xFF129A55); val OkSoft  = Color(0xFFE4F5EC); val OkInk  = Color(0xFF0C6E3C)
val WarnBase= Color(0xFFC9820C); val WarnSoft= Color(0xFFFBF0DB); val WarnInk= Color(0xFF8A5900)
val ErrBase = Color(0xFFD63838); val ErrSoft = Color(0xFFFCE7E7); val ErrInk = Color(0xFF9B1E1E)
val InfoBase= Color(0xFF2D6CDF); val InfoSoft= Color(0xFFE6EEFC); val InfoInk= Color(0xFF1B4CA0)

val NeutralBg      = Color(0xFFEEF0F6)
val NeutralSurface = Color(0xFFFFFFFF)
val NeutralSurf2   = Color(0xFFF6F7FB)
val NeutralSunken  = Color(0xFFE9ECF3)
val NeutralText    = Color(0xFF14161F)
val NeutralText2   = Color(0xFF565D70)
val NeutralText3   = Color(0xFF8A90A2)
val NeutralBorder  = Color(0xFFE2E5EF)
val NeutralBorderS = Color(0xFFCFD4E2)

val DarkBg      = Color(0xFF0B0D14)
val DarkSurface = Color(0xFF151926)
val DarkSurf2   = Color(0xFF1B2130)
val DarkSunken  = Color(0xFF0F131E)
val DarkText    = Color(0xFFF1F3FA)
val DarkText2   = Color(0xFFA7AEC2)
val DarkText3   = Color(0xFF6B7186)
val DarkBorder  = Color(0xFF262C3C)
val DarkBorderS = Color(0xFF353C50)

val DarkIris      = Color(0xFF7C8BFF)
val DarkIrisDeep  = Color(0xFF6A7BF2)
val DarkIrisSoft  = Color(0xFF1E2440)
val DarkIrisInk   = Color(0xFFB9C2FF)
val DarkOkBase = Color(0xFF2FBE73); val DarkOkSoft = Color(0xFF11281C); val DarkOkInk = Color(0xFF5FE39B)
val DarkWarnBase= Color(0xFFE0A23A); val DarkWarnSoft= Color(0xFF2A2110); val DarkWarnInk= Color(0xFFF3C46B)
val DarkErrBase = Color(0xFFF06060); val DarkErrSoft = Color(0xFF2C1414); val DarkErrInk = Color(0xFFFF8C8C)
val DarkInfoBase= Color(0xFF5B93F0); val DarkInfoSoft= Color(0xFF132036); val DarkInfoInk= Color(0xFF8FB8FF)

/* ============================ SemanticColors ============================ */

data class SemanticColors(
    val brand: Color, val brandDeep: Color, val brandSoft: Color, val brandInk: Color,
    val ok: Color, val okSoft: Color, val okInk: Color,
    val warn: Color, val warnSoft: Color, val warnInk: Color,
    val err: Color, val errSoft: Color, val errInk: Color,
    val info: Color, val infoSoft: Color, val infoInk: Color,
    val text3: Color, val text2: Color, val border: Color, val borderStrong: Color, val surfaceSunken: Color,
)

val LightSemantic = SemanticColors(
    brand = IrisBlue, brandDeep = IrisBlueDeep, brandSoft = IrisBlueSoft, brandInk = IrisBlueInk,
    ok = OkBase, okSoft = OkSoft, okInk = OkInk,
    warn = WarnBase, warnSoft = WarnSoft, warnInk = WarnInk,
    err = ErrBase, errSoft = ErrSoft, errInk = ErrInk,
    info = InfoBase, infoSoft = InfoSoft, infoInk = InfoInk,
    text3 = NeutralText3, text2 = NeutralText2, border = NeutralBorder, borderStrong = NeutralBorderS, surfaceSunken = NeutralSunken,
)
val DarkSemantic = SemanticColors(
    brand = DarkIris, brandDeep = DarkIrisDeep, brandSoft = DarkIrisSoft, brandInk = DarkIrisInk,
    ok = DarkOkBase, okSoft = DarkOkSoft, okInk = DarkOkInk,
    warn = DarkWarnBase, warnSoft = DarkWarnSoft, warnInk = DarkWarnInk,
    err = DarkErrBase, errSoft = DarkErrSoft, errInk = DarkErrInk,
    info = DarkInfoBase, infoSoft = DarkInfoSoft, infoInk = DarkInfoInk,
    text3 = DarkText3, text2 = DarkText2, border = DarkBorder, borderStrong = DarkBorderS, surfaceSunken = DarkSunken,
)
val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/* ============================ 字体 ============================ */

/**
 * 全部改用**系统字体族**，实现零字体资源依赖：无需手工往 res/font/ 放任何 ttf，开箱即用。
 *
 * - [FontDisplay] / [FontBody]：系统无衬线（Android 默认 Roboto；中文落到系统 Noto Sans CJK），
 *   字形层级不再依赖外置展示体，改由既有 `FontWeight / fontSize / lineHeight` 保证
 *   （displaySmall=Bold、titleLarge/Medium=SemiBold 与正文 Normal/Medium 的对比依旧成立）。
 * - [FontMono]：系统等宽（Roboto Mono / Droid Sans Mono），用于 IP / 端口 / Token / 租约倒计时
 *   等技术值——等宽对数字与冒号的对齐、快速扫描至关重要，此为功能性设计意图，必须保留。
 *
 * 变量名与类型（[FontFamily]）保持不变，Components / Screens 无需任何改动。
 *
 * 若日后要换成品牌字体：将 ttf 放入 `app/src/main/res/font/`，再把下面三行换回
 * `FontFamily(Font(R.font.xxx, FontWeight.Normal), ...)` 形式即可（无需改动其余代码）。
 */
val FontDisplay: FontFamily = FontFamily.SansSerif
val FontBody: FontFamily = FontFamily.SansSerif
val FontMono: FontFamily = FontFamily.Monospace

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontDisplay, fontWeight = FontWeight.Bold,  fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge   = TextStyle(fontFamily = FontDisplay, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium  = TextStyle(fontFamily = FontDisplay, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge    = TextStyle(fontFamily = FontBody,    fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium   = TextStyle(fontFamily = FontBody,    fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge   = TextStyle(fontFamily = FontBody,    fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium  = TextStyle(fontFamily = FontBody,    fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/* ============================ 形状 / 间距 ============================ */

val AppShapes = Shapes(
    small  = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large  = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)
val PillShape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)

object Spacing {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s8 = 32.dp; val s10 = 40.dp
}

/* ============================ ColorScheme ============================ */

private val LightScheme = lightColorScheme(
    primary = IrisBlue, onPrimary = Color.White,
    primaryContainer = IrisBlueSoft, onPrimaryContainer = IrisBlueInk,
    secondary = NeutralText2, onSecondary = Color.White,
    background = NeutralBg, onBackground = NeutralText,
    surface = NeutralSurface, onSurface = NeutralText,
    surfaceVariant = NeutralSurf2, onSurfaceVariant = NeutralText2,
    outline = NeutralBorder, outlineVariant = NeutralBorderS,
    error = ErrBase, onError = Color.White,
)
private val DarkScheme = darkColorScheme(
    primary = DarkIris, onPrimary = Color(0xFF0B0D14),
    primaryContainer = DarkIrisSoft, onPrimaryContainer = DarkIrisInk,
    secondary = DarkText2, onSecondary = DarkBg,
    background = DarkBg, onBackground = DarkText,
    surface = DarkSurface, onSurface = DarkText,
    surfaceVariant = DarkSurf2, onSurfaceVariant = DarkText2,
    outline = DarkBorder, outlineVariant = DarkBorderS,
    error = DarkErrBase, onError = Color(0xFF0B0D14),
)

@Composable
fun UnlockGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(LocalSemanticColors provides semantic, content = content)
    }
}

val semantic: SemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSemanticColors.current
