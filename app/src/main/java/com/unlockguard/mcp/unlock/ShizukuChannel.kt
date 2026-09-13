package com.unlockguard.mcp.unlock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import com.unlockguard.mcp.core.ShizukuGate
import com.unlockguard.mcp.domain.UnlockChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        val backend = runCatching { svc.probe() }.getOrDefault("none")
        steps += UnlockStep("绑定时注入服务", backend != "none", "注入后端：$backend")
        if (backend == "none") {
            return@withContext finish(false, "主通道不可用：无可用注入后端")
        }

        val (w, h) = screenSize()
        val cx = w / 2f

        // 1. 唤醒屏幕（SLEEP/POWER 是 toggle，必须用 WAKEUP 避免"唤醒时反而熄灭"）
        val woke = svc.injectKey(KeyEvent.KEYCODE_WAKEUP)
        steps += UnlockStep("唤醒屏幕", woke, "KEYCODE_WAKEUP(224)")
        Thread.sleep(280)

        // 2. 上滑呼出 PIN 键盘
        val swiped = svc.injectSwipe(cx, h * 0.82f, cx, h * 0.20f, 180)
        steps += UnlockStep("上滑呼出密码界面", swiped, "垂直上滑")
        Thread.sleep(420)

        // 3. 逐位输入 PIN
        val typed = svc.injectDigits(pin, 70)
        steps += UnlockStep("输入 PIN", typed, "${pin.length} 位数字")
        Thread.sleep(220)

        // 4. 确认（部分 ROM 在 PIN 长度匹配时自动提交，此步为兼容手动确认的 ROM）
        val confirmed = svc.injectKey(KeyEvent.KEYCODE_ENTER)
        steps += UnlockStep("提交解锁", confirmed, "KEYCODE_ENTER(66)")
        Thread.sleep(320)

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
    }
}
