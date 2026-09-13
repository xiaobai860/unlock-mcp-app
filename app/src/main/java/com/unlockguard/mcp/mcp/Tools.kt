package com.unlockguard.mcp.mcp

import android.provider.Settings
import com.unlockguard.mcp.domain.AcquireResult
import com.unlockguard.mcp.domain.LeaseManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import com.unlockguard.mcp.unlock.LockResult
import com.unlockguard.mcp.unlock.UnlockResult

/**
 * MCP 工具实现：每个工具返回统一 ToolEnvelope(ok/data/error)，并写入审计日志。
 * 见方案文档「三、MCP 工具清单」与「3.5 统一返回结构与错误码」。
 */
object Tools {

    suspend fun dispatch(ctx: McpContext, name: String, args: JsonObject, sourceIp: String): ToolEnvelope =
        when (name) {
            "get_phone_state" -> getPhoneState(ctx, sourceIp)
            "unlock_phone" -> {
                val ttl = args["ttl_seconds"]?.jsonPrimitive?.int ?: LeaseManager.DEFAULT_TTL
                unlockPhone(ctx, ttl, sourceIp)
            }
            "release_lease" -> releaseLease(ctx, args["lease_id"]?.jsonPrimitive?.content ?: "")
            "lock_phone" -> lockPhone(ctx, sourceIp)
            "set_screen_timeout" -> {
                val off = args["screen_off_ms"]?.jsonPrimitive?.int ?: 60_000
                val after = args["lock_after_ms"]?.jsonPrimitive?.int ?: 5_000
                setScreenTimeout(ctx, off, after)
            }
            "restore_settings" -> restoreSettings(ctx)
            "grant_debug_auth" -> grantDebugAuth(ctx)
            else -> ToolEnvelope(false, null, McpError("UNKNOWN_TOOL", "未知工具: $name", "检查客户端工具名"))
        }

    private suspend fun getPhoneState(ctx: McpContext, sourceIp: String): ToolEnvelope {
        val a = ctx.unlockEngine.availability()
        val data = buildJsonObject {
            put("screen_on", JsonPrimitive(ctx.unlockEngine.isScreenOn()))
            put("locked", JsonPrimitive(ctx.unlockEngine.isScreenLocked()))
            putJsonObject("channels") {
                put("shizuku", JsonPrimitive(a.shizuku))
                put("accessibility", JsonPrimitive(a.accessibility))
            }
            val lease = ctx.leaseManager.info()
            if (lease != null) {
                putJsonObject("lease") {
                    put("lease_id", JsonPrimitive(lease.leaseId))
                    put("remaining_sec", JsonPrimitive(ctx.leaseManager.remainingSec()))
                    put("holder", JsonPrimitive(lease.holder))
                    put("channel_used", JsonPrimitive(lease.channelUsed.name))
                }
            } else {
                put("lease", JsonPrimitive(null as String?))
            }
            put("locked_out", JsonPrimitive(ctx.unlockEngine.isLockedOut()))
        }
        ctx.audit.record(sourceIp, "get_phone_state", "-", true, null)
        return ToolEnvelope(true, data)
    }

    private suspend fun unlockPhone(ctx: McpContext, ttlSeconds: Int, sourceIp: String): ToolEnvelope {
        // 全局唯一租约：已有活跃租约不抢占
        val existing = ctx.leaseManager.info()
        if (existing != null) {
            val data = buildJsonObject {
                put("lease_id", JsonPrimitive(existing.leaseId))
                put("remaining_sec", JsonPrimitive(ctx.leaseManager.remainingSec()))
                put("holder", JsonPrimitive(existing.holder))
                put("channel_used", JsonPrimitive(existing.channelUsed.name))
            }
            ctx.audit.record(sourceIp, "unlock_phone", "ttl=$ttlSeconds", false, ErrorCodes.LEASE_CONFLICT)
            return ToolEnvelope(false, data, McpError(ErrorCodes.LEASE_CONFLICT, "已有活跃租约", "请等待到期或显式 release_lease"))
        }

        return when (val r = ctx.unlockEngine.tryUnlock()) {
            is UnlockResult.Ok -> {
                val capped = ttlSeconds.coerceIn(1, LeaseManager.MAX_TTL)
                when (val lease = ctx.leaseManager.acquire(capped, r.channel, sourceIp)) {
                    is AcquireResult.Ok -> {
                        val data = buildJsonObject {
                            put("lease_id", JsonPrimitive(lease.lease.leaseId))
                            put("ttl_seconds", JsonPrimitive(capped))
                            put("expire_at", JsonPrimitive(lease.lease.expireAtEpochMs))
                            put("channel_used", JsonPrimitive(lease.lease.channelUsed.name))
                        }
                        ctx.audit.record(sourceIp, "unlock_phone", "ttl=$capped", true, null)
                        ToolEnvelope(true, data)
                    }
                    is AcquireResult.Conflict -> ToolEnvelope(false, null, McpError(ErrorCodes.LEASE_CONFLICT, "并发租约", "lease 已被占用"))
                    is AcquireResult.Failed -> ToolEnvelope(false, null, McpError(lease.code, "获取租约失败", lease.hint))
                }
            }
            is UnlockResult.Failed -> {
                ctx.audit.record(sourceIp, "unlock_phone", "ttl=$ttlSeconds", false, r.code)
                ToolEnvelope(false, null, McpError(r.code, "解锁失败", r.hint))
            }
        }
    }

    private suspend fun releaseLease(ctx: McpContext, leaseId: String): ToolEnvelope {
        val ok = ctx.leaseManager.release(leaseId)
        val data = buildJsonObject { put("released", JsonPrimitive(ok)) }
        ctx.audit.record("local", "release_lease", "id=$leaseId", ok, if (ok) null else ErrorCodes.LEASE_CONFLICT)
        return if (ok) ToolEnvelope(true, data)
        else ToolEnvelope(false, data, McpError(ErrorCodes.LEASE_CONFLICT, "租约不存在或已释放", "请核对 lease_id"))
    }

    /**
     * 锁屏。返回实际生效的通道，以及**生物识别是否还可用** —— AI 客户端据此决定
     * 后续是"等用户指纹解锁"还是"准备输入 PIN"。
     */
    private suspend fun lockPhone(ctx: McpContext, sourceIp: String): ToolEnvelope =
        when (val r = ctx.unlockEngine.lock()) {
            is LockResult.Ok -> {
                val data = buildJsonObject {
                    put("locked", JsonPrimitive(true))
                    put("channel_used", JsonPrimitive(r.channel.name))
                    put("already_locked", JsonPrimitive(r.alreadyLocked))
                    put("biometric_preserved", JsonPrimitive(r.biometricPreserved))
                }
                ctx.audit.record(sourceIp, "lock_phone", "channel=${r.channel.name}", true, null)
                ToolEnvelope(true, data)
            }
            is LockResult.Failed -> {
                ctx.audit.record(sourceIp, "lock_phone", "-", false, r.code)
                ToolEnvelope(
                    false,
                    buildJsonObject { put("locked", JsonPrimitive(false)) },
                    McpError(r.code, "锁屏失败", r.hint),
                )
            }
        }

    private suspend fun setScreenTimeout(ctx: McpContext, screenOffMs: Int, lockAfterMs: Int): ToolEnvelope {
        val c = ctx.appContext
        if (!Settings.System.canWrite(c)) {
            return ToolEnvelope(
                false,
                buildJsonObject { put("applied", JsonPrimitive(false)) },
                McpError(ErrorCodes.PERMISSION_MISSING, "需要 WRITE_SETTINGS", "请在设置中授予「修改系统设置」权限"),
            )
        }
        runCatching {
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, screenOffMs)
            Settings.Secure.putInt(c.contentResolver, LeaseManager.SECURE_LOCK_AFTER_TIMEOUT, lockAfterMs)
        }
        val data = buildJsonObject { put("applied", JsonPrimitive(true)) }
        ctx.audit.record("local", "set_screen_timeout", "off=$screenOffMs,after=$lockAfterMs", true, null)
        return ToolEnvelope(true, data)
    }

    private suspend fun restoreSettings(ctx: McpContext): ToolEnvelope {
        val ok = ctx.leaseManager.restoreSettingsNow()
        val data = buildJsonObject { put("restored", JsonPrimitive(ok)) }
        ctx.audit.record("local", "restore_settings", "-", true, null)
        return ToolEnvelope(true, data)
    }

    private suspend fun grantDebugAuth(ctx: McpContext): ToolEnvelope {
        val data = buildJsonObject {
            put("adb_auth_required", JsonPrimitive(false))
            put("hint", JsonPrimitive("如弹窗要求无线调试授权，请手动在手机上点击「允许」"))
        }
        ctx.audit.record("local", "grant_debug_auth", "-", true, null)
        return ToolEnvelope(true, data)
    }
}
