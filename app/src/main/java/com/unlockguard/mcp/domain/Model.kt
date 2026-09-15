package com.unlockguard.mcp.domain

/** 解锁执行通道 */
enum class UnlockChannel { SHIZUKU, ACCESSIBILITY, NONE }

/**
 * 锁屏执行通道。三级降级关系的优先级见 `UnlockEngine.lock()`。
 *
 * [biometricPreserved] 是这三条路**唯一对用户可见的差异**，也是选择顺序的依据：
 * - `ACCESSIBILITY`（`GLOBAL_ACTION_LOCK_SCREEN`）与 `SHIZUKU`（`KEYCODE_SLEEP`）等效于按电源键，
 *   **指纹 / 人脸 / Smart Lock 照常可用**；
 * - `DEVICE_ADMIN`（`lockNow()`）会把设备强制推进 PRIMARY_BOUNCER（"必须输密码"态），
 *   **生物识别失效，只能输 PIN**。
 *
 * 正因如此，Android 9 才新增无障碍全局动作——Google 的目的是让第三方锁屏应用摆脱
 * DeviceAdmin 这个副作用，官方推荐优先用无障碍。
 *
 * 本项目锁屏降序（与解锁链路保持一致，均 Shizuku 主 → 无障碍备）：
 * `SHIZUKU` → `ACCESSIBILITY` → `DEVICE_ADMIN`。
 * 前两者都保留生物识别，可互换先后；设备管理员因会让生物识别失效，固定排在最后兜底。
 */
enum class LockChannel(val biometricPreserved: Boolean) {
    ACCESSIBILITY(true),
    DEVICE_ADMIN(false),
    SHIZUKU(true),

    /** 调用前屏幕已处于锁定态，未执行任何动作 */
    NONE(true),
}

/** 手机实时状态（get_phone_state 返回） */
data class PhoneState(
    val screenOn: Boolean,
    val locked: Boolean,
    val shizukuAvailable: Boolean,
    val accessibilityEnabled: Boolean,
    val leaseActive: Boolean,
    val leaseRemainingSec: Int? = null,
    val leaseHolder: String? = null,
    val lockedOut: Boolean = false,
)

/** 活跃租约 */
data class Lease(
    val leaseId: String,
    val expireAtEpochMs: Long,
    val holder: String,
    val channelUsed: UnlockChannel,
)

/** 租约获取结果 */
sealed interface AcquireResult {
    data class Ok(val lease: Lease) : AcquireResult
    data class Conflict(val existing: Lease) : AcquireResult
    data class Failed(val code: String, val hint: String) : AcquireResult
}
