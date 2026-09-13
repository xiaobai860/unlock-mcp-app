package com.unlockguard.mcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自启：拉起前台 MCP 服务（前台服务启动属于豁免路径）。
 * 同时由服务启动逻辑提示拉起 Shizuku（Shizuku 自身负责权限获取）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            context.startForegroundService(Intent(context, McpForegroundService::class.java))
        }
    }
}
