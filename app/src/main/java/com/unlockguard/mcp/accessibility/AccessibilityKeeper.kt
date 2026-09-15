package com.unlockguard.mcp.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 无障碍服务「重启后免手动开启」的底层能力。
 *
 * 机制：`android.permission.WRITE_SECURE_SETTINGS` 是 signature|privileged|development 级权限，
 * 普通应用无法在 Manifest 中直接获取，必须由用户在已连接 adb 的电脑上一次性执行
 * `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` 授予。
 * 授予后该权限**随应用保留**：跨重启、跨应用更新都有效（仅卸载应用或恢复出厂会撤销）。
 *
 * 用途：部分国产 ROM（HyperOS / MIUI 等）会在重启后、或一段时间后自动关闭第三方无障碍服务。
 * 我们持有该权限时，可在开机 / 应用启动时把本应用的无障碍服务重新写回
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，并打开总开关 `Settings.Secure.ACCESSIBILITY_ENABLED`，
 * 从而无需用户每次重启后手动到「无障碍」设置页重新开启。
 *
 * 约定：
 *  - 仅「重新写入系统设置」这一动作需要该权限；不持有也不影响应用其它功能（仍走 Shizuku 主通道）。
 *  - 写的是系统设置 ContentProvider（跨进程 IPC），调用方须在 IO 线程执行，切勿在主线程 / 广播回调里同步调用。
 *  - 写入采用「追加而非覆盖」，绝不改动其它已启用服务的配置。
 */
object AccessibilityKeeper {

    private const val TAG = "AccessibilityKeeper"

    /** 是否持有 WRITE_SECURE_SETTINGS（无障碍重启免手动开启的前提） */
    fun canWriteSecureSettings(ctx: Context): Boolean =
        runCatching {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * 若已持有 WRITE_SECURE_SETTINGS，确保本应用的无障碍服务处于系统启用列表且总开关打开。
     * @return true 表示本次执行了写入（即此前未启用），false 表示无需写入或未持有权限。
     */
    fun ensureEnabled(ctx: Context): Boolean {
        if (!canWriteSecureSettings(ctx)) return false
        return runCatching {
            val cn = ComponentName(ctx, UnlockAccessibilityService::class.java)
            val flat = cn.flattenToString()
            val resolver = ctx.contentResolver

            val cur = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val parts = cur.split(':').filter { it.isNotBlank() }.toMutableSet()
            val wroteList = if (flat !in parts) {
                parts.add(flat)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, parts.joinToString(":"))
                true
            } else false

            val masterOn = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            val wroteMaster = if (!masterOn) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            } else false

            if (wroteList || wroteMaster) {
                Log.i(TAG, "已重写无障碍设置：enabled_services 含 $flat，masterSwitch=$masterOn→1")
            }
            wroteList || wroteMaster
        }.onFailure { e ->
            // 部分 ROM 会限制第三方直接写无障碍设置，失败属预期降级，不阻塞应用
            Log.w(TAG, "重写无障碍设置失败（ROM 可能限制直接写入）：${e.message}")
        }.getOrDefault(false)
    }
}
