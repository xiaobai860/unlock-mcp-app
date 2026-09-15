package com.unlockguard.mcp

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.unlockguard.mcp.accessibility.AccessibilityKeeper
import com.unlockguard.mcp.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 持有 WRITE_SECURE_SETTINGS 时，应用启动即尝试把无障碍服务写回系统启用列表（与开机自启互补）
        CoroutineScope(Dispatchers.IO).launch { AccessibilityKeeper.ensureEnabled(applicationContext) }
        // 冷启动即把 Shizuku binder 拉到本进程：
        // Shizuku 的「授权」是按 uid 存在 Shizuku 管理器里的，但本进程要读授权
        // （Shizuku.pingBinder()/checkSelfPermission()）必须先拿到 binder。provider 进程**不会**在
        // 「更新 / 重启后的全新进程」里自动派发 binder，必须显式 requestBinderForNonProviderProcess
        // 触发管理器回推；否则 ping() 长期为 false、UI 永远显示「未授权」，而管理器仍显示已授权
        // （uid 没变）—— 这正是「更新后 Shizuku 显示已授权、App 内却未授权」的根因。
        // 点「去授权」会顺带 requestBinder，所以首次手动授权正常；但更新/重启这种「无人点授权」路径就暴露了。
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(applicationContext) }
        // binder 送达 / 断开留痕：Shizuku 的 binder 是异步送达的，多进程与冷启动场景下
        // pingBinder() 短期为 false 属正常现象。有这两条日志才能把「服务没起」
        // 与「只是还没连上」区分开，而不是把用户误送去 Shizuku 的服务教程页。
        runCatching {
            Shizuku.addBinderReceivedListener { Log.i(SHIZUKU_LOG, "binder 已就绪") }
            Shizuku.addBinderDeadListener { Log.w(SHIZUKU_LOG, "binder 已断开") }
        }
        // 授权结果留痕：Shizuku 弹窗被点「允许 / 拒绝」后回调。
        // 有了它，"点了没反应"这类问题才能从日志上区分是没弹窗、还是弹了被拒。
        runCatching {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i(
                    SHIZUKU_LOG,
                    "onRequestPermissionResult: code=$requestCode " +
                        "granted=${grantResult == PackageManager.PERMISSION_GRANTED}",
                )
            }
        }
        // 载入审计最近 N 条
        CoroutineScope(Dispatchers.IO).launch { graph.audit.reload() }
    }

    private companion object {
        /** 与 ShizukuGate 同 tag，便于一条 grep 捞出整条授权链路 */
        const val SHIZUKU_LOG = "ShizukuGate"
    }
}
