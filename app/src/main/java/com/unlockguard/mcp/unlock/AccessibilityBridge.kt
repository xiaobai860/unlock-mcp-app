package com.unlockguard.mcp.unlock

import com.unlockguard.mcp.accessibility.UnlockAccessibilityService

/**
 * 无障碍服务的进程内桥：由 UnlockAccessibilityService 在 onCreate/onDestroy 时
 * attach/detach，供解锁引擎在无障碍通道可用时调用其 PIN 输入能力。
 */
object AccessibilityBridge {

    @Volatile var service: UnlockAccessibilityService? = null
        private set

    fun attach(s: UnlockAccessibilityService) { service = s }
    fun detach(s: UnlockAccessibilityService) { if (service === s) service = null }

    fun isKeyguardShown(): Boolean = service?.isKeyguardShown() ?: false

    /** 在锁屏 Keyguard 界面输入 PIN，返回结构化结果（是否真解开由引擎用锁屏状态互校） */
    fun inputPin(pin: String): A11yUnlockResult =
        service?.inputPinOnKeyguard(pin) ?: A11yUnlockResult.KeyguardNotFound

    /**
     * 锁屏：无障碍全局动作 `GLOBAL_ACTION_LOCK_SCREEN`。
     * 返回系统是否接受动作，**不代表已锁上** —— 仍由引擎用 KeyguardManager 复核。
     */
    fun lockScreen(): Boolean = service?.lockScreen() ?: false

    /** 无障碍间接点亮屏幕：通过系统面板全局动作唤醒（无障碍无专用唤醒 API） */
    fun wake(): Boolean = service?.wakeScreen() ?: false
}
