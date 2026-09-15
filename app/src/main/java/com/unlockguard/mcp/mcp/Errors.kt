package com.unlockguard.mcp.mcp

import kotlinx.serialization.Serializable

/** 统一错误结构 { code, message, hint } */
@Serializable
data class McpError(
    val code: String,
    val message: String,
    val hint: String,
)

/** 方案文档约定的错误码（与 UI 引导对话框一一对应） */
object ErrorCodes {
    const val SHIZUKU_UNAVAILABLE = "SHIZUKU_UNAVAILABLE"
    const val ACCESSIBILITY_DISABLED = "ACCESSIBILITY_DISABLED"
    const val PIN_MISMATCH = "PIN_MISMATCH"
    const val LOCKED_OUT = "LOCKED_OUT"
    const val LEASE_CONFLICT = "LEASE_CONFLICT"
    const val POPUP_BLOCKED = "POPUP_BLOCKED"
    const val PERMISSION_MISSING = "PERMISSION_MISSING"
    const val TOKEN_INVALID = "TOKEN_INVALID"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INVALID_PARAMS = "INVALID_PARAMS"
}

/** MCP 工具统一返回结构：{ ok, data?, error? } */
@Serializable
data class ToolEnvelope(
    val ok: Boolean,
    val data: kotlinx.serialization.json.JsonObject? = null,
    val error: McpError? = null,
)
