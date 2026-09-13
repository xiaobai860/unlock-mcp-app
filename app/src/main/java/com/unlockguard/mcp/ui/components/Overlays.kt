package com.unlockguard.mcp.ui.components

/**
 * 解锁守护 · MCP 手机端 —— 浮层与导航（设计稿 .fab / .sheet / .scrim+.dialog / .toast / .tabbar）
 *
 * 全部按设计稿定稿数值复刻；在不依赖 Popup/Dialog 窗口的前提下，直接用根布局叠层实现，
 * 行为与设计稿一致（悬浮球可点开引导弹层、错误对话框可关闭/跳转、复制后有 Toast 反馈）。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unlockguard.mcp.ui.theme.AppShapes
import com.unlockguard.mcp.ui.theme.AppText
import com.unlockguard.mcp.ui.theme.FontBody
import com.unlockguard.mcp.ui.theme.PillShape
import com.unlockguard.mcp.ui.theme.Spacing
import com.unlockguard.mcp.ui.theme.semantic

/* ===================================================================== */
/* 悬浮球                                                                 */
/* ===================================================================== */

/** 设计稿 .fab：56dp 圆形，品牌渐变，3px 面板色描边，强投影 */
@Composable
fun GuardFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(semantic.brand, BrandGradientEnd)))
            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Shield,
            contentDescription = "解锁守护悬浮球",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/* ===================================================================== */
/* 引导底部弹层                                                            */
/* ===================================================================== */

/**
 * 设计稿 .sheet：底部弹层（抓手 + 警示标题 + 说明 + 双按钮）。
 * 场景：POPUP_BLOCKED —— 系统验证类弹窗拦截了自动解锁，需要用户点一下继续。
 */
@Composable
fun GuideSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "需要你点一下",
    body: String = "检测到系统验证类弹窗 (POPUP_BLOCKED)，自动解锁已停手。请手动完成验证后，点击下方按钮通知我继续；或直接在锁屏输入 PIN。",
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable { onDismiss() })
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .topBorder(semantic.border)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            ) {
                Box(
                    Modifier.size(width = 38.dp, height = 4.dp).clip(PillShape)
                        .background(semantic.borderStrong).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = semantic.warn, modifier = Modifier.size(18.dp))
                    Text(title, style = AppText.sheetTitle, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(10.dp))
                Text(body, style = AppText.lead.copy(lineHeight = 20.sp), color = semantic.text2)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("我已处理", onConfirm, variant = BtnVariant.Primary, modifier = Modifier.weight(1f))
                    AppButton("稍后", onDismiss, variant = BtnVariant.Ghost)
                }
            }
        }
    }
}

/* ===================================================================== */
/* 错误对话框                                                              */
/* ===================================================================== */

/**
 * 错误/引导对话框内容规格。
 * @param code 设计稿 .dcode 展示的机器可读错误码（如 LOCKED_OUT）
 */
data class DialogSpec(
    val title: String,
    val body: String,
    val code: String? = null,
    val tone: Tone = Tone.Err,
    val icon: ImageVector = Icons.Outlined.Lock,
    val confirmLabel: String = "去设置",
    val dismissLabel: String = "知道了",
    val onConfirm: (() -> Unit)? = null,
)

/** 设计稿 .scrim + .dialog：遮罩 + 居中卡片（56 圆形图标 / 标题 / 正文 / 错误码 / 双按钮） */
@Composable
fun ErrorDialog(
    spec: DialogSpec?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = spec != null
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x80080A12)).clickable { onDismiss() })
        }
        AnimatedVisibility(
            visible,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val s = spec ?: return@AnimatedVisibility
            val c = toneColors(s.tone)
            Column(
                Modifier
                    .padding(28.dp)
                    .widthIn(max = 300.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(c.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(s.icon, contentDescription = null, tint = c.base, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(s.title, style = AppText.dialogTitle, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(s.body, style = AppText.body3, color = semantic.text2, textAlign = TextAlign.Center)
                if (s.code != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(semantic.errSoft)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(s.code, style = AppText.monoSmall, color = semantic.errInk)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton(s.dismissLabel, onDismiss, variant = BtnVariant.Ghost, modifier = Modifier.weight(1f))
                    AppButton(
                        s.confirmLabel,
                        onClick = { s.onConfirm?.invoke() ?: onDismiss() },
                        variant = BtnVariant.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/* ===================================================================== */
/* PIN 输入对话框                                                          */
/* ===================================================================== */

/** 设置/修改 PIN：沿用对话框卡面，输入为数字键盘 + 密码遮蔽 */
@Composable
fun PinDialog(
    visible: Boolean,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x80080A12)).clickable { onDismiss() })
        }
        AnimatedVisibility(
            visible,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .widthIn(max = 300.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = AppText.dialogTitle, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(
                    "PIN 用于锁屏阶段的本地输入，以 Keystore 加密存储，永不过网络传输。",
                    style = AppText.body2, color = semantic.text2, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, semantic.borderStrong, RoundedCornerShape(11.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty()) {
                        Text("输入数字 PIN", style = AppText.monoInput, color = semantic.text3)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { v -> onValueChange(v.filter { it.isDigit() }.take(16)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = AppText.monoInput.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(semantic.brand),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("取消", onDismiss, variant = BtnVariant.Ghost, modifier = Modifier.weight(1f))
                    AppButton("保存", onConfirm, variant = BtnVariant.Primary, modifier = Modifier.weight(1f), enabled = value.isNotBlank())
                }
            }
        }
    }
}

/* ===================================================================== */
/* Toast                                                                  */
/* ===================================================================== */

/** 设计稿 .toast：底部居中药丸，深底浅字 */
@Composable
fun AppToast(message: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp),
        ) {
            Box(
                Modifier
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    message.orEmpty(),
                    style = AppText.btnSm,
                    color = MaterialTheme.colorScheme.surface,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ===================================================================== */
/* 底部导航                                                               */
/* ===================================================================== */

data class GuardTab(val label: String, val icon: ImageVector)

/** 设计稿 .tabbar：5 等分，激活项为品牌色 + 品牌浅底圆角方块衬底 */
val GuardTabs = listOf(
    GuardTab("引导", Icons.Outlined.Shield),
    GuardTab("首页", Icons.Outlined.Home),
    GuardTab("状态", Icons.Outlined.Insights),
    GuardTab("日志", Icons.Outlined.Article),
    GuardTab("设置", Icons.Outlined.Settings),
)

@Composable
fun GuardTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            // 面板色一直铺到屏幕底边，系统手势条区域内仍保持导航栏观感
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .topBorder(semantic.border),
    ) {
        Row(Modifier.fillMaxWidth().height(74.dp).padding(bottom = 8.dp)) {
            GuardTabs.forEachIndexed { i, t ->
                val sel = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(AppShapes.small)
                            .background(if (sel) semantic.brandSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            t.icon,
                            contentDescription = t.label,
                            tint = if (sel) semantic.brand else semantic.text3,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        style = AppText.tabLabel.copy(fontFamily = FontBody),
                        color = if (sel) semantic.brand else semantic.text3,
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 空态：无边框（用于已在内层卡片中的场景），保持与整体视觉一致 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(vertical = Spacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppText.body2, color = semantic.text3, textAlign = TextAlign.Center)
    }
}

/** 阴影辅助：给卡片加设计稿的 shadow-sm / shadow */
fun Modifier.softShadow(elevation: androidx.compose.ui.unit.Dp) = shadow(elevation, clip = false)
