package com.unlockguard.mcp.unlock

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Shizuku UserService 跨进程接口 —— 主解锁通道的真正执行体。
 *
 * ## 为什么必须这样做（历史缺陷）
 * 旧实现把 `InputManager.injectInputEvent` 反射调用放在**本应用进程**里执行。
 * 但 `INJECT_EVENTS` 是 `signature|privileged` 级权限，普通应用进程（uid=10xxx）无论如何都拿不到，
 * 调用必然抛 `SecurityException`。也就是说：**Shizuku 就算授权成功，主通道也从来没有真正注入过事件**
 * —— 表现就是每次解锁都先走 Shizuku 白忙一趟、再靠无障碍兜底。
 *
 * ## 正解
 * Shizuku 的 UserService 会把下面这个 Binder 实现加载进 **shizuku_server 进程** 执行，
 * 该进程以 shell(uid 2000) 或 root(uid 0) 身份运行，**具备注入能力**
 * （`adb shell input` 能点屏幕，走的就是同一个 uid 的同一套权限）。
 * 于是：本进程只负责跨进程调用，真正的注入发生在有权限的进程里。
 *
 * ## 为什么手写 Binder 协议而不走 AIDL
 * 参数只用 float / int / boolean / String 这些 primitive，手写 `Parcel` 读写即可完成，
 * 既省掉 AIDL 工具链的构建配置，也避免跨 classloader 解析 Parcelable 的坑。
 */
interface IUnlockUserService {

    /**
     * 注入后端自检，返回实际可用策略：
     * - `inputmanager`：反射 `InputManager.injectInputEvent`（毫秒级，首选）
     * - `shell`：退化为执行 `input` 命令（每次 fork 子进程，约百毫秒，但最稳）
     * - `none`：都不可用
     */
    fun probe(): String

    fun injectTap(x: Float, y: Float): Boolean

    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean

    fun injectKey(keyCode: Int): Boolean

    /** 逐位输入数字 PIN（KEYCODE_0..9），位间留 gapMs 给锁屏键盘响应 */
    fun injectDigits(digits: String, gapMs: Int): Boolean

    /**
     * 以本进程（shell=2000 / root=0）身份执行一条 shell 命令。
     *
     * @return JSON 字符串 `{ exitCode, stdout, stderr, timedOut, error }`。
     *         输出会在服务端截断，避免超出 Binder 事务约 1MB 的上限
     *         （`dumpsys` 之类大输出命令很容易撑爆，必须截断）。
     */
    fun exec(cmd: String, timeoutSec: Int): String

    /** 结束本 UserService 进程 */
    fun exit()

    /** 服务端实现基类：跑在 Shizuku 进程内，直接实现上面几个方法即可 */
    abstract class Stub : Binder(), IUnlockUserService {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TR_PROBE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val r = probe()
                    reply?.apply { writeNoException(); writeString(r) }
                    return true
                }

                TR_TAP -> {
                    data.enforceInterface(DESCRIPTOR)
                    val r = injectTap(data.readFloat(), data.readFloat())
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }

                TR_SWIPE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val x1 = data.readFloat()
                    val y1 = data.readFloat()
                    val x2 = data.readFloat()
                    val y2 = data.readFloat()
                    val dur = data.readInt()
                    val r = injectSwipe(x1, y1, x2, y2, dur)
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }

                TR_KEY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val r = injectKey(data.readInt())
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }

                TR_DIGITS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val digits = data.readString().orEmpty()
                    val gap = data.readInt()
                    val r = injectDigits(digits, gap)
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }

                TR_EXEC -> {
                    data.enforceInterface(DESCRIPTOR)
                    val r = exec(data.readString().orEmpty(), data.readInt())
                    reply?.apply { writeNoException(); writeString(r) }
                    return true
                }

                TR_EXIT -> {
                    data.enforceInterface(DESCRIPTOR)
                    exit()
                    reply?.apply { writeNoException() }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        companion object {
            /** 转换成本地实现（同进程直接返回，跨进程包一层代理） */
            @JvmStatic
            fun asInterface(binder: IBinder?): IUnlockUserService? {
                if (binder == null) return null
                if (!runCatching { binder.isBinderAlive }.getOrDefault(false)) return null
                (binder as? IUnlockUserService)?.let { return it }
                return Proxy(binder)
            }
        }
    }

    /** 客户端代理：把每次调用打成一次 Binder transaction */
    private class Proxy(private val remote: IBinder) : IUnlockUserService {

        override fun probe(): String = callString(TR_PROBE, 0)

        override fun injectTap(x: Float, y: Float): Boolean = callBool(TR_TAP, 2) {
            it.writeFloat(x); it.writeFloat(y)
        }

        override fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean =
            callBool(TR_SWIPE, 5) {
                it.writeFloat(x1); it.writeFloat(y1); it.writeFloat(x2); it.writeFloat(y2)
                it.writeInt(durationMs)
            }

        override fun injectKey(keyCode: Int): Boolean = callBool(TR_KEY, 1) { it.writeInt(keyCode) }

        override fun injectDigits(digits: String, gapMs: Int): Boolean = callBool(TR_DIGITS, 2) {
            it.writeString(digits); it.writeInt(gapMs)
        }

        override fun exec(cmd: String, timeoutSec: Int): String = callString(TR_EXEC) {
            it.writeString(cmd); it.writeInt(timeoutSec)
        }

        override fun exit() {
            val data = Parcel.obtain()
            runCatching {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TR_EXIT, data, null, IBinder.FLAG_ONEWAY)
            }
            data.recycle()
        }

        /** 参数个数只用于预分配，写什么由 [write] 决定 */
        private inline fun callBool(code: Int, argCount: Int, write: (Parcel) -> Unit): Boolean =
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    write(data)
                    remote.transact(code, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }.getOrDefault(false)

        /** 带参数的字符串调用：写入内容由 [write] 决定 */
        private inline fun callString(code: Int, write: (Parcel) -> Unit): String = runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                remote.transact(code, data, reply, 0)
                reply.readException()
                reply.readString().orEmpty()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.getOrDefault("")

        private fun callString(code: Int, argCount: Int): String = runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(code, data, reply, 0)
                reply.readException()
                reply.readString().orEmpty()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.getOrDefault("none")
    }
}

private const val DESCRIPTOR = "com.unlockguard.mcp.unlock.IUnlockUserService"

private const val TR_PROBE = IBinder.FIRST_CALL_TRANSACTION
private const val TR_TAP = IBinder.FIRST_CALL_TRANSACTION + 1
private const val TR_SWIPE = IBinder.FIRST_CALL_TRANSACTION + 2
private const val TR_KEY = IBinder.FIRST_CALL_TRANSACTION + 3
private const val TR_DIGITS = IBinder.FIRST_CALL_TRANSACTION + 4
private const val TR_EXIT = IBinder.FIRST_CALL_TRANSACTION + 5
private const val TR_EXEC = IBinder.FIRST_CALL_TRANSACTION + 6
