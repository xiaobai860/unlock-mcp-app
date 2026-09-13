package com.unlockguard.mcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
     * 点亮熄屏（无障碍**间接**唤醒）。
     *
     * 无障碍**没有**专门的唤醒 API：既不能注入 KEYCODE_WAKEUP，也没有 GLOBAL_ACTION_WAKE。
     * 但 `performGlobalAction` 的几个"拉起系统面板"动作会顺带点亮屏幕：
     *   - GLOBAL_ACTION_RECENTS（概览）
     *   - GLOBAL_ACTION_NOTIFICATIONS（通知栏）
     *   - GLOBAL_ACTION_QUICK_SETTINGS（快捷设置）
     * 这些动作要求显示可见，于是系统把屏点亮 —— 这是自动化工具"用无障碍亮屏"的标准手法。
     *
     * 注意：在**安全锁屏**上拉起的是"锁屏之上的面板"，需再收起才回到 PIN 锁屏；
     * 部分 ROM（如 HyperOS）可能直接拦截这些全局动作，此时返回 false，由上层决定兜底。
     *
     * @return 屏幕是否已处于交互态（亮屏）。
     */
    fun wakeScreen(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Log.i(TAG_A11Y, "wakeScreen: 屏幕本来就是亮的，无需唤醒")
            return true
        }
        Log.i(TAG_A11Y, "wakeScreen: 屏幕已熄，依次尝试全局动作点亮")
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
        Log.w(TAG_A11Y, "wakeScreen: 所有全局动作均未能点亮屏幕（可能被 ROM 限制）")
        return false
    }

    /**
     * 在锁屏 Keyguard 界面输入 PIN。
     *
     * 关键约束（HyperOS 实测）：
     *  - 本方法依赖 `rootInActiveWindow` 必须是**真正的 PIN 锁屏**（SystemUI 的 keyguard_pin_view）。
     *    **屏幕熄灭时无障碍读不到任何 keyguard 控件**——uiautomator dump 仅剩 scrim（legacy_window_root /
     *    scrim_*），无 key0~key9。故**必须先把屏点亮**才能触达数字键（`wakeScreen()` 完成）。
     *  - 无障碍**没有**专用唤醒 API；点亮屏靠 `wakeScreen()` 用全局动作（RECENTS / NOTIFICATIONS /
     *    QUICK_SETTINGS）拉起系统面板间接触发。HyperOS 上这些动作可能仍点不亮屏，此时直接放弃，
     *    **绝不谎报成功**（解锁成败最终由引擎用 `KeyguardManager.isKeyguardLocked()` 互校）。
     *  - 拉起的面板（通知栏 / 快捷设置）与 PIN 锁屏同属 `com.android.systemui`，所以要用 [isPinKeyguard]
     *    甄别"是否含数字键"，甄别失败就收起面板、回到锁屏重试（最多 3 次）。
     */
    fun inputPinOnKeyguard(pin: String): Boolean {
        // 1) 确保屏幕亮着：锁屏 = 等效按电源键，屏会熄灭；不点亮则 keyguard 不可见、数字键点不到
        if (!wakeScreen()) {
            Log.e(TAG_A11Y, "inputPinOnKeyguard: 屏幕无法点亮（wakeScreen 失败，HyperOS 限制或 ROM 拦截全局动作），放弃 PIN 输入")
            return false
        }

        // 2) 甄别并定位真实 PIN 锁屏，排除误拉起的通知/快捷面板，最多重试 3 次
        var root = rootInActiveWindow
        var attempt = 0
        while (root == null || !isPinKeyguard(root)) {
            if (attempt >= 3) {
                Log.e(TAG_A11Y, "inputPinOnKeyguard: 重试 3 次仍无法定位 PIN 锁屏（package=${root?.packageName}），放弃输入")
                return false
            }
            Log.w(TAG_A11Y, "inputPinOnKeyguard: 第 ${attempt + 1} 次重试：当前非 PIN 锁屏（package=${root?.packageName}），收起面板回到锁屏")
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            runCatching { Thread.sleep(450) }
            root = rootInActiveWindow
            attempt++
        }
        Log.i(TAG_A11Y, "inputPinOnKeyguard: 进入 PIN 锁屏，开始输入 PIN(${pin.length} 位)")
        for (ch in pin) {
            val node = findDigit(root, ch)
            if (node == null) {
                Log.e(TAG_A11Y, "inputPinOnKeyguard: 未找到数字键 '$ch' 节点（Keyguard 节点可能已被 ROM 简化降级）")
                return false
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
        return true
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
            cd in "0123456789"
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
