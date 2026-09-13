package com.unlockguard.mcp.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.unlockguard.mcp.R
import kotlin.math.abs

/**
 * 系统悬浮球 —— 绘制与交互全在纯 [View] 里完成（不用 Compose）。
 *
 * 为什么不用 Compose：这个 View 被挂在 `TYPE_APPLICATION_OVERLAY` 的独立窗口上，
 * ComposeView 在该场景需要手动补 `ViewTreeLifecycleOwner` / SavedStateRegistry 等
 * 一整套宿主环境，任何一处缺失都会在退出应用后白屏。纯 View 绘制零依赖、生命周期简单，
 * 且配色直接取设计令牌同一套色值，视觉与 App 内一致。
 *
 * 交互：拖动移动位置（松手后吸附在屏幕范围内并持久化），单击回到 App。
 */
@SuppressLint("ViewConstructor")
class OverlayBallView(
    context: Context,
    private val onTap: () -> Unit,
    private val onMove: (dx: Int, dy: Int, up: Boolean) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 与设计稿 .fab 一致的柔和投影
        setShadowLayer(6f * density, 0f, 2f * density, 0x44000000)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }

    private val shield: Drawable? =
        runCatching { context.getDrawable(R.drawable.ic_overlay_shield) }.getOrNull()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    init {
        // setShadowLayer 只在软件渲染层生效；本 View 静态且尺寸很小，开销可忽略
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - strokePaint.strokeWidth / 2f - density

        fillPaint.shader = LinearGradient(0f, 0f, w, h, BRAND, BRAND_DEEP, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)

        shield?.let {
            val s = (22f * density).toInt()
            val left = (cx - s / 2f).toInt()
            val top = (cy - s / 2f).toInt()
            it.setBounds(left, top, left + s, top + s)
            it.draw(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    dragging = abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                }
                if (dragging) {
                    onMove((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt(), false)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) onMove(0, 0, true) else onTap()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onMove(0, 0, true)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** 与 Theme.kt 的 IrisBlue / IrisBlueDeep 保持同一套色值 */
        val BRAND = 0xFF4C5FD5.toInt()
        val BRAND_DEEP = 0xFF3A49BE.toInt()
    }
}
