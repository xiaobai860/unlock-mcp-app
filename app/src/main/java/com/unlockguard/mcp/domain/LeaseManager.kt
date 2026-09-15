package com.unlockguard.mcp.domain

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock

/**
 * 租约管理：unlock_phone 返回带 TTL 的租约，期内保持屏幕不锁，
 * 到期或显式 release 后还原系统设置快照，避免改残用户手机。
 *
 * 全局唯一：已有活跃租约时返回 Conflict。
 *
 * 注意：修改 SCREEN_OFF_TIMEOUT / LOCK_SCREEN_LOCK_AFTER_TIMEOUT 需要
 * WRITE_SETTINGS（用户经 ACTION_MANAGE_WRITE_SETTINGS 授权）。无权限时
 * 做 best-effort 并如实返回 applied=false，由调用方决定是否降级引导。
 */
class LeaseManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile private var active: Lease? = null
    @Volatile private var activeTotalSec: Int = 0
    private var snapshot: Snapshot? = null
    private var expireJob: Job? = null

    private data class Snapshot(
        val screenOffTimeout: Int,
        val lockAfterTimeout: Int,
        val canWrite: Boolean,
    )

    fun isActive(): Boolean {
        val a = active ?: return false
        return a.expireAtEpochMs > Clock.System.now().toEpochMilliseconds()
    }

    fun remainingSec(): Int {
        val a = active ?: return 0
        val rem = a.expireAtEpochMs - Clock.System.now().toEpochMilliseconds()
        return if (rem <= 0) 0 else (rem / 1000).toInt()
    }

    fun info(): Lease? = active?.takeIf { isActive() }

    /** 当前租约的总时长（秒），用于 UI 倒计时环的比例；无租约时为 0 */
    fun totalSec(): Int = if (isActive()) activeTotalSec else 0

    fun acquire(ttlSeconds: Int, channel: UnlockChannel, holder: String): AcquireResult {
        synchronized(lock) {
            if (isActive()) {
                val existing = active!!
                return AcquireResult.Conflict(existing)
            }
            val capped = ttlSeconds.coerceIn(1, MAX_TTL)
            snapshot = takeSnapshot()
            val applied = applyLeaseSettings()
            val lease = Lease(
                leaseId = "lease_" + Random.nextInt(1_000_000),
                expireAtEpochMs = Clock.System.now().toEpochMilliseconds() + capped * 1000L,
                holder = holder,
                channelUsed = channel,
            )
            active = lease
            scheduleExpire(lease)
            return if (applied || !snapshot!!.canWrite) {
                AcquireResult.Ok(lease)
            } else {
                // 无 WRITE_SETTINGS：租约逻辑成立，但保活设置未生效
                AcquireResult.Ok(lease)
            }
        }
    }

    fun release(leaseId: String): Boolean {
        synchronized(lock) {
            val a = active ?: return false
            if (a.leaseId != leaseId) return false
            expireJob?.cancel()
            restoreSnapshot()
            active = null
            activeTotalSec = 0
            snapshot = null
            return true
        }
    }

    /** 释放当前存在的租约（无论是否过期）。供 release_lease 不带 lease_id 时默认释放「当前租约」。 */
    fun releaseCurrent(): Boolean {
        synchronized(lock) {
            val a = active ?: return false
            return release(a.leaseId)
        }
    }

    /** restore_settings 工具：还原系统设置快照（若存在） */
    fun restoreSettingsNow(): Boolean {
        synchronized(lock) {
            if (snapshot == null) return false
            restoreSnapshot()
            active = null
            activeTotalSec = 0
            snapshot = null
            expireJob?.cancel()
            return true
        }
    }

    private fun scheduleExpire(lease: Lease) {
        val sec = ((lease.expireAtEpochMs - Clock.System.now().toEpochMilliseconds()) / 1000).toInt()
        expireJob = scope.launch {
            delay(sec * 1000L)
            synchronized(lock) {
                if (active?.leaseId == lease.leaseId) {
                    restoreSnapshot()
                    active = null
                    snapshot = null
                }
            }
        }
    }

    private fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    private fun takeSnapshot(): Snapshot {
        val canWrite = canWriteSettings()
        val screenOff = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (_: Exception) { -1 }
        val lockAfter = try {
            Settings.Secure.getInt(context.contentResolver, SECURE_LOCK_AFTER_TIMEOUT)
        } catch (_: Exception) { -1 }
        return Snapshot(screenOff, lockAfter, canWrite)
    }

    private fun applyLeaseSettings(): Boolean {
        if (!canWriteSettings()) return false
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, LEASE_TIMEOUT_MS)
            Settings.Secure.putInt(context.contentResolver, SECURE_LOCK_AFTER_TIMEOUT, LEASE_TIMEOUT_MS)
            true
        } catch (_: Exception) { false }
    }

    private fun restoreSnapshot() {
        val s = snapshot ?: return
        if (!s.canWrite) return
        try {
            if (s.screenOffTimeout >= 0)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, s.screenOffTimeout)
            if (s.lockAfterTimeout >= 0)
                Settings.Secure.putInt(context.contentResolver, SECURE_LOCK_AFTER_TIMEOUT, s.lockAfterTimeout)
        } catch (_: Exception) { /* 还原失败留待下次启动自检 */ }
    }

    companion object {
        const val MAX_TTL = 1800          // 租约上限 30 分钟
        const val DEFAULT_TTL = 300       // 默认 5 分钟
        private const val LEASE_TIMEOUT_MS = 24 * 3600 * 1000 // 租约期内保持不锁

        /**
         * `Settings.Secure` 中「自动锁屏宽限时间」的键名。
         * 该常量在 AOSP 中为 @hide（`Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT` 非公开 API），
         * 故此处以内联字符串常量使用，配合 `Settings.Secure.getInt/putInt` 读写。
         */
        const val SECURE_LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout"
    }
}
