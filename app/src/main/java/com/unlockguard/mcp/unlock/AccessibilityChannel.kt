package com.unlockguard.mcp.unlock

/**
 * 无障碍通道，承担两个角色：
 *
 * 1. **锁屏首选**：`GLOBAL_ACTION_LOCK_SCREEN` 是 Google 官方推荐的第三方锁屏方式，
 *    毫秒级生效，且等效按电源键 —— **保留指纹/人脸**，不像 DeviceAdmin `lockNow()`
 *    那样强制进入"必须输密码"态。锁屏链路里它排在设备管理员之前。
 * 2. **解锁备通道**：不走事件注入，不受 ROM 输入拦截策略影响；能读取 Keyguard 控件树，
 *    定位 PIN 键盘更稳。需用户在设置中手动开启。
 *
 * 能力焊死在 Keyguard（SystemUI）窗口内的 PIN 输入 + 锁屏全局动作，
 * 见 [com.unlockguard.mcp.accessibility.UnlockAccessibilityService]。
 */
class AccessibilityChannel {

    fun isEnabled(): Boolean = AccessibilityBridge.service != null

    fun unlock(pin: String): Boolean = AccessibilityBridge.inputPin(pin)

    /** 锁屏。返回是否已下发，真实结果由引擎复核锁屏状态 */
    fun lock(): Boolean = AccessibilityBridge.lockScreen()

    /** 间接点亮屏幕（无障碍全局动作拉起系统面板 → 屏亮），用于锁屏后自救 */
    fun wake(): Boolean = AccessibilityBridge.wake()
}
