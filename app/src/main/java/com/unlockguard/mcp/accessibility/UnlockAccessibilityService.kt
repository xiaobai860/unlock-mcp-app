package com.unlockguard.mcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.unlockguard.mcp.unlock.A11yUnlockResult
import com.unlockguard.mcp.unlock.AccessibilityBridge

/**
 * 解锁备通道无障碍服务。
 * 能力严格焊死：
 *  1) 仅在 Keyguard（com.android.systemui）窗口内输入预存 PIN；
 *  2) 仅操作 PIN 键盘数字节点与确认节点，绝不点击其它任何界面；
 *  3) 不提供通用点击/坐标能力（符合方案「九、明确不做」与第七章安全边界）。
 */
class UnlockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        AccessibilityBridge.attach(this)
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 被动监听；解锁由引擎在 tryUnlock 时主动调用 inputPinOnKeyguard
    }

    override fun onInterrupt() {}

    fun isKeyguardShown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.packageName == "com.android.systemui"
    }

    /**
     * 锁屏（熄屏上锁）—— `GLOBAL_ACTION_LOCK_SCREEN`，Android 9（API 28）起由系统提供。
     *
     * 这是 Google 官方推荐的第三方锁屏方式：效果等效于按电源键，
     * **指纹 / 人脸 / Smart Lock 照常可用**；而 DeviceAdmin 的 `lockNow()` 会强制进入
     * PRIMARY_BOUNCER，生物识别失效必须输 PIN —— 这正是本通道排在设备管理员之前的原因。
     * （Shizuku 的 KEYCODE_SLEEP 与之等效、同样保留生物识别，故本项目锁屏以 Shizuku 优先、本通道次之。）
     *
     * 注：`performGlobalAction` 是直接向系统服务下发全局动作，**不受本服务
     * `packageNames` 事件过滤的约束**，因此在任何界面都能生效。
     *
     * @return 系统是否接受了该动作。注意这**不等于**屏幕已锁上，
     *         调用方必须用 `KeyguardManager.isKeyguardLocked()` 复核（见 UnlockEngine.lock）。
     */
    fun lockScreen(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        Log.i(TAG_A11Y, "lockScreen: performGlobalAction(LOCK_SCREEN) 返回=$ok（等效按电源键，会熄屏）")
        return ok
    }

    /**
     * 点亮熄屏。
     *
     * 首选**合法系统 API**：`PowerManager.WakeLock` 带 `ACQUIRE_CAUSES_WAKEUP` 能直接点亮屏幕，
     * 这比"用全局动作拉起系统面板间接触发"可靠得多 —— 后者是 hack，ROM（如 HyperOS）可拦截，
     * 且依赖后台服务存活，Doze 下可能被延迟。
     *
     * 兜底才用全局动作（RECENTS / NOTIFICATIONS / QUICK_SETTINGS）间接触发点亮：
     * 它们是"要求显示可见故系统顺带亮屏"，并非唤醒契约。安全锁屏上拉起的是"锁屏之上的面板"，
     * 需再收起才回到 PIN 锁屏；部分 ROM 会直接拦截这些全局动作。
     *
     * @return 屏幕是否已处于交互态（亮屏）。
     */
    @Suppress("DEPRECATION")
    fun wakeScreen(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Log.i(TAG_A11Y, "wakeScreen: 屏幕本来就是亮的，无需唤醒")
            return true
        }
        Log.i(TAG_A11Y, "wakeScreen: 屏幕已熄，先尝试 WakeLock(ACQUIRE_CAUSES_WAKEUP) 点亮")

        // 首选：WakeLock 直接点亮屏。超时自动释放，避免常亮 wakelock 泄漏。
        val wl = pm.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "UnlockGuard::A11yWake",
        )
        wl.acquire(3000L)
        try {
            runCatching { Thread.sleep(300) }
            if (pm.isInteractive) {
                Log.i(TAG_A11Y, "wakeScreen: WakeLock 已点亮屏幕")
                return true
            }
        } finally {
            if (wl.isHeld) wl.release()
        }

        // 兜底：全局动作间接触发（部分 ROM 会拦截，故放后面）
        Log.i(TAG_A11Y, "wakeScreen: WakeLock 未点亮，回退到全局动作点亮")
        val orders = listOf(
            GLOBAL_ACTION_RECENTS,
            GLOBAL_ACTION_NOTIFICATIONS,
            GLOBAL_ACTION_QUICK_SETTINGS,
        )
        for (a in orders) {
            val ok = performGlobalAction(a)
            Log.d(TAG_A11Y, "wakeScreen: performGlobalAction($a) 返回=$ok")
            Thread.sleep(600)
            if (pm.isInteractive) {
                Log.i(TAG_A11Y, "wakeScreen: 已点亮（action=$a），尝试收起面板回到 PIN 锁屏")
                performGlobalAction(GLOBAL_ACTION_BACK)
                runCatching { Thread.sleep(250) }
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                runCatching { Thread.sleep(250) }
                return true
            }
        }
        Log.w(TAG_A11Y, "wakeScreen: 所有方式均未能点亮屏幕（可能被 ROM 限制）")
        return false
    }

    /**
     * 在锁屏 Keyguard 界面输入 PIN。
     *
     * 流程：
     *  - 先 [wakeScreen] 点亮屏（WakeLock 直接亮屏，全局动作兜底）。屏熄时无障碍读不到任何 keyguard 控件，
     *    故必须先把屏点亮才能触达数字键。
     *  - 很多 ROM（HyperOS/MIUI/原生）亮屏后先停「上滑解锁」第一层，需先 [swipeUpToRevealPin] 上滑才露出
     *    PIN 键盘；也可能误拉起通知/快捷面板。两者窗口都属 `com.android.systemui`，靠 [isPinKeyguard]
     *    甄别"是否含数字键"区分：是 keyguard 无数字键 → 上滑；是面板 → 收起。最多重试 5 次。
     *  - 任何一步失败都**绝不谎报成功**：返回结构化 [A11yUnlockResult]，解锁成败最终由引擎用
     *    `KeyguardManager.isKeyguardLocked()` 互校。
     */

    /**
     * 在「上滑解锁」第一层锁屏向上滑动，露出下方的 PIN 输入页。
     *
     * 用 `dispatchGesture`（需服务声明 `FLAG_CAN_PERFORM_GESTURES`，见 accessibility_service_config 的
     * `canPerformGestures`）做合法全域手势，不依赖坐标点击、不触碰其它界面。
     */
    private fun swipeUpToRevealPin(): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val midX = w / 2f
        val path = Path().apply {
            moveTo(midX, h * 0.82f)
            lineTo(midX, h * 0.18f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG_A11Y, "swipeUpToRevealPin: dispatchGesture 返回=$dispatched（屏 ${w.toInt()}x${h.toInt()}）")
        return dispatched
    }

    fun inputPinOnKeyguard(pin: String): A11yUnlockResult {
        // 1) 确保屏幕亮着：锁屏 = 等效按电源键，屏会熄灭；不点亮则 keyguard 不可见、数字键点不到
        if (!wakeScreen()) {
            Log.e(TAG_A11Y, "inputPinOnKeyguard: 屏幕无法点亮（wakeScreen 失败，HyperOS 限制或 ROM 拦截全局动作），放弃 PIN 输入")
            return A11yUnlockResult.ScreenOffWakeFailed
        }

        // 2) 定位真实 PIN 锁屏。很多 ROM（HyperOS/MIUI/原生）亮屏后先停「上滑解锁」第一层，需上滑才露出 PIN 键盘；
        //    也可能误拉起通知/快捷面板。两种情况靠重试 + 针对性动作解决，最多 5 次。
        var root = rootInActiveWindow
        var attempt = 0
        var swipedOnce = false
        while (root == null || !isPinKeyguard(root)) {
            if (attempt >= 5) {
                Log.e(TAG_A11Y, "inputPinOnKeyguard: 重试 5 次仍无法定位 PIN 锁屏（package=${root?.packageName}），放弃输入")
                return A11yUnlockResult.KeyguardNotFound
            }
            val onKeyguard = root?.packageName == "com.android.systemui"
            Log.w(TAG_A11Y, "inputPinOnKeyguard: 第 ${attempt + 1} 次：非 PIN 锁屏（package=${root?.packageName}，onKeyguard=$onKeyguard），尝试进入 PIN 页")
            if (onKeyguard && !swipedOnce) {
                // systemui 窗口但无数字键 → 多半停在「上滑解锁」第一层，向上滑出 PIN 键盘
                swipeUpToRevealPin()
                swipedOnce = true
                runCatching { Thread.sleep(650) }
            } else if (onKeyguard) {
                // 已上滑过仍是锁屏无键盘：多半 PIN 页渲染慢或被 ROM 拦截手势，等待后重试（不再重复上滑以免划走 PIN 页）
                runCatching { Thread.sleep(500) }
            } else {
                // 误拉起通知/快捷面板（同样属 systemui 但不在锁屏页），收起回到锁屏
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                runCatching { Thread.sleep(450) }
            }
            root = rootInActiveWindow
            attempt++
        }
        Log.i(TAG_A11Y, "inputPinOnKeyguard: 进入 PIN 锁屏，开始输入 PIN(${pin.length} 位)")
        for (ch in pin) {
            val node = findDigit(root, ch)
            if (node == null) {
                Log.e(TAG_A11Y, "inputPinOnKeyguard: 未找到数字键 '$ch' 节点（Keyguard 节点可能已被 ROM 简化降级）")
                return A11yUnlockResult.DigitMissing(ch)
            }
            Log.d(TAG_A11Y, "inputPinOnKeyguard: 点击数字键 '$ch'")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(80)
        }
        val confirm = findConfirm(root)
        if (confirm == null) {
            // HyperOS / AOSP 多数 PIN 键盘为"输满即校验"的自动提交式，没有独立确认键属正常情况
            Log.i(TAG_A11Y, "inputPinOnKeyguard: 未发现『确定/完成』节点 —— 当前为自动提交式 PIN 键盘，输入满位即校验")
        } else {
            Log.d(TAG_A11Y, "inputPinOnKeyguard: 点击确认节点")
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return A11yUnlockResult.PinEntered
    }

    /** 当前窗口是否为 PIN 锁屏：包名须为 SystemUI，且含有数字键盘节点（key0~key9 或 content-desc 为 0~9） */
    private fun isPinKeyguard(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName != "com.android.systemui") return false
        return dfs(root) { node ->
            val rid = node.viewIdResourceName ?: ""
            if (rid.endsWith("key0") || rid.endsWith("key1") || rid.endsWith("key2") ||
                rid.endsWith("key3") || rid.endsWith("key4") || rid.endsWith("key5") ||
                rid.endsWith("key6") || rid.endsWith("key7") || rid.endsWith("key8") ||
                rid.endsWith("key9")
            ) {
                return@dfs true
            }
            val cd = node.contentDescription?.toString() ?: ""
            // 注意：不能用 `cd in "0123456789"`（等价 "0123456789".contains(cd)，
            // 空串会被判定为包含，导致无 contentDescription 的节点也命中 → 甄别恒为真、失效）。
            cd.length == 1 && cd[0] in '0'..'9'
        } != null
    }

    private fun findDigit(root: AccessibilityNodeInfo, ch: Char): AccessibilityNodeInfo? {
        val target = ch.toString()
        // 1) 优先按 resource-id 精确匹配（ROM 不改 id 时最稳，HyperOS 即 key0~key9）
        var hit = dfs(root) { node ->
            (node.viewIdResourceName ?: "").endsWith("key$ch")
        }
        // 2) 退而求其次：content-desc / text == 数字
        if (hit == null) {
            hit = dfs(root) { node ->
                val t = node.text?.toString()
                val cd = node.contentDescription?.toString()
                t == target || cd == target
            }
        }
        // 命中节点若本身不可点击（数字文本常挂在不可点击的子 TextView 上），向上找最近可点击祖先
        return resolveClickable(hit)
    }

    private fun findConfirm(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keys = setOf("确定", "完成", "确认", "✔", "OK", "ok")
        val hit = dfs(root) { node ->
            val t = node.text?.toString()?.trim() ?: ""
            val cd = node.contentDescription?.toString()?.trim() ?: ""
            (t in keys || cd in keys) ||
                (node.viewIdResourceName?.contains("confirm", ignoreCase = true) == true) ||
                (node.viewIdResourceName?.contains("lock", ignoreCase = true) == true && node.isClickable)
        }
        return resolveClickable(hit)
    }

    /** 若命中节点本身不可点击，向上爬到最近的可点击祖先；找不到可点击祖先则返回原节点 */
    private fun resolveClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node ?: return null
        var guard = 0
        while (!cur.isClickable && guard < 8) {
            val p = cur.parent ?: break
            cur = p
            guard++
        }
        return cur
    }

    private fun dfs(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = dfs(child, pred)
            if (found != null) return found
        }
        return null
    }

    private companion object {
        const val TAG_A11Y = "UnlockA11y"
    }
}
