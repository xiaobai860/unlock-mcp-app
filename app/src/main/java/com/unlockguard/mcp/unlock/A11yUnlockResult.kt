package com.unlockguard.mcp.unlock

/**
 * 无障碍备通道解锁结果。
 *
 * 与 Shizuku 通道（[ShizukuReport]）不同，无障碍通道不注入原始按键事件，
 * 而是依赖「屏亮 + 能读到 Keyguard 控件树 + 能定位数字键」三件事同时成立。
 * 任何一环断了都属于**基础设施失败**，与「PIN 是否正确」无关 —— 必须如实区分，
 * 否则会把"点不亮屏 / ROM 节点降级"误报成"PIN 不符"，既误导用户又会让连续失败计数误触发 LOCKED_OUT。
 */
sealed interface A11yUnlockResult {

    /** PIN 已成功输入（无论屏幕最终是否解开，由引擎用 [com.unlockguard.mcp.unlock.UnlockEngine.isScreenLocked] 互校） */
    data object PinEntered : A11yUnlockResult

    /** 屏幕点不亮：连输入 PIN 的机会都没有，多为 HyperOS 拦截全局动作或电池优化杀服务 */
    data object ScreenOffWakeFailed : A11yUnlockResult

    /** 点亮后始终定位不到 PIN 锁屏（误拉起面板无法收起 / Keyguard 窗口被 ROM 隐藏） */
    data object KeyguardNotFound : A11yUnlockResult

    /** 定位到 PIN 锁屏但缺某个数字键：ROM 节点降级，或 PIN 与锁屏密码位数/字符不一致 */
    data class DigitMissing(val ch: Char) : A11yUnlockResult
}
