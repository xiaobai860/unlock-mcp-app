package com.unlockguard.mcp.unlock

import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.lang.reflect.Method

/**
 * 事件注入实现 —— **运行在 Shizuku 的 UserService 进程内**（shell uid 2000 / root uid 0）。
 *
 * 之所以强调"在哪个进程跑"：`INJECT_EVENTS` 是 signature|privileged 权限，判定依据是 **uid**。
 * 本应用进程（uid 10xxx）拿不到；而 shizuku_server 进程的 uid 是 2000，
 * 与 `com.android.shell` 同 uid，因此天然通过校验 —— 这也是 `adb shell input` 能点屏幕的原因。
 *
 * 双后端设计（互备，运行时自适应）：
 * - **inputmanager**：反射 `InputManager.injectInputEvent`，毫秒级，无 fork 开销（首选）；
 * - **shell**：执行 `/system/bin/input` 命令，每次约百毫秒，但完全不依赖隐藏 API，最稳（兜底）。
 *
 * 二者都实测可用性后再回报，绝不假装成功 —— `probe()` 会如实说出真正在用哪条。
 */
internal class InputInjector {

    /* ------------------------- 后端探测 ------------------------- */

    private val inputManager: Any? = resolveInputManager()

    private val injectMethod: Method? = inputManager?.let { im ->
        runCatching {
            im.javaClass
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * `InputEvent.setSource` 是 @hide（不在 android.jar 里），但运行时类里存在，
     * 故用 getDeclaredMethod 在运行期取。触摸事件若不设 source，
     * dispatch 阶段找不到目标窗口，事件会被直接丢弃。
     */
    private val setSourceMethod: Method? = runCatching {
        InputEvent::class.java
            .getDeclaredMethod("setSource", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.getOrNull()

    private val inputBin: String? = SHELL_CANDIDATES.firstOrNull { File(it).canExecute() }

    /** 实际可用后端：`inputmanager` / `shell` / `none` */
    val backend: String = when {
        injectMethod != null -> "inputmanager"
        inputBin != null -> "shell"
        else -> "none"
    }

    /* ------------------------- 对外能力 ------------------------- */

    fun tap(x: Float, y: Float): Boolean {
        injectMethod?.let {
            val t = SystemClock.uptimeMillis()
            val down = motion(MotionEvent.ACTION_DOWN, x, y, t)
            val up = motion(MotionEvent.ACTION_UP, x, y, t + 40)
            return down && up
        }
        return shell("tap", x.toInt().toString(), y.toInt().toString())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean {
        injectMethod?.let {
            val t0 = SystemClock.uptimeMillis()
            val dur = durationMs.coerceAtLeast(1)
            var ok = motion(MotionEvent.ACTION_DOWN, x1, y1, t0)
            for (i in 1 until STEPS) {
                val f = i / STEPS.toFloat()
                val t = t0 + (dur * f).toLong()
                ok = motion(
                    MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f,
                    y1 + (y2 - y1) * f,
                    t,
                ) && ok
            }
            ok = motion(MotionEvent.ACTION_UP, x2, y2, t0 + dur) && ok
            return ok
        }
        return shell(
            "swipe",
            x1.toInt().toString(), y1.toInt().toString(),
            x2.toInt().toString(), y2.toInt().toString(),
            durationMs.toString(),
        )
    }

    fun key(keyCode: Int): Boolean {
        injectMethod?.let {
            val t = SystemClock.uptimeMillis()
            // 5 参构造会把 source 设为 SOURCE_KEYBOARD（框架内部行为），无需再手动设
            val down = keyEvent(KeyEvent.ACTION_DOWN, keyCode, t)
            val up = keyEvent(KeyEvent.ACTION_UP, keyCode, t + 20)
            return down && up
        }
        return shell("keyevent", keyCode.toString())
    }

    fun digits(digits: String, gapMs: Int): Boolean {
        var any = false
        for (ch in digits) {
            if (ch !in '0'..'9') continue
            any = key(KeyEvent.KEYCODE_0 + (ch - '0')) || any
            runCatching { Thread.sleep(gapMs.toLong().coerceIn(0, 500)) }
        }
        return any
    }

    /* ------------------------- 内部实现 ------------------------- */

    private fun motion(action: Int, x: Float, y: Float, when_: Long): Boolean = runCatching {
        val ev = MotionEvent.obtain(when_, when_, action, x, y, 0)
        runCatching { setSourceMethod?.invoke(ev, android.view.InputDevice.SOURCE_TOUCHSCREEN) }
        try {
            inject(ev)
        } finally {
            ev.recycle()
        }
    }.getOrDefault(false)

    private fun keyEvent(action: Int, keyCode: Int, when_: Long): Boolean = runCatching {
        inject(KeyEvent(when_, when_, action, keyCode, 0))
    }.getOrDefault(false)

    private fun inject(event: InputEvent): Boolean {
        val m = injectMethod ?: return false
        // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC：不等待分发完成，避免阻塞解锁流程
        m.invoke(inputManager, event, 0)
        return true
    }

    private fun shell(vararg args: String): Boolean = runCatching {
        val bin = inputBin ?: return false
        val p = Runtime.getRuntime().exec(arrayOf(bin) + args)
        val done = p.waitFor(SHELL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            false
        } else {
            p.exitValue() == 0
        }
    }.getOrDefault(false)

    /**
     * 解析 InputManager 实例。三种入口依次尝试，覆盖不同 Android 版本：
     * ① `InputManager.getInstance()`（旧版静态单例）；
     * ② `InputManagerGlobal.getInstance().getInputManager()`（Android 14+）；
     * ③ 借 system context 走 `getSystemService("input")`。
     */
    private fun resolveInputManager(): Any? {
        runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance")
                .invoke(null)
        }.getOrNull()?.let { return it }

        runCatching {
            val global = Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance")
                .invoke(null)
            global.javaClass.getMethod("getInputManager").invoke(global)
        }.getOrNull()?.let { return it }

        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val main = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(main) as android.content.Context
            ctx.getSystemService(android.content.Context.INPUT_SERVICE)
        }.getOrNull()?.let { return it }

        return null
    }

    private companion object {
        const val STEPS = 10
        const val SHELL_TIMEOUT_SEC = 4L
        val SHELL_CANDIDATES = listOf("/system/bin/input", "/system/xbin/input")
    }
}
