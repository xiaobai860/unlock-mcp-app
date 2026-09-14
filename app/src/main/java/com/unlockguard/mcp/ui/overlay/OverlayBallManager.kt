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
    private const val KEY_SIZE = "ball_size_dp"
    private const val KEY_ALPHA = "ball_alpha"
    private const val KEY_PEEK = "ball_peek_dp"

    private const val DEFAULT_SIZE_DP = 56f
    private const val DEFAULT_ALPHA = 1.0f
    private const val DEFAULT_PEEK_DP = 16f
    private const val MIN_SIZE_DP = 40f
    private const val MAX_SIZE_DP = 96f
    private const val MIN_ALPHA = 0.3f
    private const val MAX_ALPHA = 1.0f
    private const val MIN_PEEK_DP = 0f
    private const val MAX_PEEK_DP = 48f
    private const val EDGE_DP = 16
    private const val DEFAULT_TOP_RATIO = 0.70f

    private var view: OverlayBallView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    private var screenW = 0
    private var screenH = 0
    private var sizePx = 0
    private var peekPx = 0
    private var curDensity = 1f
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
        val prefs = readPrefs(app)
        curDensity = density
        appCtx = app
        screenW = bounds.width()
        screenH = bounds.height()
        val size = (prefs.sizeDp * density).toInt().coerceAtLeast(1)
        sizePx = size
        peekPx = (prefs.peekDp * density).toInt()
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
            val (x0, y0) = loadPosition(app, bounds.width(), bounds.height(), size, density, peekPx)
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
                        // 松手后吸附到最近竖边，仅露出 peekPx（贴边程度）
                        cur.x = snapX(cur.x, sizePx, peekPx, screenW)
                        cur.y = cur.y.coerceIn(0, maxY)
                    } else {
                        cur.x += dx
                        cur.y += dy
                    }
                    runCatching { wm?.updateViewLayout(v, cur) }
                    if (up) savePosition(app, cur.x, cur.y)
                }
            },
        ).also { it.alpha = prefs.alpha }

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
        peekPx: Int,
    ): Pair<Int, Int> {
        val edge = (EDGE_DP * density).toInt()
        val fallbackX = (screenW - size - edge).coerceAtLeast(0)
        val fallbackY = ((screenH - size) * DEFAULT_TOP_RATIO).toInt().coerceAtLeast(0)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = sp.getInt(KEY_X, -1)
        val y = sp.getInt(KEY_Y, -1)
        if (x < 0 || y < 0) return fallbackX to fallbackY

        // 屏幕旋转 / 分辨率变化后旧坐标可能出界；贴边隐藏态下至少保证露出 peekPx
        val minX = (peekPx - size).coerceAtMost(0)
        val maxXLo = (screenW - peekPx).coerceAtLeast(screenW - size)
        return x.coerceIn(minX, maxXLo) to
            y.coerceIn(0, (screenH - size).coerceAtLeast(0))
    }

    private fun savePosition(ctx: Context, x: Int, y: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** 读取悬浮球外观偏好（尺寸/透明度/贴边露出），越界值夹回合法范围 */
    private fun readPrefs(ctx: Context): Prefs {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Prefs(
            sizeDp = sp.getFloat(KEY_SIZE, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
            alpha = sp.getFloat(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA),
            peekDp = sp.getFloat(KEY_PEEK, DEFAULT_PEEK_DP).coerceIn(MIN_PEEK_DP, MAX_PEEK_DP),
        )
    }

    private data class Prefs(val sizeDp: Float, val alpha: Float, val peekDp: Float)

    /** 松手/改贴边后吸附到最近竖边，仅露出 peekPx（为负即部分移出屏外） */
    private fun snapX(x: Int, size: Int, peek: Int, screenW: Int): Int {
        val leftDist = x
        val rightDist = (screenW - size) - x
        return if (leftDist <= rightDist) {
            (peek - size).coerceAtMost(0)
        } else {
            (screenW - peek).coerceIn(0, screenW - size)
        }
    }

    /* -------- 设置页实时调节（即使球未显示也会持久化，下次挂载生效） -------- */

    fun getSizeDp(ctx: Context): Float = readPrefs(ctx).sizeDp
    fun getAlpha(ctx: Context): Float = readPrefs(ctx).alpha
    fun getPeekDp(ctx: Context): Float = readPrefs(ctx).peekDp

    fun setSizeDp(ctx: Context, dp: Float) {
        val clamped = dp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SIZE, clamped).apply()
        val px = (clamped * curDensity).toInt().coerceAtLeast(1)
        sizePx = px
        val p = params
        val v = view
        if (p != null && v != null) {
            p.width = px
            p.height = px
            runCatching { wm?.updateViewLayout(v, p) }
            p.x = snapX(p.x, px, peekPx, screenW)
            p.y = p.y.coerceIn(0, maxY)
            runCatching { wm?.updateViewLayout(v, p) }
            savePosition(ctx, p.x, p.y)
        }
    }

    fun setAlpha(ctx: Context, a: Float) {
        val clamped = a.coerceIn(MIN_ALPHA, MAX_ALPHA)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_ALPHA, clamped).apply()
        view?.alpha = clamped
    }

    fun setPeekDp(ctx: Context, dp: Float) {
        val clamped = dp.coerceIn(MIN_PEEK_DP, MAX_PEEK_DP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_PEEK, clamped).apply()
        peekPx = (clamped * curDensity).toInt()
        val p = params
        val v = view
        if (p != null && v != null) {
            p.x = snapX(p.x, sizePx, peekPx, screenW)
            runCatching { wm?.updateViewLayout(v, p) }
            savePosition(ctx, p.x, p.y)
        }
    }
}
