package com.unlockguard.mcp.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.unlockguard.mcp.ui.MainActivity

/**
 * 系统级悬浮球的挂载/卸载。
 *
 * 使用 `TYPE_APPLICATION_OVERLAY` 窗口，**独立于应用 Activity 生命周期**：
 * 只要宿主前台服务在运行，用户退回桌面、杀掉任务栈、锁屏再唤醒，悬浮球都还在。
 * 这正是此前"退出应用后悬浮球消失"的根因所在 —— 旧实现是 Compose 里的一层覆盖，
 * 它属于 Activity 的窗口，Activity 一销毁就跟着没了。
 *
 * 位置在拖动结束后持久化，重新挂载时复原。
 */
object OverlayBallManager {

    private const val PREFS = "guard_overlay"
    private const val KEY_X = "ball_x"
    private const val KEY_Y = "ball_y"
    private const val SIZE_DP = 56
    private const val EDGE_DP = 16
    private const val DEFAULT_TOP_RATIO = 0.70f

    private var view: OverlayBallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var maxX = 0
    private var maxY = 0

    /** 是否已获「显示在其他应用上层」权限 */
    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun isShown(): Boolean = view != null

    /** @return true = 已挂上（或本来就在）；false = 无权限或挂载失败 */
    @Synchronized
    fun show(ctx: Context): Boolean {
        if (view != null) return true

        val app = ctx.applicationContext
        if (!canDraw(app)) return false

        val windowManager = app.getSystemService(WindowManager::class.java) ?: return false
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull() ?: return false

        val density = app.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt().coerceAtLeast(1)
        maxX = (bounds.width() - size).coerceAtLeast(0)
        maxY = (bounds.height() - size).coerceAtLeast(0)

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢输入焦点：否则会顶掉锁屏/输入法的焦点，反而干扰解锁
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (x0, y0) = loadPosition(app, bounds.width(), bounds.height(), size, density)
            x = x0
            y = y0
        }

        val ball = OverlayBallView(
            app,
            onTap = { openApp(app) },
            onMove = { dx, dy, up ->
                val cur = params
                val v = view
                if (cur != null && v != null) {
                    if (up) {
                        cur.x = cur.x.coerceIn(0, maxX)
                        cur.y = cur.y.coerceIn(0, maxY)
                    } else {
                        cur.x += dx
                        cur.y += dy
                    }
                    runCatching { wm?.updateViewLayout(v, cur) }
                    if (up) savePosition(app, cur.x, cur.y)
                }
            },
        )

        return runCatching {
            windowManager.addView(ball, lp)
            view = ball
            params = lp
            wm = windowManager
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun hide() {
        val v = view ?: return
        runCatching { wm?.removeViewImmediate(v) }
        view = null
        params = null
        wm = null
    }

    /** 权限页：用户尚未授予「显示在其他应用上层」时引导过去 */
    fun openOverlaySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /* ------------------------- 内部 ------------------------- */

    private fun openApp(ctx: Context) {
        // 持 SYSTEM_ALERT_WINDOW 的应用具备后台启动 Activity 的豁免，这里可以直接拉起主界面
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { ctx.startActivity(i) }
    }

    private fun loadPosition(
        ctx: Context,
        screenW: Int,
        screenH: Int,
        size: Int,
        density: Float,
    ): Pair<Int, Int> {
        val edge = (EDGE_DP * density).toInt()
        val fallbackX = (screenW - size - edge).coerceAtLeast(0)
        val fallbackY = ((screenH - size) * DEFAULT_TOP_RATIO).toInt().coerceAtLeast(0)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = sp.getInt(KEY_X, -1)
        val y = sp.getInt(KEY_Y, -1)
        if (x < 0 || y < 0) return fallbackX to fallbackY

        // 屏幕旋转 / 分辨率变化后旧坐标可能出界，重新夹回可见范围
        return x.coerceIn(0, (screenW - size).coerceAtLeast(0)) to
            y.coerceIn(0, (screenH - size).coerceAtLeast(0))
    }

    private fun savePosition(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
