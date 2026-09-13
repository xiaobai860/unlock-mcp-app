package com.unlockguard.mcp.device

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理员 —— **仅用于锁屏兜底**。
 *
 * ## 只用一条策略：`force-lock`
 * `res/xml/device_admin.xml` 只声明 `<force-lock />`，对应
 * `DeviceAdminInfo.USES_POLICY_FORCE_LOCK`，官方定义是
 * "able to force the device to lock via lockNow() or limit the maximum lock timeout"。
 * 注意它的方向**只有「锁」，没有「解」** —— 系统激活页上那个"管理锁屏"的中文条目说的就是它。
 *
 * 不申请其它任何策略（改密码 / 清数据 / 摄像头 / 密码规则等），理由：
 * 1. 本项目只需要锁屏，最小权限原则；
 * 2. `resetPassword()` 自 Android 8.0 对 Device Owner 废弃、Android 10 起对普通
 *    设备管理员**完全移除**（调用即抛 SecurityException），要了也用不了；
 * 3. 申请得越少，用户面对的系统激活页越不吓人。
 *
 * ## 在锁屏链路里的位置
 * 它是**第二级兜底**，排在无障碍之后：`lockNow()` 会把设备强制推进 PRIMARY_BOUNCER，
 * **指纹/人脸失效、只能输 PIN**。只有当无障碍被 ROM 限制时才轮到它。
 * 见 `UnlockEngine.lock()`。
 */
class ScreenLockAdmin : DeviceAdminReceiver() {

    companion object {

        fun component(context: Context): ComponentName =
            ComponentName(context, ScreenLockAdmin::class.java)

        fun isActive(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm.isAdminActive(component(context))
        }.getOrDefault(false)

        /**
         * 系统设备管理员激活页的 Intent。
         * UI 与权限自检都走这个入口，避免各处各写一份 Intent 常量。
         */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "用于在无障碍锁屏不可用时兜底熄屏上锁。本应用只使用「强制锁屏」这一项能力，" +
                        "不会修改锁屏密码、不会清除数据、不会使用其它任何设备管理能力。",
                )

        /** 锁屏。未激活时返回 false，由调用方降级到下一通道。 */
        fun lockNow(context: Context): Boolean = runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val cn = component(context)
            if (dpm.isAdminActive(cn)) {
                dpm.lockNow()
                true
            } else false
        }.getOrDefault(false)
    }
}
