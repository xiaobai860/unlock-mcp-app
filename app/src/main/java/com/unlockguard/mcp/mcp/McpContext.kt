package com.unlockguard.mcp.mcp

import android.content.Context
import com.unlockguard.mcp.domain.AuditLog
import com.unlockguard.mcp.domain.LeaseManager
import com.unlockguard.mcp.domain.PinStore
import com.unlockguard.mcp.domain.RateLimiter
import com.unlockguard.mcp.unlock.UnlockEngine

/**
 * MCP 服务运行上下文：聚合鉴权 Token 与所有领域/能力依赖。
 * 由前台服务在启动 Server 前构建并注入。
 */
data class McpContext(
    val appContext: Context,
    val token: String,
    val version: String,
    val port: Int,
    val bindHost: String,
    val isLan: Boolean,
    val pinStore: PinStore,
    val leaseManager: LeaseManager,
    val audit: AuditLog,
    val unlockEngine: UnlockEngine,
    val rateLimiters: MutableMap<String, RateLimiter>,
)
