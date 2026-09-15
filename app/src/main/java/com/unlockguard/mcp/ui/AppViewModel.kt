package com.unlockguard.mcp.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unlockguard.mcp.core.AppGraph
import com.unlockguard.mcp.core.PermissionRow
import com.unlockguard.mcp.domain.AuditLog
import com.unlockguard.mcp.service.McpForegroundService
import com.unlockguard.mcp.unlock.VerifyReport
import com.unlockguard.mcp.ui.overlay.OverlayBallManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 五个界面共享的 UI 状态快照。
 * 所有字段都来自真实领域层/系统 API；无法查询的能力以 Unknown 表达，
 * 不在 UI 层编造状态（与设计稿「如实上报，不假装成功」一致）。
 */
data class PhoneUiState(
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    val shizuku: Boolean = false,
    val accessibility: Boolean = false,
    /** 设备管理员是否已激活（锁屏第二级兜底） */
    val deviceAdmin: Boolean = false,
    /** 是否持有 WRITE_SECURE_SETTINGS（无障碍重启免手动开启的前提） */
    val wssGranted: Boolean = false,
    val leaseActive: Boolean = false,
    val leaseRemainingSec: Int = 0,
    val leaseTotalSec: Int = 0,
    val leaseHolder: String? = null,
    val lockedOut: Boolean = false,
    val lockedOutRemainingSec: Int = 0,
    val failureCount: Int = 0,
    val failThreshold: Int = 5,
    val uptimeSec: Long = 0L,
    val port: Int = AppGraph.DEFAULT_PORT,
    val perms: List<PermissionRow> = emptyList(),
)

class AppViewModel(private val graph: AppGraph, app: Application) : AndroidViewModel(app) {

    /** 解锁验证 UI 状态 */
    data class VerifyUi(val running: Boolean = false, val report: VerifyReport? = null)

    val serviceOn: StateFlow<Boolean> = graph.serviceOn
    val lanOn: StateFlow<Boolean> = graph.lanOn
    val token: StateFlow<String> = graph.token
    val addresses: StateFlow<List<String>> = graph.addresses
    val auditRecent: StateFlow<List<AuditLog.Entry>> = graph.audit.recent
    val logRetentionDays: StateFlow<Int> = graph.audit.retentionDays
    val fabOn: StateFlow<Boolean> = graph.fabOn

    private val _verify = MutableStateFlow(VerifyUi())
    val verify: StateFlow<VerifyUi> = _verify.asStateFlow()

    /** 应用版本（取自 PackageManager，不依赖 BuildConfig） */
    val versionName: String = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    private val _ui = MutableStateFlow(PhoneUiState(port = graph.port))
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    /**
     * 从领域层/系统 API 采样一次真实状态。
     * @param refreshPerms 权限自检需读取 ContentProvider，按低频率刷新；false 时沿用上一帧结果
     */
    private fun snapshot(refreshPerms: Boolean): PhoneUiState {
        val shizuku = graph.unlockEngine.isShizukuAvailable()
        val accessibility = graph.unlockEngine.isAccessibilityEnabled()
        return PhoneUiState(
            screenOn = graph.unlockEngine.isScreenOn(),
            locked = graph.unlockEngine.isScreenLocked(),
            shizuku = shizuku,
            accessibility = accessibility,
            deviceAdmin = graph.permissions.isDeviceAdminActive(),
            wssGranted = graph.permissions.canWriteSecureSettings(),
            leaseActive = graph.leaseManager.isActive(),
            leaseRemainingSec = graph.leaseManager.remainingSec(),
            leaseTotalSec = graph.leaseManager.totalSec(),
            leaseHolder = graph.leaseManager.info()?.holder,
            lockedOut = graph.unlockEngine.isLockedOut(),
            lockedOutRemainingSec = graph.unlockEngine.lockedOutRemainingSec(),
            failureCount = graph.unlockEngine.failureCount(),
            failThreshold = 5,
            uptimeSec = graph.uptimeSec(),
            port = graph.port,
            perms = if (refreshPerms) graph.permissions.audit(accessibility, shizuku) else _ui.value.perms,
        )
    }

    init {
        _ui.value = snapshot(refreshPerms = true)
        viewModelScope.launch {
            var tick = 1
            while (true) {
                delay(1000)
                _ui.value = snapshot(refreshPerms = tick % 3 == 0)
                tick++
            }
        }
    }

    /* ---------------- 服务 ---------------- */

    /**
     * 开关 MCP 守护服务。
     *
     * 历史缺陷：旧实现只调用 `graph.startServer()`（仅起 HTTP 监听），
     * **从未启动 McpForegroundService 这个 Service 组件** —— 于是从界面开启服务时，
     * 常驻通知、WifiLock、网络恢复保活、悬浮球全都没有，只有开机自启那条路才会拉起前台服务。
     * 现改为经 startForegroundService 启动 Service，HTTP 监听由 Service 内部拉起。
     */
    fun toggleService() = if (graph.serviceOn.value) stopService() else startService()

    fun startService() {
        val app = getApplication<Application>()
        runCatching {
            app.startForegroundService(Intent(app, McpForegroundService::class.java))
        }.onFailure {
            // 极端情况（系统限制后台启动前台服务）退化为仅起监听，至少保证 MCP 可用
            if (!graph.serviceOn.value) graph.startServer()
        }
    }

    fun stopService() {
        val app = getApplication<Application>()
        runCatching { app.stopService(Intent(app, McpForegroundService::class.java)) }
        // 兜底：Service 未走到 onDestroy 时也要把状态复位
        if (graph.serviceOn.value) graph.stopServer()
    }

    /* ---------------- 悬浮球 ---------------- */

    fun canDrawOverlay(): Boolean = OverlayBallManager.canDraw(getApplication())

    /** 一键复制的 adb 授权命令：用户在电脑终端执行即可授予 WRITE_SECURE_SETTINGS */
    fun adbGrantCommand(): String =
        "adb shell pm grant ${getApplication<Application>().packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * 切换悬浮球显隐。
     *
     * 悬浮球由前台服务托管，因此开启时顺带确保服务在运行；未授予悬浮窗权限时
     * 先把用户送到系统授权页，并且**不写入开启状态**（避免开关显示"已开"但实际没有球）。
     */
    fun setFab(on: Boolean): String {
        val app = getApplication<Application>()
        if (on && !OverlayBallManager.canDraw(app)) {
            OverlayBallManager.openOverlaySettings(app)
            return "请先允许「显示在其他应用上层」，授权后回来重新打开"
        }
        graph.setFab(on)
        if (on) {
            // 服务是悬浮球的宿主：没起服务就地平线无从挂载
            if (!graph.serviceOn.value) startService()
        } else {
            OverlayBallManager.hide()
        }
        return if (on) "悬浮球已开启：退出应用后仍会常驻显示" else "悬浮球已关闭"
    }

    /* ---------------- 解锁验证 ---------------- */

    /** 实跑一次解锁并逐步回报。running 期间重复点击直接忽略。 */
    fun runVerify(lockFirst: Boolean) {
        if (_verify.value.running) return
        Log.i(TAG, "runVerify 开始 lockFirst=$lockFirst")
        _verify.value = VerifyUi(running = true)
        viewModelScope.launch {
            val outcome = runCatching { graph.unlockEngine.verify(lockFirst) }
            outcome.exceptionOrNull()?.let { Log.e(TAG, "解锁验证抛出异常", it) }
            val report = outcome.getOrNull()
            Log.i(
                TAG,
                "runVerify 结束 → " + (report?.let {
                    "ok=${it.ok} channel=${it.channel} backend=${it.backend} 耗时=${it.elapsedMs}ms"
                } ?: "report=null"),
            )
            _verify.value = VerifyUi(running = false, report = report)
        }
    }

    fun clearVerify() {
        _verify.value = VerifyUi()
    }

    fun toggleLan(on: Boolean) = graph.setLan(on)

    /** @return 实际生效的端口；null 表示输入非法（UI 保留原值） */
    fun setPort(raw: String): Int? {
        val p = raw.toIntOrNull() ?: return null
        return if (graph.setPort(p)) p else null
    }

    /* ---------------- 鉴权 / 凭据 ---------------- */

    fun regenerateToken(): String = graph.regenerateToken().also { graph.refreshAddresses() }

    /**
     * 生成可直接粘贴到 AI 客户端（Cursor / Claude Desktop 等）MCP 配置里的 JSON。
     * 优先用局域网地址（开启「局域网连接」后才有），否则退回 127.0.0.1（仅本机可连）。
     */
    fun copyConfig(): String {
        val base = addresses.value.firstOrNull { !it.contains("127.0.0.1") }
            ?: addresses.value.firstOrNull()
            ?: "http://127.0.0.1:${graph.port}/mcp"
        return buildString {
            append("{\n")
            append("  \"mcpServers\": {\n")
            append("    \"unlock-guard\": {\n")
            append("      \"url\": \"$base\",\n")
            append("      \"headers\": {\n")
            append("        \"Authorization\": \"Bearer ${token.value}\"\n")
            append("      }\n")
            append("    }\n")
            append("  }\n")
            append("}")
        }
    }

    fun setPin(pin: String) = graph.pinStore.setPin(pin)

    fun hasPin(): Boolean = graph.pinStore.hasPin()

    /* ---------------- 租约 ---------------- */

    fun releaseLease(): Boolean {
        val id = graph.leaseManager.info()?.leaseId ?: return false
        return graph.leaseManager.release(id)
    }

    /* ---------------- 审计 ---------------- */

    fun exportAuditCsv(): java.io.File = graph.audit.exportCsv()

    /** 修改日志默认保存天数（持久化，超期记录立即清理） */
    fun setLogRetentionDays(days: Int) {
        viewModelScope.launch { graph.audit.setRetentionDays(days) }
    }

    /** 清空本机全部调用日志（不可恢复） */
    fun clearAudit() {
        viewModelScope.launch { graph.audit.clear() }
    }

    class Factory(private val graph: AppGraph, private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph, app) as T
    }

    private companion object {
        const val TAG = "AppViewModel"
    }
}
