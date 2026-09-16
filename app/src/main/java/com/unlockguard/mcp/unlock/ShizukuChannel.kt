package com.unlockguard.mcp.unlock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.unlockguard.mcp.core.ShizukuGate
import com.unlockguard.mcp.domain.CommandGuard
import com.unlockguard.mcp.domain.UnlockChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主解锁通道：Shizuku。
 *
 * ## 执行位置（本类最重要的一件事）
 * 事件注入**不在本进程执行**，而是通过 [ShizukuUserServiceHub] 把调用转给
 * `shizuku_server` 进程里的 [UnlockUserService]（shell/root 身份）。
 * 原因见 [IUnlockUserService] 的注释：`INJECT_EVENTS` 判定的是 uid，
 * 应用进程再怎样反射都拿不到，只有特权 uid 才能注入。
 *
 * 因此 [isAvailable] 只回答「有没有授权」，真正能不能注入要等 [unlock] 里绑定完
 * UserService、拿到 [IUnlockUserService.probe] 的结果才知道 —— 二者分开暴露，
 * 避免 UI 把「已授权」误显示成「一定能解锁」。
 */
/**
 * 一次 shell 执行的结果。
 *
 * 必须区分两件事：
 * - [executed]：命令**有没有跑起来**（未超时、进程与 Binder 都正常）；
 * - [exitCode]：命令**自己返回的退出码**（`ls /nope` 会正常执行并返回 1）。
 * 二者混为一谈会把「命令跑了但失败」误报成「工具故障」。
 */
data class ShellResult(
    val executed: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    /** executed=false 时的原因：未授权 / UserService 未就绪 / 异常信息 / 被护栏拦截 */
    val note: String = "",
    /** 是否被安全护栏拦截（区别于其它执行失败，需回报专门的错误码） */
    val blocked: Boolean = false,
) {
    /** 命令执行完毕且退出码为 0 */
    val succeeded: Boolean get() = executed && !timedOut && exitCode == 0
}

class ShizukuChannel(private val context: Context) {

    /**
     * 主通道是否具备基本条件：已授权 **且** 服务端是特权身份。
     *
     * uid 判定放宽为 `{0(root), 2000(shell/adb/无线调试)}`：
     * 旧实现只认 2000，root 启动 Shizuku 的设备会被误判为不可用。
     */
    fun isAvailable(): Boolean {
        if (!ShizukuGate.granted()) return false
        val uid = ShizukuGate.serverUid()
        return uid == 0 || uid == 2000
    }

    /** UserService 是否已就绪（会触发绑定，可能耗时数百毫秒） */
    suspend fun ready(): Boolean = ShizukuUserServiceHub.obtain(context) != null

    /**
     * 走 Shizuku 主通道解锁：唤醒 → 上滑呼出密码界面 → 输入 PIN → 确认。
     *
     * 全过程记录为 [UnlockStep] 列表返回，是否真的解开由调用方用锁屏状态互校
     * （本方法只负责把事件注入出去，不谎报结果）。
     */
    suspend fun unlock(pin: String): InjectReport = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val steps = mutableListOf<UnlockStep>()

        fun finish(injected: Boolean, note: String = "") = InjectReport(
            channel = UnlockChannel.SHIZUKU,
            backend = "-",
            steps = steps.toList(),
            injected = injected,
            unlocked = false, // 由引擎用 KeyguardManager 复核后填最终值
            elapsedMs = SystemClock.elapsedRealtime() - started,
            note = note,
        )

        if (!isAvailable()) {
            steps += UnlockStep("Shizuku 授权", false, "未授权或服务端非特权身份")
            return@withContext finish(false, "Shizuku 不可用")
        }
        steps += UnlockStep("Shizuku 授权", true, "服务端 uid=${ShizukuGate.serverUid()}")

        val svc = ShizukuUserServiceHub.obtain(context)
            ?: run {
                steps += UnlockStep("绑定时注入服务", false, "UserService 绑定超时或失败")
                return@withContext finish(false, "主通道不可用：UserService 未就绪")
            }

        // 后端探测带超时：Binder 无内建超时，transact 卡死无法被协程中断，
        // 但 withTimeoutOrNull 能让「调用方」及时超时返回，避免占死 Ktor 工作线程。
        val backend = withTimeoutOrNull(3000) { svc.probe() } ?: "none"
        steps += UnlockStep("绑定时注入服务", backend != "none", "注入后端：$backend")
        if (backend == "none") {
            return@withContext finish(false, "主通道不可用：无可用注入后端")
        }

        val (w, h) = screenSize()
        val cx = w / 2f

        // 1. 唤醒屏幕（SLEEP/POWER 是 toggle，必须用 WAKEUP 避免"唤醒时反而熄灭"）
        val woke = svc.injectKey(KeyEvent.KEYCODE_WAKEUP)
        steps += UnlockStep("唤醒屏幕", woke, "KEYCODE_WAKEUP(224)")
        delay(280)

        // 2. 上滑呼出 PIN 键盘
        val swiped = svc.injectSwipe(cx, h * 0.82f, cx, h * 0.20f, 180)
        steps += UnlockStep("上滑呼出密码界面", swiped, "垂直上滑")
        delay(420)

        // 3. 逐位输入 PIN
        val typed = svc.injectDigits(pin, 70)
        steps += UnlockStep("输入 PIN", typed, "${pin.length} 位数字")
        delay(220)

        // 4. 确认（部分 ROM 在 PIN 长度匹配时自动提交，此步为兼容手动确认的 ROM）
        val confirmed = svc.injectKey(KeyEvent.KEYCODE_ENTER)
        steps += UnlockStep("提交解锁", confirmed, "KEYCODE_ENTER(66)")
        delay(320)

        val injected = woke && swiped && typed && confirmed
        InjectReport(
            channel = UnlockChannel.SHIZUKU,
            backend = backend,
            steps = steps.toList(),
            injected = injected,
            unlocked = false,
            elapsedMs = SystemClock.elapsedRealtime() - started,
        )
    }

    /** 熄屏（SLEEP=223，非 POWER toggle，避免误点亮） */
    suspend fun lock(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val svc = ShizukuUserServiceHub.obtain(context) ?: return@withContext false
            svc.injectKey(KeyEvent.KEYCODE_SLEEP)
        }.getOrDefault(false)
    }

    /**
     * 以 **shell(2000) / root(0) 身份**执行一条 shell 命令（等价于手机连电脑后的 `adb shell <cmd>`）。
     *
     * 执行位置与 [unlock] 同理：命令不在本应用进程跑，而是转交给 shizuku_server 进程里的
     * [UnlockUserService]，因此拿到的是 adb 级权限，而非应用自身权限 —— 这正是
     * 「亮度调节 / adb 命令」这类能力必须依赖 Shizuku 的原因。
     *
     * 未授权 Shizuku 或 UserService 未就绪时不抛异常，而是返回 executed=false + 原因，
     * 由上层转成明确的 SHIZUKU_UNAVAILABLE 错误码。
     */
    suspend fun exec(cmd: String, timeoutMs: Int = DEFAULT_EXEC_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext ShellResult(
                    executed = false, exitCode = -1, stdout = "", stderr = "",
                    timedOut = false, note = "Shizuku 未授权或服务端非特权身份",
                )
            }
            // 安全护栏：破坏性命令在进入 shell 之前就拦掉（此时还没绑定 UserService，开销最小）
            CommandGuard.check(cmd)?.let { reason ->
                return@withContext ShellResult(
                    executed = false, exitCode = -1, stdout = "", stderr = "",
                    timedOut = false, note = reason, blocked = true,
                )
            }

            val svc = ShizukuUserServiceHub.obtain(context)
                ?: return@withContext ShellResult(
                    executed = false, exitCode = -1, stdout = "", stderr = "",
                    timedOut = false, note = "Shizuku UserService 未就绪",
                )

            // Binder 无内建超时，外层再兜一层；超时仅放弃等待，
            // 命令仍由服务端自身的超时兜底终止。
            val sec = (timeoutMs / 1000).coerceIn(1, 60)
            val raw = withTimeoutOrNull(timeoutMs + 5_000L) { svc.exec(cmd, sec) }
                ?: return@withContext ShellResult(
                    executed = false, exitCode = -1, stdout = "", stderr = "",
                    timedOut = true, note = "Binder 调用超时（命令可能仍在服务端执行）",
                )

            parseShellResult(raw)
        }

    /**
     * 调节屏幕亮度。
     *
     * Android 原生亮度值是 **0–255**（`settings system screen_brightness`）。
     * 自动亮度开启时系统会持续改写该值、手动设置的会被覆盖，所以切到手动档时必须
     * 把 `screen_brightness_mode` 一并置 0（0=手动，1=自动）。
     */
    suspend fun setBrightness(level: Int, auto: Boolean): ShellResult {
        val mode = if (auto) 1 else 0
        val r = exec(
            "settings put system screen_brightness $level && settings put system screen_brightness_mode $mode",
            timeoutMs = 8_000,
        )
        if (!r.succeeded) return r
        // 回读核对：退出码为 0 只代表命令跑了，不代表值真的落盘（ROM 可能拦截 settings put）
        val actual = exec("settings get system screen_brightness", timeoutMs = 5_000)
            .stdout.trim().toIntOrNull()
        return when {
            actual == null -> r.copy(note = "已下发但回读失败，无法确认是否生效")
            actual != level -> r.copy(note = "回读亮度为 $actual，与写入值 $level 不一致")
            else -> r
        }
    }

    /** 解析服务端返回的 JSON（{ exitCode, stdout, stderr, timedOut, error }） */
    private fun parseShellResult(raw: String): ShellResult {
        if (raw.isBlank()) {
            return ShellResult(false, -1, "", "", false, "服务端返回为空（UserService 可能已崩溃）")
        }
        return runCatching {
            val o = org.json.JSONObject(raw)
            val timedOut = o.optBoolean("timedOut", false)
            val err = o.optString("error", "")
            ShellResult(
                executed = !timedOut && err.isBlank(),
                exitCode = o.optInt("exitCode", -1),
                stdout = o.optString("stdout", ""),
                stderr = o.optString("stderr", ""),
                timedOut = timedOut,
                note = err,
            )
        }.getOrElse { e ->
            ShellResult(false, -1, "", raw.take(500), false, "结果解析失败：${e.message}")
        }
    }

    /**
     * 屏幕物理尺寸（含系统栏）。注入事件用的是物理屏幕坐标，
     * 故不能用 resources.displayMetrics（在部分场景下已扣除系统栏）。
     */
    private fun screenSize(): Pair<Float, Float> {
        runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            val b = wm?.currentWindowMetrics?.bounds
            if (b != null && b.width() > 0 && b.height() > 0) {
                return@runCatching b.width().toFloat() to b.height().toFloat()
            }
            null
        }.getOrNull()?.let { return it }

        val dm = context.resources.displayMetrics
        Log.w(TAG, "回退到 displayMetrics 取屏幕尺寸")
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    private companion object {
        const val TAG = "ShizukuChannel"
        /** shell 命令默认超时（毫秒） */
        const val DEFAULT_EXEC_TIMEOUT_MS = 15_000
    }
}
