package com.unlockguard.mcp.unlock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.unlockguard.mcp.core.ShizukuGate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku UserService 的绑定与持有。
 *
 * 一次绑定、长期复用：解锁时连续要注入 7~10 次事件（唤醒/上滑/4 位 PIN/确认），
 * 每次都重新 bind 会慢且抖动，因此这里把连接缓存下来。
 *
 * 绑定是**异步**的（Shizuku 收到 binder 后才回调 `onServiceConnected`），
 * 所以对外暴露 suspend 的 [obtain]：调用方挂起等待，不阻塞主线程
 * （回调本身在主线程派发，主线程被阻塞会直接死锁）。
 */
object ShizukuUserServiceHub {

    private const val TAG = "UnlockUserService"
    private const val VERSION = 1

    @Volatile
    private var service: IUnlockUserService? = null

    /** 服务端 binder，用于存活探测（接口本身不暴露 IBinder，避免继承体系歧义） */
    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var awaiting: ((IUnlockUserService?) -> Unit)? = null

    @Volatile
    private var cachedArgs: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IUnlockUserService.Stub.asInterface(binder)
            service = s
            this@ShizukuUserServiceHub.binder = binder
            val backend = runCatching { s?.probe() }.getOrNull()
            Log.i(TAG, "UserService 已连接 · 注入后端=$backend")
            awaiting?.invoke(s)
            awaiting = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService 断开")
            service = null
            binder = null
        }
    }

    private fun argsFor(ctx: Context): Shizuku.UserServiceArgs =
        cachedArgs ?: Shizuku.UserServiceArgs(
            // className 会被 Shizuku 用于在 UserService 进程内 loadClass
            ComponentName(ctx.packageName, UnlockUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("unlock")
            .debuggable(false)
            .version(VERSION)
            .also { cachedArgs = it }

    /** 已绑定且 binder 存活时直接返回，不产生任何 IPC */
    fun peek(): IUnlockUserService? {
        val b = binder ?: return null
        if (!runCatching { b.isBinderAlive }.getOrDefault(false)) {
            service = null
            binder = null
            return null
        }
        return service
    }

    /**
     * 确保拿到可用的 UserService。
     * @return null 表示：未授权 Shizuku / 服务未运行 / 绑定超时（调用方据此降级到无障碍）
     */
    suspend fun obtain(ctx: Context, timeoutMs: Long = BIND_TIMEOUT_MS): IUnlockUserService? {
        peek()?.let { return it }
        if (!ShizukuGate.granted()) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                awaiting = { s -> if (cont.isActive) cont.resume(s) }
                cont.invokeOnCancellation { awaiting = null }
                runCatching { Shizuku.bindUserService(argsFor(ctx), connection) }
                    .onFailure { e ->
                        Log.w(TAG, "bindUserService 调用失败：${e.javaClass.simpleName} ${e.message}")
                        awaiting = null
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }
    }

    /** 断开并结束服务端进程（Shizuku 恢复/授权撤销时调用） */
    fun release(ctx: Context) {
        runCatching { Shizuku.unbindUserService(argsFor(ctx), connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService 失败：${it.javaClass.simpleName}") }
        service = null
        binder = null
    }

    private const val BIND_TIMEOUT_MS = 6000L
}
