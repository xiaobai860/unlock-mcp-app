package com.unlockguard.mcp

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.unlockguard.mcp.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 尝试绑定 Shizuku（其 App 运行时可用；权限授予由 Shizuku 自身 UI 完成）
        runCatching { Shizuku.pingBinder() }
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
