package com.unlockguard.mcp.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.unlockguard.mcp.device.ScreenLockAdmin

/**
 * 权限自检 —— 设计稿「权限自检」卡片的真实数据源。
 *
 * 原则（与设计稿「如实上报，不假装成功」一致）：能查的查真实状态，查不到的**明确标注为待确认**，
 * 绝不用绿色对勾糊过去。
 *
 * - `Granted`：系统 API 可直接判定且已授予
 * - `Missing`：系统 API 可直接判定且未授予
 * - `Unknown`：AOSP 无查询接口（自启动、后台弹出界面在多数 ROM 上是厂商私有开关），需用户手动确认
 */
enum class PermStatus { Granted, Missing, Unknown }

/**
 * 每一项对应的系统跳转入口。
 *
 * [AppInfo] 是**权限总入口**：应用信息页里可以一次把「自启动 / 电池 / 后台弹出 / 权限」都设置完。
 *
 * 为什么不能只给「电池」直达：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 在部分 ROM
 * （真机实测的国产 ROM 即如此）会被系统重定向到**该应用的「电量详情页」**，
 * 用户在那里只能改电池策略，改不了自启动、后台弹出等其它权限——与「方便地设置应用权限」的诉求相反。
 * 因此自启动 / 后台弹出这类 ROM 私有开关一律落到应用信息页，由用户逐项开启。
 *
 * Shizuku 不走这里：它没有可跳转的授权 Intent，必须调 `Shizuku.requestPermission()`，
 * 见 [ShizukuGate.request]。
 */
enum class PermAction {
    AppInfo,          // 应用信息页（权限 / 自启动 / 电池 / 后台弹出的总入口）
    BatteryUnlimited, // 电池优化白名单（精确直达）
    Overlay,          // 悬浮窗权限页
    Accessibility,    // 无障碍服务列表
    DeviceAdmin,      // 设备管理员激活页（锁屏兜底）
    None,
}

data class PermissionRow(
    val title: String,
    val desc: String?,
    val status: PermStatus,
    val action: PermAction,
)

class DevicePermissions(private val ctx: Context) {

    /** 电池无限制 / Doze 白名单：同一系统开关（AOSP 下二者等价） */
    fun batteryUnrestricted(): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    }.getOrDefault(false)

    /** 悬浮窗（后台弹出兜底所需） */
    fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** 设备管理员是否已激活（锁屏第二级兜底） */
    fun isDeviceAdminActive(): Boolean = ScreenLockAdmin.isActive(ctx)

    /** 无障碍服务是否在系统已启用列表中 */
    fun isAccessibilityServiceEnabled(className: String): Boolean = runCatching {
        val expected = ComponentName(ctx.packageName, className)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }.getOrDefault(false)

    /**
     * @param accessibilityOn 由解锁引擎给出的无障碍可用性（服务实例已连接）
     * @param shizukuOn       Shizuku binder 可用性
     */
    fun audit(accessibilityOn: Boolean, shizukuOn: Boolean): List<PermissionRow> {
        val battery = batteryUnrestricted()
        val overlay = canDrawOverlays()
        val deviceAdmin = isDeviceAdminActive()
        return listOf(
            PermissionRow(
                "自启动", "重启后自动拉起服务（ROM 私有开关，无法读取）",
                PermStatus.Unknown, PermAction.AppInfo,
            ),
            PermissionRow(
                "电池无限制", if (battery) null else "未加入白名单，后台可能被清理",
                if (battery) PermStatus.Granted else PermStatus.Missing, PermAction.BatteryUnlimited,
            ),
            PermissionRow(
                "Doze 白名单", if (battery) null else "未豁免 Doze，熄屏后服务可能被挂起",
                if (battery) PermStatus.Granted else PermStatus.Missing, PermAction.BatteryUnlimited,
            ),
            PermissionRow(
                "后台弹出界面", "解锁时拉起页面的能力（ROM 私有开关，无法读取）",
                PermStatus.Unknown, PermAction.AppInfo,
            ),
            PermissionRow(
                "悬浮窗", if (overlay) null else "未授予，悬浮球兜底不可用",
                if (overlay) PermStatus.Granted else PermStatus.Missing, PermAction.Overlay,
            ),
            PermissionRow(
                "无障碍服务",
                if (accessibilityOn) null else "未开启：锁屏首选通道与解锁备通道均不可用",
                if (accessibilityOn) PermStatus.Granted else PermStatus.Missing, PermAction.Accessibility,
            ),
            PermissionRow(
                "设备管理员",
                if (deviceAdmin) null else "未激活：锁屏失去兜底（可选，仅在无障碍被 ROM 限制时才用得上）",
                if (deviceAdmin) PermStatus.Granted else PermStatus.Missing, PermAction.DeviceAdmin,
            ),
        )
    }
}

/** 打开本应用的应用信息页（权限总入口） */
fun openAppInfo(ctx: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", ctx.packageName, null),
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

/** 跳转到对应的系统设置页；全部做防御性处理，跳转失败不崩溃 */
fun performPermAction(ctx: Context, action: PermAction) {
    val pkgUri = Uri.fromParts("package", ctx.packageName, null)
    val intent: Intent? = when (action) {
        PermAction.AppInfo -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        PermAction.BatteryUnlimited -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
        PermAction.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
        PermAction.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermAction.DeviceAdmin -> ScreenLockAdmin.activationIntent(ctx)
        PermAction.None -> null
    }
    if (intent == null) return
    // 设备管理员激活页（DeviceAdminAdd）在部分 ROM（如 HyperOS）会因 NEW_TASK 被直接拒绝
    // （日志："Cannot start ADD_DEVICE_ADMIN as a new task"），页面打开后瞬间 finish，表现为"点了没反应"。
    // 因此该入口**永不**附加 NEW_TASK，交给系统把它并入调用方当前任务栈即可。
    // （注意：Compose 的 LocalContext 可能是 ContextWrapper 而非 Activity 实例，不能用 `ctx !is Activity`
    //  来判定是否补 NEW_TASK，否则会在该入口误加回 NEW_TASK。故直接按 action 排除。）
    // 其它权限入口若从非 Activity 上下文（Service/ViewModel）调用，再补 NEW_TASK。
    if (action != PermAction.DeviceAdmin && ctx !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
        .onFailure {
            // 兜底：落到应用信息页，至少让用户能自己找到入口
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

/** 打开外部 Activity 的通用工具（预留：Activity 场景下可去掉 NEW_TASK） */
fun Context.startActivitySafe(intent: Intent) {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
