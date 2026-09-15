package com.unlockguard.mcp.core

import android.content.Context
import android.util.Log
import com.unlockguard.mcp.domain.AuditLog
import com.unlockguard.mcp.domain.LeaseManager
import com.unlockguard.mcp.domain.PinStore
import com.unlockguard.mcp.domain.RateLimiter
import com.unlockguard.mcp.mcp.McpContext
import com.unlockguard.mcp.mcp.McpServer
import com.unlockguard.mcp.unlock.RealUnlockEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用级依赖容器：持有所有领域对象、解锁引擎与 MCP Server 控制。
 * 由 MainApplication 创建，前台服务与 UI 共享同一实例。
 */
class AppGraph(private val app: Context) {

    private val prefs = app.getSharedPreferences("guard_settings", Context.MODE_PRIVATE)

    val pinStore = PinStore(app)
    private val unlockRateLimiter = RateLimiter()
    val leaseManager = LeaseManager(app)
    val audit = AuditLog(app)
    val rateLimiters: MutableMap<String, RateLimiter> = ConcurrentHashMap()
    val ipLimiters: MutableMap<String, RateLimiter> = ConcurrentHashMap()
    val unlockEngine = RealUnlockEngine(app, pinStore, unlockRateLimiter, leaseManager)
    val permissions = DevicePermissions(app)

    /** 服务端口（设置页可改，持久化；改动后重建监听） */
    var port: Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        private set

    private var _startedAtMs = 0L

    /** 本次服务启动时刻（elapsedRealtime），未运行时为 0 */
    val startedAtMs: Long get() = _startedAtMs

    /** 已守护秒数；未运行时为 0 */
    fun uptimeSec(): Long =
        if (_startedAtMs <= 0L) 0L
        else ((android.os.SystemClock.elapsedRealtime() - _startedAtMs) / 1000L)

    /**
     * 修改监听端口：持久化 → 重建监听（若在运行）→ 刷新展示地址。
     * @return 端口不合法时返回 false，UI 据此保留原值。
     */
    fun setPort(p: Int): Boolean {
        if (p < 1024 || p > 65535) return false
        if (p == port) return true
        port = p
        prefs.edit().putInt(KEY_PORT, p).apply()
        if (server != null) { stopServer(); startServer() } else refreshAddresses()
        return true
    }

    /**
     * 局域网监听是否已被用户授权（未授予悬浮窗/网络权限时不硬开）。
     * 这里只做状态记录，实际绑定行为交由 [setLan]。
     */
    fun canBindLan(): Boolean = true

    /** 局域网监听开关：持久化，重启手机后保持用户上次的选择 */
    private val _lanOn = MutableStateFlow(prefs.getBoolean(KEY_LAN, false))
    val lanOn: StateFlow<Boolean> = _lanOn.asStateFlow()

    private val _serviceOn = MutableStateFlow(false)
    val serviceOn: StateFlow<Boolean> = _serviceOn.asStateFlow()

    /**
     * 悬浮球是否常驻显示（持久化，默认开）。
     *
     * 它由前台服务托管，因此脱离应用界面也存在 —— 这正是「退出应用后悬浮球消失」
     * 的修复点：旧实现只是 Activity 里的一层 Compose 覆盖，Activity 一销毁就没了。
     */
    private val _fabOn = MutableStateFlow(prefs.getBoolean(KEY_FAB, true))
    val fabOn: StateFlow<Boolean> = _fabOn.asStateFlow()

    fun setFab(on: Boolean) {
        prefs.edit().putBoolean(KEY_FAB, on).apply()
        _fabOn.value = on
    }

    private val _token = MutableStateFlow(pinStore.getToken())
    val token: StateFlow<String> = _token.asStateFlow()

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses.asStateFlow()

    private var server: McpServer? = null

    fun startServer() {
        if (server != null) return
        runCatching {
            val host = if (_lanOn.value) "0.0.0.0" else "127.0.0.1"
            val ctx = McpContext(
                appContext = app,
                token = _token.value,
                version = "1.0",
                port = port,
                bindHost = host,
                isLan = _lanOn.value,
                pinStore = pinStore,
                leaseManager = leaseManager,
                audit = audit,
                unlockEngine = unlockEngine,
                rateLimiters = rateLimiters,
                ipLimiters = ipLimiters,
            )
            server = McpServer(ctx).also { it.start() }
            _serviceOn.value = true
            _startedAtMs = android.os.SystemClock.elapsedRealtime()
            refreshAddresses()
        }.onFailure { e ->
            Log.e("AppGraph", "startServer 失败（端口可能被占用），服务将保持前台但无法响应请求", e)
            server = null
            _serviceOn.value = false
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        _serviceOn.value = false
        _startedAtMs = 0L
        _addresses.value = emptyList()
    }

    fun setLan(on: Boolean) {
        prefs.edit().putBoolean(KEY_LAN, on).apply()
        _lanOn.value = on
        if (server != null) { stopServer(); startServer() } // 重新绑定地址
        else refreshAddresses()
    }

    fun regenerateToken(): String {
        val t = pinStore.regenerateToken()
        _token.value = t
        return t
    }

    fun refreshAddresses() {
        val list = mutableListOf<String>("http://127.0.0.1:$port/mcp")
        lanIp()?.let { list.add("http://$it:$port/mcp") }
        _addresses.value = list
    }

    /**
     * 取本机局域网 IPv4（用于展示/拼装 LAN 访问地址）。
     *
     * 历史实现用 `WifiManager.connectionInfo.ipAddress`：该 API 自 Android 13 起废弃，
     * 且在新系统上恒返回 0，导致局域网地址永远取不到、电脑端连不上（QA 实证的 P1 之一）。
     *
     * 现改为直接枚举网络接口，不依赖任何已废弃 API，覆盖 Android 13+：
     *  - 跳过未启用（!isUp）与回环接口；
     *  - 按接口名排序优先级：wlan* (0) > eth* (1) > 其它 (2)，局域网场景优先 Wi-Fi；
     *  - 只接受「站点本地地址」（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）的非回环 IPv4，
     *    天然排除运营商级 NAT(100.64/10) 等非局域网地址；
     *  - 同优先级取先命中的即可。
     *
     * @return 形如 "192.168.1.5" 的局域网 IPv4；无可用接口时返回 null。
     */
    private fun lanIp(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            var best: String? = null
            var bestRank = Int.MAX_VALUE
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase() ?: ""
                val rank = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    else -> 2
                }
                if (rank >= bestRank) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        best = addr.hostAddress
                        bestRank = rank
                        break
                    }
                }
            }
            best
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_PORT = 8790
        private const val KEY_PORT = "port"
        private const val KEY_FAB = "fab_on"
        private const val KEY_LAN = "lan_on"
    }
}
