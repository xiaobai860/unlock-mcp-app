package com.unlockguard.mcp.ui.theme

/**
 * 解锁守护 · MCP 手机端 —— 文字阶梯（严格对应设计稿 v1.2 的 CSS 字号/字重/行高）
 *
 * 对应关系：
 *  kicker   ← .kicker    11px / 600 / letter-spacing .1em / 大写
 *  h1 / h1sm← .h1        22px & 20px / 700
 *  cardTitle← .h2        16px / 600
 *  lead     ← .lead      13px / 行高 1.5
 *  bodyStrong← .field .meta b  13.5px / 600
 *  sub      ← .field .meta span / .addr span  11.5px / 10.5px
 *  mono*    ← .mono / .token-mask / .set-input  JetBrains Mono → 系统等宽
 *  pill     ← .pill      11.5px / 600
 *  btn      ← .btn       14px / 600，.btn.sm 12.5px
 *
 * 仅新增样式，未改动 Theme.kt 中任何既有令牌值。
 */

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AppText {

    /* 页面标题体系 */
    val kicker = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, letterSpacing = 1.1.sp,
    )

    val h1 = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 25.sp,
    )

    val h1sm = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 23.sp,
    )

    val cardTitle = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 20.sp,
    )

    val lead = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.5.sp,
    )

    /** 服务卡主状态字（.svc-card .state，26px / 700） */
    val displayState = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 30.sp,
    )

    /** 引导弹层标题（.sheet .stitle，17px / 700） */
    val sheetTitle = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.Bold, fontSize = 17.sp,
    )

    /** 对话框标题（.dialog h3，18px / 700） */
    val dialogTitle = TextStyle(
        fontFamily = FontDisplay, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    )

    /** 步骤标题（.step .body b，14px） */
    val stepTitle = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    )

    /* 正文 */
    val body = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 19.sp,
    )

    val bodyStrong = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
    )

    /** .step .body p / .dialog p / .sheet .sbody：12~13px 次级正文 */
    val body2 = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.5.sp,
    )

    val body3 = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp,
    )

    /** .field .meta span */
    val sub = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 16.sp,
    )

    /** .log .content .det */
    val subMono = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal, fontSize = 11.5.sp,
    )

    /** .addr .who span */
    val micro = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal, fontSize = 10.5.sp,
    )

    /** .log .content .res / .note */
    val res = TextStyle(
        fontFamily = FontBody, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.5.sp,
    )

    /* 技术值：等宽（IP / 端口 / Token / 租约 / 工具名） */
    val mono = TextStyle(fontFamily = FontMono, fontSize = 12.5.sp)
    val monoStrong = TextStyle(fontFamily = FontMono, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    val monoSmall = TextStyle(fontFamily = FontMono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp)
    val monoInput = TextStyle(fontFamily = FontMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val monoToken = TextStyle(fontFamily = FontMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, letterSpacing = 1.sp)

    /* 控件 */
    val btn = TextStyle(fontFamily = FontBody, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val btnSm = TextStyle(fontFamily = FontBody, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    val pill = TextStyle(fontFamily = FontBody, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
    val tabLabel = TextStyle(fontFamily = FontBody, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
}
