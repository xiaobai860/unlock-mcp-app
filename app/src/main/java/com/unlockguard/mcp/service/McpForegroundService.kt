package com.unlockguard.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.unlockguard.mcp.MainApplication
import com.unlockguard.mcp.R
import com.unlockguard.mcp.core.AppGraph
import com.unlockguard.mcp.ui.MainActivity
import com.unlockguard.mcp.ui.overlay.OverlayBallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 常驻 MCP 服务：托管 HTTP Server + WifiLock + 常驻通知 + Doze 豁免 + 网络恢复自启。
 * 声明 foregroundServiceType="specialUse"（见 Manifest）。
 */
class McpForegroundService : Service() {

    private lateinit var graph: AppGraph
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** 观察悬浮球开关；服务销毁时一并取消，避免回调打到已卸载的窗口 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fabJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = (application as MainApplication).graph
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        acquireWifiLock()
        registerNetworkRecovery()
        graph.startServer()
        attachFab()
        return START_STICKY
    }

    override fun onDestroy() {
        graph.stopServer()
        releaseWifiLock()
        unregisterNetworkRecovery()
        OverlayBallManager.hide()
        fabJob = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 挂载系统悬浮球，并跟随设置页开关实时增删。
     *
     * 生命周期挂在服务上（而不是 Activity），这是"退出应用后悬浮球仍在"的关键：
     * 服务是常驻的，窗口由 WindowManager 直接管理，与任务栈无关。
     */
    private fun attachFab() {
        applyFab(graph.fabOn.value)
        if (fabJob == null) {
            fabJob = scope.launch { graph.fabOn.collect { applyFab(it) } }
        }
    }

    private fun applyFab(on: Boolean) {
        if (!on) {
            OverlayBallManager.hide()
            return
        }
        if (!OverlayBallManager.show(this)) {
            Log.w(TAG, "悬浮球未挂载：缺少「显示在其他应用上层」权限")
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 占位，建议替换为品牌图标
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun acquireWifiLock() {
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ug_mcp").apply { acquire() }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    private fun registerNetworkRecovery() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // WiFi 恢复：服务不在运行则拉起；已在运行仅刷新地址
                if (!graph.serviceOn.value) graph.startServer()
                else graph.refreshAddresses()
            }
        }
        cm.registerNetworkCallback(req, netCallback!!, Handler(Looper.getMainLooper()))
    }

    private fun unregisterNetworkRecovery() {
        netCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        netCallback = null
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "mcp_service"
        private const val TAG = "McpForegroundService"
    }
}
