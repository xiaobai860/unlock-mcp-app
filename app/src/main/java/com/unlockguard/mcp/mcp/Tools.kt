package com.unlockguard.mcp.mcp

import android.provider.Settings
import com.unlockguard.mcp.device.ScreenLockAdmin
import com.unlockguard.mcp.domain.AcquireResult
import com.unlockguard.mcp.domain.LeaseManager
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import com.unlockguard.mcp.unlock.LockResult
import com.unlockguard.mcp.unlock.UnlockResult

/**
 * MCP 工具实现：每个工具返回统一 ToolEnvelope(ok/data/error)，并写入审计日志。
 * 见方案文档「三、MCP 工具清单」与「3.5 统一返回结构与错误码」。
 */
object Tools {

    /**
     * 连续解锁失败阈值（达上限即暂停自动解锁 10 分钟）。
     * 与 RateLimiter 默认值、UI 展示保持一致；此处仅用于 get_phone_state 如实回读。
     */
    private const val FAIL_THRESHOLD = 5

    /** 各工具接受的参数名白名单（服务端强制 inputSchema，F1） */
    private val TOOL_ARGS: Map<String, Set<String>> = mapOf(
        "get_phone_state" to emptySet(),
        "unlock_phone" to setOf("ttl_seconds"),
        "release_lease" to setOf("lease_id"),
        "lock_phone" to emptySet(),
        "set_screen_timeout" to setOf("screen_off_ms", "lock_after_ms"),
        "restore_settings" to emptySet(),
        "grant_debug_auth" to emptySet(),
        "set_screen_brightness" to setOf("level", "auto"),
        "run_adb_command" to setOf("command", "timeout_ms"),
    )

    suspend fun dispatch(ctx: McpContext, name: String, args: JsonObject, sourceIp: String): ToolEnvelope {
        // 服务端强制 inputSchema（F1）：拒绝未知参数（inputSchema 声明 additionalProperties:false 但此前未落地执行），
        // 避免「参数名写错被静默忽略→走默认值」这类误判/混淆。类型错误由下方 .int 强制抛错后统一兜成 INVALID_PARAMS。
        val allowed = TOOL_ARGS[name]
        for (key in args.keys) {
            if (allowed == null || key !in allowed) {
                ctx.audit.record(sourceIp, name, "bad-arg:$key", false, ErrorCodes.INVALID_PARAMS)
                return ToolEnvelope(false, null,
                    McpError(ErrorCodes.INVALID_PARAMS, "未知参数: $key",
                        "该工具不接受参数 '$key'（可用参数：${allowed?.joinToString() ?: "无"}）"))
            }
        }
        return when (name) {
            "get_phone_state" -> getPhoneState(ctx, sourceIp)
            "unlock_phone" -> {
                val ttl = args["ttl_seconds"]?.jsonPrimitive?.int ?: LeaseManager.DEFAULT_TTL
                unlockPhone(ctx, ttl, sourceIp)
            }
            "release_lease" -> {
                val id = args["lease_id"]?.jsonPrimitive?.content
                if (id.isNullOrBlank()) {
                    val ok = ctx.leaseManager.releaseCurrent()
                    ctx.audit.record("local", "release_lease", "current", ok, if (ok) null else ErrorCodes.LEASE_CONFLICT)
                    ToolEnvelope(true, buildJsonObject { put("released", JsonPrimitive(ok)) })
                } else {
                    releaseLease(ctx, id)
                }
            }
            "lock_phone" -> lockPhone(ctx, sourceIp)
            "set_screen_timeout" -> {
                val off = args["screen_off_ms"]?.jsonPrimitive?.int ?: 60_000
                val after = args["lock_after_ms"]?.jsonPrimitive?.int ?: 5_000
                setScreenTimeout(ctx, off, after)
            }
            "restore_settings" -> restoreSettings(ctx)
            "grant_debug_auth" -> grantDebugAuth(ctx)
            "set_screen_brightness" -> {
                val level = args["level"]?.jsonPrimitive?.int
                val auto = args["auto"]?.jsonPrimitive?.boolean ?: false
                setScreenBrightness(ctx, level, auto, sourceIp)
            }
            "run_adb_command" -> {
                val cmd = args["command"]?.jsonPrimitive?.content
                val timeout = args["timeout_ms"]?.jsonPrimitive?.int ?: 15_000
                runAdbCommand(ctx, cmd, timeout, sourceIp)
            }
            else -> ToolEnvelope(false, null, McpError("UNKNOWN_TOOL", "未知工具: $name", "检查客户端工具名"))
        }
    }

    /**
     * 读取一条系统设置。
     *
     * 两级策略：
     * 1. 优先用 Android API（快、无依赖）—— 读 `Settings.System/Secure` 对普通应用是开放的；
     * 2. 读不到时（ROM 限制、键不存在），若 Shizuku 可用则以 shell 身份再读一次。
     *
     * 第 2 步是必要的：写工具（`set_screen_timeout` / `set_screen_brightness`）走的是
     * shell 通道，读也走同一通道时，核对结果才可信。
     */
    private suspend fun readSetting(ctx: McpContext, ns: String, key: String): String? {
        val viaApi = runCatching {
            when (ns) {
                "system" -> Settings.System.getString(ctx.appContext.contentResolver, key)
                "secure" -> Settings.Secure.getString(ctx.appContext.contentResolver, key)
                else -> null
            }
        }.getOrNull()
        if (!viaApi.isNullOrBlank()) return viaApi.trim()

        if (!ctx.unlockEngine.isShizukuAvailable()) return null
        val r = ctx.unlockEngine.runShell("settings get $ns $key", 5_000)
        val out = r.stdout.trim()
        // `settings get` 对不存在的键输出字面量 "null"
        return out.takeIf { r.succeeded && it.isNotBlank() && it != "null" }
    }

    private suspend fun getPhoneState(ctx: McpContext, sourceIp: String): ToolEnvelope {
        val a = ctx.unlockEngine.availability()

        // 回读「所有 MCP 可写能力」的当前值：调用方据此核对 set_* 是否真的生效
        val brightness = readSetting(ctx, "system", Settings.System.SCREEN_BRIGHTNESS)?.toIntOrNull()
        val brightnessMode = readSetting(ctx, "system", Settings.System.SCREEN_BRIGHTNESS_MODE)?.toIntOrNull()
        val screenOffMs = readSetting(ctx, "system", Settings.System.SCREEN_OFF_TIMEOUT)?.toIntOrNull()
        val lockAfterMs = readSetting(ctx, "secure", LeaseManager.SECURE_LOCK_AFTER_TIMEOUT)?.toIntOrNull()

        val data = buildJsonObject {
            put("screen_on", JsonPrimitive(ctx.unlockEngine.isScreenOn()))
            put("locked", JsonPrimitive(ctx.unlockEngine.isScreenLocked()))
            putJsonObject("channels") {
                put("shizuku", JsonPrimitive(a.shizuku))
                put("accessibility", JsonPrimitive(a.accessibility))
                // 设备管理员是 lock_phone 的第三级兜底，能否用直接决定锁屏会不会失败
                put("device_admin", JsonPrimitive(ScreenLockAdmin.isActive(ctx.appContext)))
            }
            putJsonObject("display") {
                put("brightness", brightness?.let { JsonPrimitive(it) } ?: JsonNull)
                put("brightness_mode", JsonPrimitive(
                    when (brightnessMode) {
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "auto"
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "manual"
                        else -> "unknown"
                    },
                ))
                put("screen_off_timeout_ms", screenOffMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("lock_after_timeout_ms", lockAfterMs?.let { JsonPrimitive(it) } ?: JsonNull)
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
            putJsonObject("security") {
                put("locked_out", JsonPrimitive(ctx.unlockEngine.isLockedOut()))
                put("locked_out_remaining_sec", JsonPrimitive(ctx.unlockEngine.lockedOutRemainingSec()))
                put("failure_count", JsonPrimitive(ctx.unlockEngine.failureCount()))
                put("fail_threshold", JsonPrimitive(FAIL_THRESHOLD))
            }
            // 兼容旧字段：保留顶层 locked_out
            put("locked_out", JsonPrimitive(ctx.unlockEngine.isLockedOut()))
            putJsonObject("capabilities") {
                // unlock_phone 的前提：没设 PIN 则必然失败
                put("pin_set", JsonPrimitive(ctx.pinStore.hasPin()))
                // set_screen_timeout 的前提：缺 WRITE_SETTINGS 会返回 PERMISSION_MISSING
                put("can_write_settings", JsonPrimitive(Settings.System.canWrite(ctx.appContext)))
            }
            put("server_start_error", JsonPrimitive(ctx.lastStartError ?: ""))
        }
        ctx.audit.record(sourceIp, "get_phone_state", "-", true, null)
        return ToolEnvelope(true, data)
    }

    private suspend fun unlockPhone(ctx: McpContext, ttlSeconds: Int, sourceIp: String): ToolEnvelope {
        // ttl 必须 ≥ 1：负值（如 -5）旧实现会静默钳为 1 并真实解锁 → 显式拒绝，避免"非法输入被吞掉后照常执行"
        if (ttlSeconds < 1) {
            ctx.audit.record(sourceIp, "unlock_phone", "ttl=$ttlSeconds", false, ErrorCodes.INVALID_PARAMS)
            return ToolEnvelope(false, null,
                McpError(ErrorCodes.INVALID_PARAMS, "ttl_seconds 必须 ≥ 1", "传入值 $ttlSeconds 无效，合法范围 1–${LeaseManager.MAX_TTL}"))
        }
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
        }
        // 锁屏宽限位于 Settings.Secure，写入需要 WRITE_SECURE_SETTINGS（adb 级），
        // 普通应用通常没有 —— 失败属常态，**不能因此谎报整体成功**，故分开记录
        runCatching {
            Settings.Secure.putInt(c.contentResolver, LeaseManager.SECURE_LOCK_AFTER_TIMEOUT, lockAfterMs)
        }

        // 回读核对：putInt 不抛异常 ≠ 值真的落盘了（ROM 可能拦截或静默丢弃）
        val actualOff = readSetting(ctx, "system", Settings.System.SCREEN_OFF_TIMEOUT)?.toIntOrNull()
        val actualLock = readSetting(ctx, "secure", LeaseManager.SECURE_LOCK_AFTER_TIMEOUT)?.toIntOrNull()
        val offApplied = actualOff == screenOffMs
        val lockApplied = actualLock == lockAfterMs

        val data = buildJsonObject {
            put("applied", JsonPrimitive(offApplied && lockApplied))
            put("screen_off_timeout_ms", actualOff?.let { JsonPrimitive(it) } ?: JsonNull)
            put("screen_off_applied", JsonPrimitive(offApplied))
            put("lock_after_timeout_ms", actualLock?.let { JsonPrimitive(it) } ?: JsonNull)
            put("lock_after_applied", JsonPrimitive(lockApplied))
        }
        ctx.audit.record(
            "local", "set_screen_timeout", "off=$screenOffMs,after=$lockAfterMs", offApplied,
            if (offApplied) null else ErrorCodes.PERMISSION_MISSING,
        )
        if (!offApplied) {
            return ToolEnvelope(false, data, McpError(
                ErrorCodes.PERMISSION_MISSING,
                "灭屏超时未生效（回读 $actualOff ≠ 期望 $screenOffMs）",
                "请确认已授予「修改系统设置」权限",
            ))
        }
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

    /* ------------------------------------------------------------------ */
    /* Shizuku 专属能力：亮度调节 / adb 命令                                */
    /* ------------------------------------------------------------------ */

    /**
     * 屏幕亮度调节。
     *
     * 必须依赖 Shizuku：写 `settings system screen_brightness` 需要 shell 级权限，
     * 应用自身没有 WRITE_SETTINGS 时改不动，只能借 Shizuku 的特权进程代劳。
     */
    private suspend fun setScreenBrightness(
        ctx: McpContext,
        level: Int?,
        auto: Boolean,
        sourceIp: String,
    ): ToolEnvelope {
        if (level == null || level !in 0..255) {
            ctx.audit.record(sourceIp, "set_screen_brightness", "level=$level", false, ErrorCodes.INVALID_PARAMS)
            return ToolEnvelope(false, null, McpError(
                ErrorCodes.INVALID_PARAMS,
                "level 必填且必须在 0–255",
                "传入值 $level 无效（Android 原生亮度值为 0–255：0 最暗、255 最亮）",
            ))
        }
        if (!ctx.unlockEngine.isShizukuAvailable()) {
            return shizukuRequired(ctx, "set_screen_brightness", "level=$level", sourceIp)
        }

        val r = ctx.unlockEngine.setBrightness(level, auto)
        ctx.audit.record(
            sourceIp, "set_screen_brightness", "level=$level auto=$auto", r.succeeded,
            if (r.succeeded) null else ErrorCodes.COMMAND_FAILED,
        )
        return if (r.succeeded) {
            ToolEnvelope(true, buildJsonObject {
                put("level", JsonPrimitive(level))
                put("auto", JsonPrimitive(auto))
                // 回读核对结果：note 为空即回读值与写入值一致
                put("verified", JsonPrimitive(r.note.isBlank()))
                if (r.note.isNotBlank()) put("note", JsonPrimitive(r.note))
            })
        } else {
            ToolEnvelope(false, null, McpError(
                ErrorCodes.COMMAND_FAILED,
                "亮度调节失败：${r.note.ifBlank { r.stderr }}",
                r.stderr.ifBlank { "确认 Shizuku 已授权且服务在运行" },
            ))
        }
    }

    /**
     * 以 adb shell 权限执行一条命令。
     *
     * 这是**高危能力**（等同于把 shell 交出去），故有三重约束：
     * 1. 必须 Shizuku 已授权（唯一执行通道，未授权直接拒绝）；
     * 2. 强制超时（1000–60000ms），超时强杀子进程；
     * 3. 输出在服务端截断（约 8000 字符），避免 Binder 事务溢出。
     *
     * 上报口径：工具自身 ok 表示「命令有没有跑起来」；
     * 命令**自己的成败**看 `data.succeeded` / `exit_code` —— 二者分开，不谎报。
     */
    private suspend fun runAdbCommand(
        ctx: McpContext,
        command: String?,
        timeoutMs: Int,
        sourceIp: String,
    ): ToolEnvelope {
        if (command.isNullOrBlank()) {
            ctx.audit.record(sourceIp, "run_adb_command", "empty", false, ErrorCodes.INVALID_PARAMS)
            return ToolEnvelope(false, null, McpError(
                ErrorCodes.INVALID_PARAMS,
                "command 不能为空",
                "传入要执行的 shell 命令（无需 adb shell 前缀）",
            ))
        }
        if (timeoutMs !in 1_000..60_000) {
            ctx.audit.record(sourceIp, "run_adb_command", "timeout=$timeoutMs", false, ErrorCodes.INVALID_PARAMS)
            return ToolEnvelope(false, null, McpError(
                ErrorCodes.INVALID_PARAMS,
                "timeout_ms 必须在 1000–60000",
                "传入值 $timeoutMs 无效",
            ))
        }
        if (!ctx.unlockEngine.isShizukuAvailable()) {
            return shizukuRequired(ctx, "run_adb_command", command.take(80), sourceIp)
        }

        val r = ctx.unlockEngine.runShell(command, timeoutMs)
        // 被护栏拦截 / 执行失败 / 命令返回非 0，三种情况分别留痕，便于事后追溯
        val failCode = when {
            r.blocked -> ErrorCodes.COMMAND_BLOCKED
            r.succeeded -> null
            else -> ErrorCodes.COMMAND_FAILED
        }
        // 审计里截断命令正文，避免超长命令污染日志
        ctx.audit.record(
            sourceIp, "run_adb_command", "exit=${r.exitCode} ${command.take(100)}", r.succeeded, failCode,
        )
        return when {
            r.blocked -> ToolEnvelope(false, null, McpError(
                ErrorCodes.COMMAND_BLOCKED,
                "命令被安全护栏拒绝：${r.note}",
                "本工具禁止执行不可逆的破坏性操作（恢复出厂 / 关机 / 删除分区根目录 / 写磁盘分区 / 提权 / 关闭 ADB 调试 / 卸载本应用）",
            ))
            !r.executed -> ToolEnvelope(false, null, McpError(
                ErrorCodes.COMMAND_FAILED,
                "命令未能执行：${r.note.ifBlank { r.stderr }}",
                "确认 Shizuku 已授权、服务在运行，或减少命令耗时后重试",
            ))
            else -> ToolEnvelope(true, buildJsonObject {
                put("exit_code", JsonPrimitive(r.exitCode))
                put("stdout", JsonPrimitive(r.stdout))
                put("stderr", JsonPrimitive(r.stderr))
                put("timed_out", JsonPrimitive(r.timedOut))
                put("succeeded", JsonPrimitive(r.succeeded))
            })
        }
    }

    /**
     * Shizuku 是上述两个工具的**唯一**执行通道：未授权时明确拒绝，
     * 不降级、不假装成功（与项目「如实上报」一致）。
     */
    private suspend fun shizukuRequired(
        ctx: McpContext,
        tool: String,
        detail: String,
        sourceIp: String,
    ): ToolEnvelope {
        ctx.audit.record(sourceIp, tool, detail, false, ErrorCodes.SHIZUKU_UNAVAILABLE)
        return ToolEnvelope(false, null, McpError(
            ErrorCodes.SHIZUKU_UNAVAILABLE,
            "该工具需要 Shizuku 授权才能执行",
            "请在 Shizuku 中授权本应用（或开启无线调试后重启 Shizuku），再重试",
        ))
    }
}
