package com.unlockguard.mcp.ui.components

/**
 * 解锁守护 · MCP 手机端 —— 组件库（严格按 UI 设计稿 v1.2 复刻）
 *
 * 与设计稿 CSS 的对应关系写在每个组件上方，尺寸/圆角/颜色/内距均取自定稿原型，
 * 不使用 Material 默认外观（除图标与输入法行为外），以保证与设计稿 1:1。
 */

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unlockguard.mcp.ui.theme.AppShapes
import com.unlockguard.mcp.ui.theme.AppText
import com.unlockguard.mcp.ui.theme.PillShape
import com.unlockguard.mcp.ui.theme.Spacing
import com.unlockguard.mcp.ui.theme.semantic

/* ===================================================================== */
/* 语义色 / 品牌渐变                                                       */
/* ===================================================================== */

/** 设计稿 .svc-card / .fab / .progress 的渐变终点（与品牌色组成 150deg 渐变） */
val BrandGradientEnd = Color(0xFF6D5DF6)

/** 设计稿 .pill / .btn / .res / .log .bar 的语义色调 */
enum class Tone { Ok, Warn, Err, Info, Neutral, Brand }

data class ToneColors(val bg: Color, val fg: Color, val base: Color)

@Composable
fun toneColors(tone: Tone): ToneColors = when (tone) {
    Tone.Ok -> ToneColors(semantic.okSoft, semantic.okInk, semantic.ok)
    Tone.Warn -> ToneColors(semantic.warnSoft, semantic.warnInk, semantic.warn)
    Tone.Err -> ToneColors(semantic.errSoft, semantic.errInk, semantic.err)
    Tone.Info -> ToneColors(semantic.infoSoft, semantic.infoInk, semantic.info)
    Tone.Neutral -> ToneColors(semantic.surfaceSunken, MaterialTheme.colorScheme.onSurfaceVariant, semantic.borderStrong)
    Tone.Brand -> ToneColors(semantic.brandSoft, semantic.brandDeep, semantic.brand)
}

/* ===================================================================== */
/* 文本原子                                                               */
/* ===================================================================== */

/** 设计稿 .kicker：11px/600/字距 1.1px/大写/品牌色 */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AppText.kicker, color = semantic.brand, modifier = modifier)
}

/** 设计稿 .h1（22px）；small=true 时用于二级页（20px） */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        style = if (small) AppText.h1sm else AppText.h1,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** 设计稿 .lead：13px 次级说明 */
@Composable
fun Lead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppText.lead, color = semantic.text2, modifier = modifier)
}

/* ===================================================================== */
/* 容器                                                                   */
/* ===================================================================== */

/**
 * 设计稿 .card：surface-2 底 + 1px 边框 + 18 圆角 + 16 内距。
 * @param flush 等价 .card.flush（无内距）
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.s4),
    flush: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, semantic.border, AppShapes.medium),
    ) {
        Column(if (flush) Modifier else Modifier.padding(padding), content = content)
    }
}

/** 设计稿 .card-head：标题 + 右侧动作 */
@Composable
fun CardHead(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = semantic.text2, modifier = Modifier.size(16.dp))
        }
        Text(title, style = AppText.cardTitle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 设计稿 .field：列表项。13px 上下内距 + 1px 下边框（最后一项无边框）。
 */
@Composable
fun FieldRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    last: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
                if (desc != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(desc, style = AppText.sub, color = semantic.text3)
                }
            }
            trailing()
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(semantic.border))
    }
}

/** 设计稿 .note：warn-soft 底 + 26% 边框 + 12 圆角 + 11.5px 说明 */
@Composable
fun NoteBox(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Warn,
    icon: ImageVector? = null,
) {
    val c = toneColors(tone)
    Row(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(c.bg)
            .border(1.dp, c.base.copy(alpha = 0.26f), AppShapes.small)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.base, modifier = Modifier.size(14.dp).padding(top = 1.dp))
        }
        Text(text, style = AppText.res, color = c.fg)
    }
}

/* ===================================================================== */
/* 控件                                                                   */
/* ===================================================================== */

enum class BtnVariant { Primary, Soft, Ghost }

/**
 * 设计稿 .btn：14px/600，圆角 14（small 为 11 / 12.5px / 9-13 内距）。
 * Primary=品牌实心，Soft=品牌浅底，Ghost=透明+边框（.ghost2）。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.Primary,
    small: Boolean = false,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val bg: Color = when (variant) {
        BtnVariant.Primary -> semantic.brand
        BtnVariant.Soft -> semantic.brandSoft
        BtnVariant.Ghost -> Color.Transparent
    }
    val fg: Color = when (variant) {
        BtnVariant.Primary -> Color.White
        BtnVariant.Soft -> semantic.brandDeep
        BtnVariant.Ghost -> semantic.text2
    }
    val borderColor: Color? = if (variant == BtnVariant.Ghost) semantic.borderStrong else null

    val base = modifier
        .let { if (fullWidth) it.fillMaxWidth() else it }
        .clip(shape)
        .background(bg)
    val shaped = if (borderColor == null) base else base.border(1.dp, borderColor, shape)

    Row(
        shaped
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = if (small) 13.dp else 18.dp, vertical = if (small) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val alpha = if (enabled) 1f else 0.5f
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = fg.copy(alpha = alpha),
                modifier = Modifier.size(if (small) 14.dp else 15.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = if (small) AppText.btnSm else AppText.btn, color = fg.copy(alpha = alpha))
    }
}

/** 设计稿 .copy-btn：brand-soft 底 / brand-strong 字 / 11.5px / 圆角 9 */
@Composable
fun CopyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(semantic.brandSoft)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = semantic.brandDeep, modifier = Modifier.size(14.dp))
        }
        if (text.isNotEmpty()) Text(text, style = AppText.pill, color = semantic.brandDeep)
    }
}

/** 设计稿 .switch：46×28 轨道 + 22 圆钮，开启为品牌色 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track = if (checked) semantic.brand else semantic.surfaceSunken
    val border = if (checked) semantic.brand else semantic.borderStrong
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 0.dp, label = "thumb")
    Box(
        modifier
            .width(46.dp).height(28.dp)
            .clip(PillShape)
            .background(track)
            .border(1.dp, border, PillShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = 2.dp + thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 设计稿 .chips .chip：药丸筛选 */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) semantic.brand else MaterialTheme.colorScheme.surface
    val fg = if (selected) Color.White else semantic.text2
    val bd = if (selected) semantic.brand else semantic.border
    Box(
        modifier
            .clip(PillShape)
            .background(bg)
            .border(1.dp, bd, PillShape)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(label, style = AppText.body2, color = fg, maxLines = 1)
    }
}

/** 设计稿 .chips：横向可滚动筛选行 */
@Composable
fun ChipRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, l ->
            Chip(label = l, selected = i == selectedIndex, onClick = { onSelect(i) })
        }
    }
}

/** 设计稿 .progress：8dp 药丸进度条，品牌→#6D5DF6 渐变 */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(semantic.surfaceSunken)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(f)
                .clip(PillShape)
                .background(Brush.horizontalGradient(listOf(semantic.brand, BrandGradientEnd))),
        )
    }
}

/** 设计稿 .set-input：等宽 14px/600、宽 96、居中、圆角 11 */
@Composable
fun SetInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardNumber: Boolean = true,
) {
    Box(
        modifier
            .width(96.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, semantic.borderStrong, RoundedCornerShape(11.dp))
            .padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AppText.monoInput.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(semantic.brand),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (keyboardNumber) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ===================================================================== */
/* 指示器                                                                 */
/* ===================================================================== */

/** 设计稿 .pill：11.5px/600、5×10 内距、药丸；含语义圆点 */
@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = true) {
    val c = toneColors(tone)
    Row(
        modifier
            .clip(PillShape)
            .background(c.bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(c.fg))
        Text(text, style = AppText.pill, color = c.fg, maxLines = 1)
    }
}

/** 设计稿 .signal：环形指示（轨道 + 进度弧），圆心放图标 */
@Composable
fun SignalRing(
    size: Dp,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 4.dp,
    trackColor: Color? = null,
    center: @Composable () -> Unit = {},
) {
    val f = fraction.coerceIn(0f, 1f)
    val track = trackColor ?: color.copy(alpha = 0.3f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
            )
            if (f > 0f) {
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = 360f * f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

/** 设计稿 .metric：38dp 圆角图标块 + 标题/说明 + 右侧内容 */
@Composable
fun MetricRow(
    icon: ImageVector,
    tone: Tone,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val c = toneColors(tone)
    Row(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, semantic.border, AppShapes.medium)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.fg, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, style = AppText.sub, color = semantic.text3)
            }
        }
        trailing()
    }
}

/** 设计稿 .addr：图标块 + (名称/地址) + 复制按钮 */
@Composable
fun AddrRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    copyText: String? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(semantic.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = semantic.brandDeep, modifier = Modifier.size(15.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = AppText.body2.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = AppText.monoSmall,
                    color = semantic.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (copyText != null && onCopy != null) {
                CopyButton(
                    text = "复制",
                    onClick = { onCopy(copyText) },
                    icon = Icons.Outlined.ContentCopy,
                )
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(semantic.border.copy(alpha = 0.6f)))
    }
}

/** 设计稿 .log：3px 竖色条 + 工具名(等宽)/时间 + 摘要 + 结果行 */
@Composable
fun LogRow(
    tool: String,
    time: String,
    detail: String,
    result: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    last: Boolean = false,
) {
    val c = toneColors(tone)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.base))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tool,
                        style = AppText.monoStrong,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(time, style = AppText.monoSmall, color = semantic.text3)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    style = AppText.subMono,
                    color = semantic.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(result, style = AppText.res, color = c.fg)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(semantic.border))
    }
}

/** 权限自检状态 */
enum class PermState { Granted, Missing, Unknown }

/** 设计稿「权限自检」行：标题 + 右侧状态图标 */
@Composable
fun PermRow(title: String, state: PermState, hint: String?, onClick: (() -> Unit)?, last: Boolean = false) {
    val icon: ImageVector = when (state) {
        PermState.Granted -> Icons.Filled.Check
        PermState.Missing -> Icons.Outlined.Warning
        PermState.Unknown -> Icons.Outlined.Help
    }
    val tint: Color = when (state) {
        PermState.Granted -> semantic.ok
        PermState.Missing -> semantic.warn
        PermState.Unknown -> semantic.text3
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable { onClick() } else it }
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
                if (hint != null && state != PermState.Granted) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, style = AppText.sub, color = semantic.text3)
                }
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(semantic.border))
    }
}

/* ===================================================================== */
/* 引导步骤                                                               */
/* ===================================================================== */

enum class StepState { Done, Active, Todo }

/**
 * 设计稿 .step：序号圆 + 标题/说明 + 右侧动作。
 * done=绿底白勾，active=品牌底白字，todo=沉底灰字。
 */
@Composable
fun StepCard(
    index: Int,
    title: String,
    desc: String,
    state: StepState,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val idxBg: Color = when (state) {
        StepState.Done -> semantic.ok
        StepState.Active -> semantic.brand
        StepState.Todo -> semantic.surfaceSunken
    }
    val idxBorder: Color = when (state) {
        StepState.Done -> semantic.ok
        StepState.Active -> semantic.brand
        StepState.Todo -> semantic.border
    }
    val idxContent: Color = if (state == StepState.Todo) semantic.text3 else Color.White

    Row(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, semantic.border, AppShapes.medium)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(idxBg).border(1.dp, idxBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.Done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = idxContent, modifier = Modifier.size(16.dp))
            } else {
                Text("$index", style = AppText.stepTitle, color = idxContent)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppText.stepTitle, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(desc, style = AppText.body2, color = semantic.text2)
        }
        Box(Modifier.align(Alignment.CenterVertically)) { action() }
    }
}

/* ===================================================================== */
/* 页面骨架                                                               */
/* ===================================================================== */

/** 设计稿 .page：左右 18 / 上 14 / 下 22 内距 + 可滚动 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 88.dp),
        content = content,
    )
}

/** 页面顶部：kicker + 标题(+可选右侧动作) */
@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    smallTitle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Column(Modifier.weight(1f)) {
            Kicker(kicker)
            Spacer(Modifier.height(2.dp))
            PageTitle(title, small = smallTitle)
        }
        trailing?.invoke()
    }
}

/** 1px 分隔线 */
@Composable
fun HLine(modifier: Modifier = Modifier, color: Color? = null) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color ?: semantic.border))
}

/** 在顶部绘制 1px 边框（底部导航 / 弹层用） */
fun Modifier.topBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), width.toPx())
}
