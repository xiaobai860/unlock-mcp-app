package com.unlockguard.mcp.ui.screens

/**
 * 解锁守护 · MCP 手机端 —— 界面实现（严格按 UI 设计稿 v1.2 的五个界面复刻）
 *
 * 引导 / 首页 / 状态 / 日志 / 设置 五屏 + 底部导航 + 悬浮球 + 引导弹层 + 错误对话框 + Toast。
 * 所有状态来自真实领域层与系统 API，UI 层不编造「成功」。
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.unlockguard.mcp.ui.overlay.OverlayBallManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unlockguard.mcp.core.PermAction
import com.unlockguard.mcp.core.PermStatus
import com.unlockguard.mcp.core.ShizukuGate
import com.unlockguard.mcp.core.performPermAction
import com.unlockguard.mcp.domain.AuditLog
import com.unlockguard.mcp.ui.AppViewModel
import com.unlockguard.mcp.ui.PhoneUiState
import com.unlockguard.mcp.ui.components.AddrRow
import com.unlockguard.mcp.ui.components.AppButton
import com.unlockguard.mcp.ui.components.AppCard
import com.unlockguard.mcp.ui.components.AppSwitch
import com.unlockguard.mcp.ui.components.AppToast
import com.unlockguard.mcp.ui.components.BrandGradientEnd
import com.unlockguard.mcp.ui.components.BtnVariant
import com.unlockguard.mcp.ui.components.CardHead
import com.unlockguard.mcp.ui.components.ChipRow
import com.unlockguard.mcp.ui.components.CopyButton
import com.unlockguard.mcp.ui.components.DialogSpec
import com.unlockguard.mcp.ui.components.EmptyHint
import com.unlockguard.mcp.ui.components.ErrorDialog
import com.unlockguard.mcp.ui.components.FieldRow
import com.unlockguard.mcp.ui.components.GuardTabBar

import com.unlockguard.mcp.ui.components.HLine
import com.unlockguard.mcp.ui.components.Kicker
import com.unlockguard.mcp.ui.components.Lead
import com.unlockguard.mcp.ui.components.LogRow
import com.unlockguard.mcp.ui.components.MetricRow
import com.unlockguard.mcp.ui.components.NoteBox
import com.unlockguard.mcp.ui.components.PageTitle
import com.unlockguard.mcp.ui.components.PermRow
import com.unlockguard.mcp.ui.components.PermState
import com.unlockguard.mcp.ui.components.Pill
import com.unlockguard.mcp.ui.components.PinDialog
import com.unlockguard.mcp.ui.components.ProgressBar
import com.unlockguard.mcp.ui.components.ScreenColumn
import com.unlockguard.mcp.ui.components.ScreenHeader
import com.unlockguard.mcp.ui.components.SetInput
import com.unlockguard.mcp.ui.components.SignalRing
import com.unlockguard.mcp.ui.components.StepCard
import com.unlockguard.mcp.ui.components.StepState
import com.unlockguard.mcp.ui.components.Tone
import com.unlockguard.mcp.ui.theme.AppShapes
import com.unlockguard.mcp.ui.theme.AppText
import com.unlockguard.mcp.ui.theme.PillShape
import com.unlockguard.mcp.ui.theme.Spacing
import com.unlockguard.mcp.ui.theme.semantic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ===================================================================== */
/* 根：五屏 + 底部导航 + 浮层                                              */
/* ===================================================================== */

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    // 「查看悬浮球」点击计数：每次 +1 触发设置页滚动定位到悬浮球卡片
    var fabScrollNonce by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }

    val ui by vm.ui.collectAsStateWithLifecycle()
    // 通道缺失项：Shizuku 主通道 / 无障碍备通道 / 安全锁定待恢复。
    // 设备管理员是可选兜底（代价：生物识别失效），不计入缺失。
    val missingCount = listOf(ui.shizuku, ui.accessibility).count { !it } + if (ui.lockedOut) 1 else 0
    val serviceOn by vm.serviceOn.collectAsStateWithLifecycle()
    val lanOn by vm.lanOn.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val logRetentionDays by vm.logRetentionDays.collectAsStateWithLifecycle()
    val addresses by vm.addresses.collectAsStateWithLifecycle()
    val fabOn by vm.fabOn.collectAsStateWithLifecycle()
    val verifyUi by vm.verify.collectAsStateWithLifecycle()
    // 默认开启「先锁屏再验证」：熄屏后重新解锁最接近真实调用场景
    var verifyLockFirst by remember { mutableStateOf(true) }

    fun say(msg: String) {
        toast = msg
        scope.launch {
            delay(1800)
            if (toast == msg) toast = null
        }
    }

    fun copy(text: String, msg: String = "已复制到剪贴板") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("unlockguard", text))
        say(msg)
    }

    val localAddr = addresses.firstOrNull { it.contains("127.0.0.1") } ?: "http://127.0.0.1:${ui.port}/mcp"
    val lanAddr = addresses.firstOrNull { !it.contains("127.0.0.1") }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> OnboardScreen(
                        ui = ui,
                        hasPin = vm.hasPin(),
                        serviceOn = serviceOn,
                        onPermAction = { performPermAction(ctx, it) },
                        onOpenShizuku = { say(ShizukuGate.request(ctx)) },
                        onSetPin = { pinValue = ""; pinDialog = true },
                        onStartService = { vm.startService(); say("MCP 服务已启动") },
                        onSay = { say(it) },
                    )

                    1 -> HomeScreen(
                        ui = ui,
                        serviceOn = serviceOn,
                        localAddr = localAddr,
                        lanAddr = lanAddr,
                        lanOn = lanOn,
                        onToggleService = {
                            vm.toggleService()
                            say(if (serviceOn) "服务已停止" else "服务已启动")
                        },
                        onCopy = { t -> copy(t) },
                        onReleaseLease = {
                            if (vm.releaseLease()) say("租约已释放，系统设置已还原") else say("当前没有可释放的租约")
                        },
                        onShowFab = { tab = 4; fabScrollNonce++ },
                    )

                    2 -> StatusScreen(
                        ui = ui,
                        missingCount = missingCount,
                        verifyUi = verifyUi,
                        verifyLockFirst = verifyLockFirst,
                        onVerifyLockFirst = { verifyLockFirst = it },
                        onRunVerify = { vm.runVerify(verifyLockFirst) },
                        onClearVerify = { vm.clearVerify() },
                        onPermAction = { performPermAction(ctx, it) },
                        onOpenShizuku = { say(ShizukuGate.request(ctx)) },
                        onShowMissing = {
                            // 如实取自当前状态：优先暴露真实缺失的能力
                            dialog = when {
                                !ui.accessibility -> DialogSpec(
                                    title = "无障碍通道未开启",
                                    body = "无障碍备通道当前关闭，解锁仅走 Shizuku 主通道。建议开启以提升锁屏阶段成功率。",
                                    code = "ACCESSIBILITY_DISABLED",
                                    tone = Tone.Warn,
                                    icon = Icons.Outlined.Warning,
                                    confirmLabel = "去开启",
                                    onConfirm = { performPermAction(ctx, PermAction.Accessibility) },
                                )

                                !ui.shizuku -> DialogSpec(
                                    title = "Shizuku 未授权",
                                    body = "主通道不可用，解锁将退化为无障碍备通道或被拒绝。请在 Shizuku 中授权本应用。",
                                    code = "SHIZUKU_UNAVAILABLE",
                                    tone = Tone.Warn,
                                    icon = Icons.Outlined.Warning,
                                    confirmLabel = "去授权",
                                    onConfirm = { say(ShizukuGate.request(ctx)) },
                                )

                                ui.lockedOut -> DialogSpec(
                                    title = "已暂停自动解锁",
                                    body = "连续 ${ui.failThreshold} 次 PIN 输入失败，已触发安全锁定。${ui.lockedOutRemainingSec} 秒后自动恢复，期间请手动操作手机。",
                                    code = "LOCKED_OUT",
                                    tone = Tone.Err,
                                    icon = Icons.Outlined.Lock,
                                    confirmLabel = "去设置",
                                    onConfirm = { performPermAction(ctx, PermAction.AppInfo) },
                                )

                                else -> DialogSpec(
                                    title = "双通道均就绪",
                                    body = "Shizuku 主通道与无障碍备通道当前均可用，无缺失项需要处理。",
                                    code = null,
                                    tone = Tone.Ok,
                                    icon = Icons.Outlined.VerifiedUser,
                                    confirmLabel = "知道了",
                                    dismissLabel = "关闭",
                                )
                            }
                        },
                    )

                    3 -> LogScreen(
                        logs = vm.auditRecent.collectAsStateWithLifecycle().value,
                        retentionDays = logRetentionDays,
                        onRetentionDays = { vm.setLogRetentionDays(it); say("日志保存天数已设为 $it 天") },
                        onClearLogs = {
                            dialog = DialogSpec(
                                title = "清空调用日志？",
                                body = "将删除本机保存的全部调用记录，且不可恢复。",
                                code = "AUDIT_CLEAR",
                                tone = Tone.Warn,
                                icon = Icons.Outlined.Warning,
                                confirmLabel = "确认清空",
                                dismissLabel = "取消",
                                onConfirm = { vm.clearAudit(); say("日志已清空") },
                            )
                        },
                        onExport = {
                            val f = runCatching { vm.exportAuditCsv() }.getOrNull()
                            say(if (f != null) "已导出：${f.name}" else "导出失败，请检查存储权限")
                        },
                    )

                    else -> SettingsScreen(
                        ui = ui,
                        lanOn = lanOn,
                        token = token,
                        versionName = vm.versionName,
                        hasPin = vm.hasPin(),
                        fabOn = fabOn,
                        fabGranted = vm.canDrawOverlay(),
                        onLan = { vm.toggleLan(it); say(if (it) "已开启局域网连接" else "已关闭局域网连接") },
                        onPort = { raw ->
                            val applied = vm.setPort(raw)
                            if (applied != null) say("端口已更新为 $applied")
                        },
                        onCopy = { t, m -> copy(t, m) },
                        onRegenerate = {
                            val t = vm.regenerateToken()
                            copy(t, "Token 已重新生成并复制，请在电脑端更新")
                        },
                        onEditPin = { pinValue = ""; pinDialog = true },
                        onFab = { on -> say(vm.setFab(on)) },
                        onCopyConfig = { copy(vm.copyConfig(), "MCP 连接配置已复制，可直接粘进 AI 客户端的 MCP 配置") },
                        scrollNonce = fabScrollNonce,
                        wssGranted = ui.wssGranted,
                        adbGrantCmd = vm.adbGrantCommand(),
                    )
                }
                // 悬浮球已改为系统级悬浮窗（见 OverlayBallManager）：
                // 由前台服务托管，退出应用/回到桌面后依然常驻，故此处不再叠一层 Compose 覆盖，
                // 否则应用内会出现两个球。
                AppToast(message = toast)
            }
            GuardTabBar(selected = tab, onSelect = { tab = it })
        }

        ErrorDialog(spec = dialog, onDismiss = { dialog = null })

        PinDialog(
            visible = pinDialog,
            title = if (vm.hasPin()) "修改 PIN" else "设置 PIN",
            value = pinValue,
            onValueChange = { pinValue = it },
            onConfirm = {
                vm.setPin(pinValue)
                pinDialog = false
                pinValue = ""
                say("PIN 已保存（Keystore 加密）")
            },
            onDismiss = { pinDialog = false; pinValue = "" },
        )
    }
}

/* ===================================================================== */
/* 1. 引导                                                                */
/* ===================================================================== */

@Composable
private fun OnboardScreen(
    ui: PhoneUiState,
    hasPin: Boolean,
    serviceOn: Boolean,
    onPermAction: (PermAction) -> Unit,
    onOpenShizuku: () -> Unit,
    onSetPin: () -> Unit,
    onStartService: () -> Unit,
    onSay: (String) -> Unit,
) {
    val battery = ui.perms.firstOrNull { it.title == "电池无限制" }?.status == PermStatus.Granted
    val overlay = ui.perms.firstOrNull { it.title == "悬浮窗" }?.status == PermStatus.Granted
    val step1 = battery && overlay

    val done = listOf(step1, ui.shizuku, ui.accessibility, hasPin, serviceOn)
    val firstPending = done.indexOfFirst { !it }
    val doneCount = done.count { it }

    val labels = listOf("去授权", "去授权", "去开启", "去设置", "去启动")
    val actions: List<() -> Unit> = listOf(
        // 第 1 步落到「应用信息页」而非电池优化页：国产 ROM 会把电池优化 Intent 重定向到
        // 该应用的「电量详情页」，用户在那里改不了自启动/后台弹出等权限（用户实测反馈）。
        { onPermAction(PermAction.AppInfo) },
        onOpenShizuku,
        { onPermAction(PermAction.Accessibility) },
        onSetPin,
        onStartService,
    )

    fun stepState(i: Int): StepState = when {
        done[i] -> StepState.Done
        i == firstPending -> StepState.Active
        else -> StepState.Todo
    }

    ScreenColumn {
        Kicker("首次配置 · 只需一次")
        Spacer(Modifier.height(2.dp))
        PageTitle("把解锁能力\n装进手机")
        Spacer(Modifier.height(6.dp))
        Lead("按步骤授予权限并配置 PIN，之后手机重启服务也会自动拉起。全程本地执行，AI 客户端只填一行地址。")
        Spacer(Modifier.height(16.dp))
        ProgressBar(fraction = doneCount / 5f)
        Spacer(Modifier.height(8.dp))

        val titles = listOf(
            "授予手机权限" to "进入「应用信息」逐项设置：电池无限制 · 自启动 · 后台弹出 · 悬浮窗",
            "启动 Shizuku" to "点「去授权」直接唤起 Shizuku 授权弹窗；若服务未运行，先启动它再回来点一次。",
            "开启无障碍服务" to "解锁备通道，锁屏阶段更稳。推荐但非强制。",
            "设置 PIN" to "本地加密存储 (Keystore)，永不过网络传输。",
            "启动 MCP 服务" to "复制地址与 Token 到电脑端 AI 客户端，即可开始调用。",
        )

        titles.forEachIndexed { i, (title, desc) ->
            if (i > 0) Spacer(Modifier.height(Spacing.s3))
            // 第 5 步依赖前四步，未完成时置灰（与设计稿 disabled 一致）
            val enabled = if (i == 4) done.take(4).all { it } else true
            StepCard(index = i + 1, title = title, desc = desc, state = stepState(i)) {
                when {
                    done[i] -> Pill("已完成", Tone.Ok)
                    else -> AppButton(
                        text = labels[i],
                        onClick = { actions[i]() },
                        variant = if (i == firstPending) BtnVariant.Soft else BtnVariant.Ghost,
                        small = true,
                        enabled = enabled,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        AppButton(
            text = "继续配置",
            onClick = {
                if (firstPending < 0) onSay("全部配置已完成，服务已就绪")
                else actions[firstPending]()
            },
            fullWidth = true,
        )
    }
}

/* ===================================================================== */
/* 2. 首页 / 控制台                                                        */
/* ===================================================================== */

@Composable
private fun HomeScreen(
    ui: PhoneUiState,
    serviceOn: Boolean,
    localAddr: String,
    lanAddr: String?,
    lanOn: Boolean,
    onToggleService: () -> Unit,
    onCopy: (String) -> Unit,
    onReleaseLease: () -> Unit,
    onShowFab: () -> Unit,
) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("控制台")
                Spacer(Modifier.height(2.dp))
                PageTitle("解锁守护", small = true)
            }
            Pill(
                if (serviceOn) "服务运行中" else "已停止",
                if (serviceOn) Tone.Ok else Tone.Neutral,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        ServiceHeroCard(
            on = serviceOn,
            port = ui.port,
            uptimeSec = ui.uptimeSec,
            onToggle = onToggleService,
        )

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("连接地址", leadingIcon = Icons.Outlined.Wifi) {
                Pill("Bearer Token", Tone.Neutral)
            }
            AddrRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = "本机 (最稳)",
                subtitle = localAddr,
                copyText = localAddr,
                onCopy = onCopy,
            )
            AddrRow(
                icon = Icons.Outlined.Lan,
                title = "局域网",
                subtitle = lanAddr ?: "未开启（仅本机可访问）",
                last = !lanOn,
                copyText = lanAddr,
                onCopy = onCopy,
            )
            if (lanOn) {
                Spacer(Modifier.height(Spacing.s3))
                NoteBox(
                    text = "局域网监听已开启，同网段设备可访问服务。明文 HTTP 下 Token 有被嗅探风险，建议仅在可信网络临时使用。",
                    icon = Icons.Outlined.Warning,
                )
            }
            Spacer(Modifier.height(Spacing.s3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(
                    text = "查看悬浮球",
                    onClick = onShowFab,
                    variant = BtnVariant.Soft,
                    small = true,
                    leadingIcon = Icons.Outlined.Shield,
                )
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        if (ui.leaseActive) {
            LeaseCard(
                remainingSec = ui.leaseRemainingSec,
                totalSec = ui.leaseTotalSec,
                holder = ui.leaseHolder,
                onRelease = onReleaseLease,
            )
        } else {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                    SignalRing(size = 40.dp, fraction = 0f, color = semantic.borderStrong, stroke = 4.dp) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = semantic.text3, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("无活跃租约", style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text("AI 调用 unlock_phone 后会建立租约，期间系统设置被临时托管。", style = AppText.sub, color = semantic.text2)
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard(padding = PaddingValues(horizontal = Spacing.s4)) {
            QuickStatRow(
                fraction = if (ui.shizuku) 0.86f else 0f,
                color = if (ui.shizuku) semantic.ok else semantic.warn,
                icon = Icons.Outlined.VerifiedUser,
                title = "Shizuku 主通道",
                subtitle = if (ui.shizuku) "存活 · 已授权" else "未授权 · 主通道不可用",
                pillText = if (ui.shizuku) "可用" else "不可用",
                pillTone = if (ui.shizuku) Tone.Ok else Tone.Warn,
            )
            QuickStatRow(
                fraction = if (ui.accessibility) 0.86f else 0f,
                color = if (ui.accessibility) semantic.ok else semantic.warn,
                icon = Icons.Outlined.VerifiedUser,
                title = "无障碍备通道",
                subtitle = if (ui.accessibility) "已开启 · Keyguard" else "未开启 · 备通道不可用",
                pillText = if (ui.accessibility) "可用" else "不可用",
                pillTone = if (ui.accessibility) Tone.Ok else Tone.Warn,
                last = true,
            )
        }
    }
}

/** 设计稿 .svc-card：品牌渐变大卡 + glow 装饰 + 半透明操作按钮 */
@Composable
private fun ServiceHeroCard(on: Boolean, port: Int, uptimeSec: Long, onToggle: () -> Unit) {
    val shape = AppShapes.large
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .let {
                if (on) it.background(Brush.linearGradient(listOf(semantic.brand, BrandGradientEnd)))
                else it.background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, semantic.border, shape)
            },
    ) {
        if (on) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 30.dp, y = (-30).dp)
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
            )
        }
        Column(Modifier.padding(Spacing.s5)) {
            Text(
                "MCP 服务 (Streamable HTTP)",
                style = AppText.body2,
                color = if (on) Color.White.copy(alpha = 0.85f) else semantic.text2,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (on) "运行中" else "已停止",
                style = AppText.displayState,
                color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (on) "已守护 ${formatUptime(uptimeSec)} · 端口 $port" else "AI 客户端将无法连接本机服务",
                style = AppText.body2,
                color = if (on) Color.White.copy(alpha = 0.9f) else semantic.text2,
            )
            Spacer(Modifier.height(Spacing.s4))
            Row(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .let {
                        if (on) it.background(Color.White.copy(alpha = 0.22f)).border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                        else it.background(semantic.brand)
                    }
                    .clickable { onToggle() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(if (on) "停止服务" else "启动服务", style = AppText.btn, color = Color.White)
            }
        }
    }
}

/** 设计稿 .lease：warn-soft 底 + 信号环 + 倒计时条 + 释放按钮 */
@Composable
private fun LeaseCard(remainingSec: Int, totalSec: Int, holder: String?, onRelease: () -> Unit) {
    val fraction = if (totalSec > 0) remainingSec.toFloat() / totalSec else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(semantic.warnSoft)
            .border(1.dp, semantic.warn.copy(alpha = 0.3f), AppShapes.medium)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        SignalRing(
            size = 54.dp,
            fraction = fraction,
            color = semantic.warn,
            stroke = 3.dp,
            trackColor = semantic.warn.copy(alpha = 0.25f),
        ) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = semantic.warn, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "租约进行中 · 剩余 ${formatClock(remainingSec)}",
                style = AppText.bodyStrong, color = semantic.warnInk,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "持有者：${holder ?: "本机 AI 客户端"} · 防\"解锁后又被锁\"",
                style = AppText.sub, color = semantic.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(semantic.warn.copy(alpha = 0.22f))) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .clip(PillShape).background(semantic.warn),
                )
            }
        }
        AppButton("释放", onRelease, variant = BtnVariant.Ghost, small = true)
    }
}

/** 设计稿 .card.flush 内的 .field：34dp 信号环 + 标题/说明 + 状态药丸 */
@Composable
private fun QuickStatRow(
    fraction: Float,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    pillText: String,
    pillTone: Tone,
    last: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            SignalRing(size = 34.dp, fraction = fraction, color = color, stroke = 4.dp, trackColor = color.copy(alpha = 0.3f)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = AppText.sub, color = semantic.text3)
            }
            Pill(pillText, pillTone)
        }
        if (!last) HLine()
    }
}

/* ===================================================================== */
/* 3. 状态                                                                */
/* ===================================================================== */

@Composable
private fun StatusScreen(
    ui: PhoneUiState,
    missingCount: Int,
    verifyUi: AppViewModel.VerifyUi,
    verifyLockFirst: Boolean,
    onVerifyLockFirst: (Boolean) -> Unit,
    onRunVerify: () -> Unit,
    onClearVerify: () -> Unit,
    onPermAction: (PermAction) -> Unit,
    onOpenShizuku: () -> Unit,
    onShowMissing: () -> Unit,
) {
    ScreenColumn {
        Kicker("get_phone_state")
        Spacer(Modifier.height(2.dp))
        PageTitle("设备与通道状态", small = true)
        Spacer(Modifier.height(6.dp))
        Lead("AI 先查后动。以下为如实上报，能力不可用时明确标注而非假装成功。")
        Spacer(Modifier.height(Spacing.s3))

        MetricRow(
            icon = Icons.Outlined.Smartphone,
            tone = if (ui.screenOn) Tone.Brand else Tone.Neutral,
            title = "屏幕 · ${if (ui.screenOn) "已亮屏" else "已熄屏"}",
            subtitle = "熄屏超时会触发租约保活",
        ) { Pill(if (ui.screenOn) "亮屏" else "熄屏", if (ui.screenOn) Tone.Ok else Tone.Neutral) }

        Spacer(Modifier.height(Spacing.s3))
        MetricRow(
            icon = if (ui.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            tone = if (ui.locked) Tone.Warn else Tone.Ok,
            title = "锁屏 · ${if (ui.locked) "已锁定" else "已解锁"}",
            subtitle = "租约期内保持不锁",
        ) { Pill(if (ui.locked) "已锁定" else "已解锁", if (ui.locked) Tone.Warn else Tone.Ok) }

        Spacer(Modifier.height(Spacing.s3))
        val bothReady = ui.shizuku && ui.accessibility
        MetricRow(
            icon = Icons.Outlined.Key,
            tone = when {
                bothReady -> Tone.Brand
                ui.shizuku || ui.accessibility -> Tone.Warn
                else -> Tone.Err
            },
            title = "解锁通道 · " + when {
                bothReady -> "双通道就绪"
                ui.shizuku -> "仅主通道"
                ui.accessibility -> "仅备通道"
                else -> "均不可用"
            },
            subtitle = "Shizuku 主 · 无障碍备",
        ) {
            Pill(
                if (bothReady) "就绪" else "降级",
                if (bothReady) Tone.Ok else Tone.Warn,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("通道可用性") {
                // 全部就绪时不再显示「模拟缺失」按钮，改为就绪标识
                if (missingCount == 0) {
                    Pill("双通道就绪", Tone.Ok)
                } else {
                    AppButton(
                        text = if (missingCount == 1) "处理缺失" else "处理缺失 · $missingCount",
                        onClick = onShowMissing,
                        variant = BtnVariant.Ghost,
                        small = true,
                        leadingIcon = Icons.Outlined.Warning,
                    )
                }
            }
            FieldRow(
                title = "Shizuku",
                desc = "本地 shell · 解锁主通道",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Pill(if (ui.shizuku) "存活" else "不可用", if (ui.shizuku) Tone.Ok else Tone.Warn)
                    if (!ui.shizuku) {
                        AppButton(
                            text = "去授权",
                            onClick = onOpenShizuku,
                            variant = BtnVariant.Soft,
                            small = true,
                        )
                    }
                }
            }
            FieldRow(
                title = "无障碍服务",
                desc = "锁屏首选（保留指纹）+ 解锁备通道",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Pill(if (ui.accessibility) "已开启" else "未开启", if (ui.accessibility) Tone.Ok else Tone.Warn)
                    if (!ui.accessibility) {
                        AppButton(
                            text = "去开启",
                            onClick = { onPermAction(PermAction.Accessibility) },
                            variant = BtnVariant.Soft,
                            small = true,
                        )
                    }
                }
            }
            FieldRow(
                title = "设备管理员",
                desc = "锁屏兜底 · 代价：生物识别失效",
                last = true,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Pill(
                        if (ui.deviceAdmin) "已激活" else "未激活",
                        if (ui.deviceAdmin) Tone.Ok else Tone.Neutral,
                    )
                    if (!ui.deviceAdmin) {
                        AppButton(
                            text = "去激活",
                            onClick = { onPermAction(PermAction.DeviceAdmin) },
                            variant = BtnVariant.Soft,
                            small = true,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("安全状态")
            FieldRow(
                title = "连续解锁失败",
                desc = "达 ${ui.failThreshold} 次暂停 10 分钟",
            ) {
                Pill("${ui.failureCount} / ${ui.failThreshold}", Tone.Neutral)
            }
            FieldRow(
                title = "安全锁定",
                desc = "失败锁定保护",
                last = true,
            ) {
                if (ui.lockedOut) {
                    Pill("已锁定 ${ui.lockedOutRemainingSec}s", Tone.Err)
                } else {
                    Pill("未锁定", Tone.Ok)
                }
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("解锁验证") {
                if (verifyUi.report != null && !verifyUi.running) {
                    AppButton(
                        text = "清除",
                        onClick = onClearVerify,
                        variant = BtnVariant.Ghost,
                        small = true,
                    )
                }
            }
            Text(
                "配置完成后实跑一次真实解锁：按优先级先走 Shizuku 主通道，未能解开再降级无障碍，逐步回报每一步结果。",
                style = AppText.body2,
                color = semantic.text2,
            )
            Spacer(Modifier.height(Spacing.s3))
            FieldRow(
                title = "先锁屏再验证",
                desc = "熄屏后重新解锁，最接近真实调用场景（仅无障碍时自动用系统面板点亮屏幕）",
                last = true,
            ) {
                AppSwitch(
                    checked = verifyLockFirst,
                    onCheckedChange = onVerifyLockFirst,
                )
            }
            if (verifyLockFirst) {
                Spacer(Modifier.height(Spacing.s2))
                NoteBox(
                    text = "验证会真实熄灭并解锁屏幕，请确认 App 内 PIN 与本机锁屏密码一致；失败同样计入安全锁定次数。" +
                        (if (!ui.shizuku) "（当前仅无障碍：将自动用系统面板全局动作点亮熄屏，部分 ROM 可能不支持，失败请手动点亮后重试）" else ""),
                    icon = Icons.Outlined.Warning,
                )
            }
            Spacer(Modifier.height(Spacing.s3))
            AppButton(
                text = if (verifyUi.running) "正在验证…" else "开始解锁验证",
                onClick = onRunVerify,
                fullWidth = true,
                enabled = !verifyUi.running,
                leadingIcon = Icons.Outlined.Key,
            )

            verifyUi.report?.let { r ->
                Spacer(Modifier.height(Spacing.s3))
                HLine()
                Spacer(Modifier.height(Spacing.s3))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    Pill(if (r.ok) "验证通过" else "未通过", if (r.ok) Tone.Ok else Tone.Warn)
                    Pill("${r.elapsedMs} ms", Tone.Neutral)
                    if (r.backend != "-") Pill(r.backend, Tone.Brand)
                }
                Spacer(Modifier.height(Spacing.s2))
                Text(r.conclusion, style = AppText.body2, color = semantic.text2)
                Spacer(Modifier.height(Spacing.s2))
                r.steps.forEach { s ->
                    VerifyStepRow(name = s.name, ok = s.ok, detail = s.detail)
                }
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("权限自检") {
                // 权限总入口：一次进「应用信息页」，把权限 / 自启动 / 电池 / 后台弹出都设完
                AppButton(
                    text = "应用信息",
                    onClick = { onPermAction(PermAction.AppInfo) },
                    variant = BtnVariant.Ghost,
                    small = true,
                )
            }
            if (ui.perms.isEmpty()) {
                EmptyHint("正在读取系统权限状态…")
            } else {
                ui.perms.forEachIndexed { i, p ->
                    PermRow(
                        title = p.title,
                        state = when (p.status) {
                            PermStatus.Granted -> PermState.Granted
                            PermStatus.Missing -> PermState.Missing
                            PermStatus.Unknown -> PermState.Unknown
                        },
                        hint = p.desc,
                        onClick = if (p.action == PermAction.None) null else ({ onPermAction(p.action) }),
                        last = i == ui.perms.lastIndex,
                    )
                }
            }
        }
    }
}

/**
 * 解锁验证的单步结果行。
 * 左：步骤名 + 说明；右：通过 / 未过 标记。用于把"哪一步断了"直接摊开给用户看。
 */
@Composable
private fun VerifyStepRow(name: String, ok: Boolean, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AppText.body2, color = MaterialTheme.colorScheme.onSurface)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, style = AppText.micro, color = semantic.text3)
            }
        }
        Pill(if (ok) "通过" else "未过", if (ok) Tone.Ok else Tone.Warn)
    }
}

/* ===================================================================== */
/* 4. 日志                                                                */
/* ===================================================================== */

private val READ_ONLY_TOOLS = setOf(
    "get_phone_state", "set_screen_timeout", "restore_settings", "release_lease",
)

private val SETTING_TOOLS = setOf(
    "set_screen_timeout", "restore_settings", "grant_debug_auth", "release_lease", "get_phone_state",
)

/** 日志默认保存天数可选值（天） */
private val LOG_RETENTION_OPTIONS = listOf(1, 3, 7, 30)

@Composable
private fun LogScreen(
    logs: List<AuditLog.Entry>,
    retentionDays: Int,
    onRetentionDays: (Int) -> Unit,
    onClearLogs: () -> Unit,
    onExport: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    val filters = listOf("全部", "解锁", "锁屏", "设置", "错误")

    val shown = logs.filter { e ->
        when (filters[filter]) {
            "解锁" -> e.tool == "unlock_phone"
            "锁屏" -> e.tool == "lock_phone"
            "设置" -> e.tool in SETTING_TOOLS
            "错误" -> !e.ok
            else -> true
        }
    }

    ScreenColumn {
        ScreenHeader(kicker = "审计日志", title = "调用记录", smallTitle = true) {
            AppButton(
                text = "导出 CSV",
                onClick = onExport,
                variant = BtnVariant.Soft,
                small = true,
                leadingIcon = Icons.Outlined.ContentCopy,
            )
        }
        Spacer(Modifier.height(Spacing.s3))
        ChipRow(labels = filters, selectedIndex = filter, onSelect = { filter = it })
        Spacer(Modifier.height(Spacing.s3))

        AppCard {
            CardHead("日志保留")
            Text(
                "超过保存天数的调用记录会自动清理，避免长期占用存储空间。",
                style = AppText.body2,
                color = semantic.text2,
            )
            Spacer(Modifier.height(Spacing.s3))
            Text("默认保存", style = AppText.bodyStrong, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.s2))
            ChipRow(
                labels = LOG_RETENTION_OPTIONS.map { "$it 天" },
                selectedIndex = LOG_RETENTION_OPTIONS.indexOf(retentionDays).coerceAtLeast(0),
                onSelect = { onRetentionDays(LOG_RETENTION_OPTIONS[it]) },
            )
            Spacer(Modifier.height(Spacing.s3))
            FieldRow(
                title = "清空日志",
                desc = "立即删除本机全部调用记录，不可恢复",
                last = true,
            ) {
                AppButton(
                    text = "清空",
                    onClick = onClearLogs,
                    variant = BtnVariant.Ghost,
                    small = true,
                )
            }
        }

        Spacer(Modifier.height(Spacing.s3))
        AppCard(padding = PaddingValues(horizontal = Spacing.s4, vertical = 6.dp)) {
            if (shown.isEmpty()) {
                Spacer(Modifier.height(Spacing.s2))
                EmptyHint(if (logs.isEmpty()) "暂无调用记录，AI 客户端发起调用后会显示在这里" else "当前筛选条件下没有记录")
                Spacer(Modifier.height(Spacing.s2))
            } else {
                shown.forEachIndexed { i, e ->
                    val tone = when {
                        !e.ok -> Tone.Err
                        e.tool in READ_ONLY_TOOLS -> Tone.Info
                        else -> Tone.Ok
                    }
                    LogRow(
                        tool = e.tool,
                        time = e.time,
                        detail = buildString {
                            append("来源 ").append(e.sourceIp)
                            if (e.args.isNotBlank()) append(" · ").append(e.args)
                        },
                        result = if (e.ok) {
                            "ok" + if (e.args.isNotBlank()) " · ${e.args}" else ""
                        } else {
                            "${e.errorCode ?: "ERROR"} · ${e.args.ifBlank { "调用被拒绝" }}"
                        },
                        tone = tone,
                        last = i == shown.lastIndex,
                    )
                }
            }
        }
    }
}

/* ===================================================================== */
/* 5. 设置                                                                */
/* ===================================================================== */

/** 带数值显示的滑块行（用于悬浮球大小 / 透明度 / 贴边露出调节） */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppText.body, color = semantic.text2)
            Text(valueText, style = AppText.mono, color = semantic.text2)
        }
        Spacer(Modifier.height(Spacing.s2))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = semantic.brand,
                activeTrackColor = semantic.brand,
                inactiveTrackColor = semantic.border,
            ),
        )
    }
}

@Composable
private fun SettingsScreen(
    ui: PhoneUiState,
    lanOn: Boolean,
    token: String,
    versionName: String,
    hasPin: Boolean,
    fabOn: Boolean,
    fabGranted: Boolean,
    wssGranted: Boolean,
    adbGrantCmd: String,
    onLan: (Boolean) -> Unit,
    onPort: (String) -> Unit,
    onCopy: (String, String) -> Unit,
    onRegenerate: () -> Unit,
    onEditPin: () -> Unit,
    onFab: (Boolean) -> Unit,
    onCopyConfig: () -> Unit,
    scrollNonce: Int = 0,
) {
    var portText by remember(ui.port) { mutableStateOf(ui.port.toString()) }
    val masked = if (token.length > 8) token.take(6) + "••••••••••" + token.takeLast(4) else token

    val fabScrollState = rememberScrollState()
    val fabCardY = remember { mutableIntStateOf(0) }
    // 「查看悬浮球」跳转设置页后，自动滚动定位到悬浮球卡片
    LaunchedEffect(scrollNonce, fabCardY.intValue) {
        if (scrollNonce > 0 && fabCardY.intValue > 0) fabScrollState.scrollTo(fabCardY.intValue)
    }

    ScreenColumn(scrollState = fabScrollState) {
        Kicker("Settings")
        Spacer(Modifier.height(2.dp))
        PageTitle("设置", small = true)

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("网络与鉴权")
            FieldRow(
                title = "服务端口",
                desc = "避开 8787/8765/8485/8080",
            ) {
                SetInput(
                    value = portText,
                    onValueChange = { v ->
                        portText = v.filter { it.isDigit() }.take(5)
                        onPort(portText)
                    },
                )
            }
            FieldRow(
                title = "局域网连接",
                desc = "关闭时仅本机可连 · 打开后同 Wi-Fi 下的电脑也能连",
                last = !lanOn,
            ) {
                AppSwitch(checked = lanOn, onCheckedChange = onLan)
            }
            if (lanOn) {
                Spacer(Modifier.height(Spacing.s3))
                NoteBox(
                    text = "打开后，同一 Wi-Fi 下的电脑 / AI 工具也能连上本服务来远程解锁。连接是明文 HTTP，同网络的人可能看到 Token，建议只在信任的 Wi-Fi 下临时开启。",
                    icon = Icons.Outlined.Warning,
                )
            }
            Spacer(Modifier.height(Spacing.s1))
            FieldRow(
                title = "访问 Token",
                desc = "随时可重新生成并吊销",
                last = true,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Text(masked, style = AppText.monoToken, color = semantic.text2, maxLines = 1)
                    CopyButton(
                        text = "",
                        onClick = { onCopy(token, "Token 已复制到剪贴板") },
                        icon = Icons.Outlined.ContentCopy,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.s3))
            AppButton(
                text = "复制 MCP 连接配置",
                onClick = onCopyConfig,
                variant = BtnVariant.Soft,
                fullWidth = true,
                leadingIcon = Icons.Outlined.ContentCopy,
            )
            Spacer(Modifier.height(Spacing.s2))
            NoteBox(
                text = "复制后把这段 JSON 直接粘进 Cursor / Claude Desktop 等客户端的 MCP 配置即可连上，无需手动拼地址与 Token。",
                icon = Icons.Outlined.Info,
            )
            Spacer(Modifier.height(Spacing.s3))
            AppButton(
                text = "重新生成 Token",
                onClick = onRegenerate,
                variant = BtnVariant.Ghost,
                fullWidth = true,
                leadingIcon = Icons.Outlined.Refresh,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("解锁凭据")
            FieldRow(
                title = "PIN 设置",
                desc = "本地加密 · Keystore + EncryptedSharedPreferences",
                last = true,
            ) {
                AppButton(
                    text = if (hasPin) "修改" else "设置",
                    onClick = onEditPin,
                    variant = BtnVariant.Soft,
                    small = true,
                )
            }
            Spacer(Modifier.height(Spacing.s3))
            NoteBox(
                text = "PIN 仅存于本机加密存储，永不过 MCP 通道传输；网络侧 AI 拿不到也不需要拿到 PIN 明文。",
                icon = Icons.Outlined.Shield,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard(
            modifier = Modifier.onPlaced { coords ->
                coords.parentLayoutCoordinates?.let { parent ->
                    fabCardY.intValue = coords.localPositionOf(parent).y.toInt()
                }
            },
        ) {
            CardHead("悬浮球")
            FieldRow(
                title = "常驻显示",
                desc = "退出应用后仍浮在屏幕上，可拖动，点一下回到本应用",
                last = !fabOn,
            ) {
                AppSwitch(checked = fabOn, onCheckedChange = onFab)
            }
            // 常驻关闭时收起外观调节，只保留开关与说明
            if (fabOn) {
                Spacer(Modifier.height(Spacing.s3))
                val ctx = LocalContext.current
                val sizeState = remember { mutableStateOf(OverlayBallManager.getSizeDp(ctx)) }
                val alphaState = remember { mutableStateOf(OverlayBallManager.getAlpha(ctx)) }
                val peekState = remember { mutableStateOf(OverlayBallManager.getPeekDp(ctx)) }
                SliderRow(
                    title = "大小",
                    valueText = "${sizeState.value.toInt()} dp",
                    value = sizeState.value,
                    valueRange = 40f..96f,
                    onValueChange = {
                        sizeState.value = it
                        OverlayBallManager.setSizeDp(ctx, it)
                    },
                )
                Spacer(Modifier.height(Spacing.s2))
                SliderRow(
                    title = "透明度",
                    valueText = "${(alphaState.value * 100).toInt()}%",
                    value = alphaState.value,
                    valueRange = 0.3f..1f,
                    onValueChange = {
                        alphaState.value = it
                        OverlayBallManager.setAlpha(ctx, it)
                    },
                )
                Spacer(Modifier.height(Spacing.s2))
                SliderRow(
                    title = "贴边露出",
                    valueText = "${peekState.value.toInt()} dp",
                    value = peekState.value,
                    valueRange = 0f..48f,
                    onValueChange = {
                        peekState.value = it
                        OverlayBallManager.setPeekDp(ctx, it)
                    },
                )
            }
            Spacer(Modifier.height(Spacing.s2))
            NoteBox(
                text = if (fabGranted) {
                    "悬浮球是系统级窗口，由守护服务托管：切到桌面、锁屏再唤醒都还在；拖动松手会自动贴到屏幕一侧，只露出你设定的「贴边露出」宽度。服务停止时会一并消失。"
                } else {
                    "尚未获得「显示在其他应用上层」权限。打开开关会跳转系统授权页，授权后回来再打开一次即可。"
                },
                icon = if (fabGranted) Icons.Outlined.Shield else Icons.Outlined.Warning,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("无障碍 · 重启免手动开启") {
                Pill(if (wssGranted) "已授权" else "未授权", if (wssGranted) Tone.Ok else Tone.Warn)
            }
            Text(
                "部分国产 ROM 会在重启后自动关闭第三方无障碍服务。授予下方特权权限后，应用可在开机时自动把它重新写回系统启用列表，无需你手动到「无障碍」设置页重新开启。",
                style = AppText.body2,
                color = semantic.text2,
            )
            Spacer(Modifier.height(Spacing.s3))
            Row(
                Modifier.fillMaxWidth()
                    .clip(AppShapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, semantic.border, AppShapes.medium)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                Text(
                    adbGrantCmd,
                    style = AppText.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(
                    text = "复制",
                    onClick = { onCopy(adbGrantCmd, "adb 授权命令已复制，请在已连接 adb 的电脑终端执行") },
                    icon = Icons.Outlined.ContentCopy,
                )
            }
            Spacer(Modifier.height(Spacing.s3))
            NoteBox(
                text = if (wssGranted) {
                    "已授权：重启后将自动保持无障碍服务开启。"
                } else {
                    "未授权：在已连接 adb 的电脑上执行上面命令（一次性）；重装应用或恢复出厂会失效。该权限仅用于重写无障碍设置，不影响其它功能。"
                },
                icon = if (wssGranted) Icons.Outlined.Shield else Icons.Outlined.Warning,
            )
        }

        Spacer(Modifier.height(Spacing.s4))
        AppCard {
            CardHead("关于")
            FieldRow(title = "版本") {
                Text("v1.2 · $versionName", style = AppText.mono, color = semantic.text2)
            }
            FieldRow(title = "技术基线", last = true) {
                Text("API 34–37", style = AppText.mono, color = semantic.text2)
            }
        }

        Spacer(Modifier.height(Spacing.s4))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "解锁守护 · MCP 手机端 · 界面按设计稿 v1.2 实现",
                style = AppText.micro, color = semantic.text3, textAlign = TextAlign.Center,
            )
        }
    }
}

/* ===================================================================== */
/* 工具                                                                   */
/* ===================================================================== */

/** 秒 → 「2 小时 14 分」/「14 分 3 秒」 */
private fun formatUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "$h 小时 $m 分"
        m > 0 -> "$m 分 $s 秒"
        else -> "$s 秒"
    }
}

/** 秒 → m:ss */
private fun formatClock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
