package com.unlockguard.mcp.unlock

import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 服务端实现。
 *
 * 这个类**不在本应用进程里运行**：Shizuku 会把它加载进 `shizuku_server` 进程，
 * 以 shell(2000) / root(0) 身份执行，从而具备 `INJECT_EVENTS` 能力与 shell 执行能力。
 *
 * 约束（由 Shizuku 加载机制决定）：
 * - 必须是 public 类且有无参构造函数；
 * - 只依赖 Android framework 与同 APK 内的类，不引用 Application / DI 容器等进程态对象。
 */
class UnlockUserService : IUnlockUserService.Stub() {

    private val injector: InputInjector by lazy { InputInjector() }

    override fun probe(): String = injector.backend

    override fun injectTap(x: Float, y: Float): Boolean = injector.tap(x, y)

    override fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean =
        injector.swipe(x1, y1, x2, y2, durationMs)

    override fun injectKey(keyCode: Int): Boolean = injector.key(keyCode)

    override fun injectDigits(digits: String, gapMs: Int): Boolean = injector.digits(digits, gapMs)

    /**
     * 以本进程（shell=2000 / root=0）身份执行一条 shell 命令。
     *
     * 三条硬约束（都是 `Runtime.exec` 的经典陷阱，踩中任意一条都会挂）：
     *
     * 1. **必须并发读 stdout / stderr**：子进程输出打满管道缓冲区后会阻塞不退出，
     *    而单线程「先读完 stdout 再读 stderr」会与之互相等待 —— 死锁。
     * 2. **必须带超时**：无参 `waitFor()` 会无限等待，一条卡死的命令能永久占住
     *    这条 Binder 调用，进而拖垮整个 MCP 请求。
     * 3. **必须截断输出**：Binder 事务上限约 1MB，`dumpsys` 之类命令轻松超出，
     *    一旦超出抛 `TransactionTooLargeException`，连错误信息都传不回调用方。
     */
    override fun exec(cmd: String, timeoutSec: Int): String {
        val out = StringBuilder()
        val err = StringBuilder()
        var exitCode = -1
        var timedOut = false
        var error = ""

        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val tOut = Thread { drain(p.inputStream, out) }
            val tErr = Thread { drain(p.errorStream, err) }
            tOut.start()
            tErr.start()

            if (!p.waitFor(timeoutSec.coerceIn(1, 60).toLong(), TimeUnit.SECONDS)) {
                timedOut = true
                p.destroyForcibly()
            }
            // 给读线程一点收尾时间，但绝不无限等
            tOut.join(1000)
            tErr.join(1000)
            exitCode = if (timedOut) -1 else runCatching { p.exitValue() }.getOrDefault(-1)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }

        // 用 framework 自带的 org.json 组装，避免手写字符串拼接的转义错误
        return org.json.JSONObject().apply {
            put("exitCode", exitCode)
            put("stdout", out.toString())
            put("stderr", err.toString())
            put("timedOut", timedOut)
            put("error", error)
        }.toString()
    }

    /**
     * 读取流追加到 [sb]；超过 [MAX_OUTPUT] 后**仍继续读取**（把管道抽干，
     * 否则子进程写阻塞不退出），只是不再拼接保存。
     */
    private fun drain(ins: InputStream, sb: StringBuilder) {
        runCatching {
            ins.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    if (sb.length < MAX_OUTPUT) sb.append(buf, 0, n)
                }
            }
        }
    }

    override fun exit() {
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    private companion object {
        /** 单条流保留的最大字符数（stdout / stderr 各计） */
        const val MAX_OUTPUT = 8000
    }
}
