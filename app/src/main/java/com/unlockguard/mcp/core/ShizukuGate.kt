package com.unlockguard.mcp.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku 链路状态（与「权限自检」同口径：如实上报，不假装成功）
 */
enum class ShizukuState { NotInstalled, ServiceDown, NotAuthorized, Authorized }

/**
 * Shizuku 授权网关 —— 统一入口，避免各处再写「只打开 Shizuku App」的假授权。
 *
 * ## 历史缺陷（真机实测暴露）
 * 旧实现 `openShizukuManager()` 只调用 `getLaunchIntentForPackage("moe.shizuku.manager")`，
 * 即**仅打开 Shizuku 这个 App**，从未调用 `Shizuku.requestPermission()`。
 * 结果：点「启动 Shizuku」只会看到 Shizuku 首页，没有任何授权弹窗，
 * 回到本应用依旧「未授权」——用户反馈的正是这一点。
 *
 * ## 官方接入流程（按状态分支，缺一不可）
 * 1. 未安装 → 打开 Shizuku 下载页；
 * 2. 已安装但 `pingBinder()==false` → Shizuku 服务未运行，打开 Shizuku 让用户先启动服务
 *    （Shizuku 服务由 adb / 无线调试 / root 启动，本应用无权代劳）；
 * 3. binder 存活且未授权 → `Shizuku.requestPermission(code)`，由 Shizuku 弹出官方授权对话框；
 * 4. `shouldShowRequestPermissionRationale()==true` → 用户已拒绝过一次，
 *    Shizuku 不再弹窗，只能引导用户到 Shizuku 的「授权应用」里手动开启。
 *
 * 本类只负责「把用户送到正确的地方」，不缓存授权结果：
 * 真实状态一律用 `checkSelfPermission()` 现读，UI 轮询即可自动反映弹窗结果。
 */
object ShizukuGate {

    /**
     * Shizuku 存在两个发行包名，**必须都认**：
     *  - `moe.shizuku.privileged.api`：GitHub Release / Google Play 正式版（真机实测装的就是这个）；
     *  - `moe.shizuku.manager`：早期 / F-Droid / 调试卷构建。
     *
     * 历史缺陷之二：旧代码只认 `moe.shizuku.manager`，而真机装的是 `privileged.api`，
     * 于是 `getLaunchIntentForPackage` 返回 null → 点击后**连 Shizuku 都没打开**，
     * 表现就是「点击没反应、不弹授权」。
     */
    val PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")

    /** 授权请求码，仅需在应用内唯一 */
    const val REQUEST_CODE = 23127

    private const val INSTALL_URL = "https://github.com/RikkaApps/Shizuku/releases"
    private const val TAG = "ShizukuGate"

    fun isInstalled(ctx: Context): Boolean = runCatching {
        PACKAGES.any { ctx.packageManager.getPackageInfo(it, 0) != null }
    }.getOrDefault(false)

    /** Shizuku 服务（binder）是否存活 */
    fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本应用是否已拿到 Shizuku 授权 */
    fun granted(): Boolean = runCatching {
        ping() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shizuku 服务端 uid：`0` = root 启动，`2000` = adb / 无线调试(shell) 启动，`-1` = 不可用。
     * 两种都属于特权身份，均可用于注入解锁事件。
     */
    fun serverUid(): Int = runCatching { if (ping()) Shizuku.getUid() else -1 }.getOrDefault(-1)

    fun serverVersion(): Int = runCatching { if (ping()) Shizuku.getVersion() else 0 }.getOrDefault(0)

    fun state(ctx: Context): ShizukuState = when {
        !isInstalled(ctx) -> ShizukuState.NotInstalled
        !ping() -> ShizukuState.ServiceDown
        granted() -> ShizukuState.Authorized
        else -> ShizukuState.NotAuthorized
    }

    /**
     * 用户点「启动 Shizuku / 去授权」的统一入口：按状态把用户送到正确的地方，
     * 需要弹窗时直接唤起 Shizuku 官方授权对话框。
     *
     * @return 可直接展示给用户的提示文案
     */
    fun request(ctx: Context): String {
        // 诊断留痕：授权卡住时这几项足以定位卡在哪一个分支（真机上常需排查）
        Log.i(
            TAG,
            "request → installed=${isInstalled(ctx)} ping=${ping()} granted=${granted()} " +
                "serverUid=${serverUid()} version=${serverVersion()}",
        )
        if (!isInstalled(ctx)) {
            openUrl(ctx, INSTALL_URL)
            return "未检测到 Shizuku，已打开下载页；安装并启动后再回来授权"
        }
        // ShizukuProvider 只会在「它自己所在的进程」里自动请求 binder，本应用是多进程
        // （MCPServer 前台服务 / Shizuku UserService），冷启动瞬间 pingBinder() 可能仍是 false。
        // 若不先补一次请求就下结论，就会把「binder 还没送到」误判成「服务未运行」，
        // 把用户送去 Shizuku 的服务教程页 —— 真机日志里已出现该误判。
        if (!ping()) {
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(ctx) }
        }
        if (!ping()) {
            // 确认不可用：只能由用户在其 App 内启动服务（adb / 无线调试 / root）
            return if (launchManager(ctx)) {
                "Shizuku 服务未就绪：已打开 Shizuku，请确认其服务正在运行，再回来点「去授权」"
            } else {
                "Shizuku 服务未就绪，且未能自动打开 Shizuku，请手动打开它并启动服务"
            }
        }
        if (granted()) return "Shizuku 已授权，主通道可用"

        // 【真机实测结论（Shizuku 13.6 / Android 17，日志逐条对账）】
        //
        //   rationale == true   → 该 uid 此前被拒绝过。此时 requestPermission() 会在
        //                          2~5ms 内**直接回调 DENIED，完全不弹窗**，调多少次都一样。
        //                          （实测：22:14:05 / 08 / 12 / 16 / 22 连续 5 次全是静默失败）
        //   rationale == false  → 可以正常弹窗。
        //                          （实测：22:14:40 request → 22:14:42 弹窗被点「拒绝」；
        //                            22:14:49 request → 22:14:50 弹窗被点「允许」，granted=true）
        //
        // 因此 rationale 是**可信判据**：为 true 时不应再硬发请求（只会白白静默失败、
        // 让用户以为「点了没反应」），而应直接把人送到 Shizuku 去改状态。
        val deniedBefore = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        Log.i(TAG, "request → rationale=$deniedBefore")

        if (deniedBefore) {
            // 秒拒分支：Shizuku 已记住「本应用被拒绝过」，弹窗永远不会再出现。
            // 唯一出路是让 Shizuku 把该 uid 的状态重置。实测有效的路径是第 2 条：
            // 在 Shizuku 里「关闭」本应用授权会触发系统 `permissions revoked` 杀掉本应用进程，
            // 进程重建后 Shizuku 重新把本应用视为「可再次请求」，弹窗随即恢复。
            launchManager(ctx)
            return "Shizuku 不再弹出授权框（此前已拒绝过）。请打开 Shizuku →「应用管理」→ 找到本应用手动开启；" +
                "若其中找不到本应用、或已开启但仍显示未授权，请先「关闭」本应用授权再重新打开，然后回到本应用重试"
        }

        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            "已唤起 Shizuku 授权弹窗，请点「允许」"
        }.getOrElse { e ->
            // binder 状态在点击与调用之间可能变化（Shizuku 服务被杀等），兜底引导手动授权
            Log.w(TAG, "requestPermission 失败：${e.javaClass.simpleName} ${e.message}")
            launchManager(ctx)
            "唤起授权失败（${e.javaClass.simpleName}）：已打开 Shizuku，请在「应用管理」中手动允许本应用"
        }
    }

    /** 仅打开 Shizuku 管理器（让用户手动启动服务 / 手动授权） */
    fun launchManager(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val i = PACKAGES.firstNotNullOfOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
        } ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    private fun openUrl(ctx: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
