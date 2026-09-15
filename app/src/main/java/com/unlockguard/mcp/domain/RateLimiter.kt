package com.unlockguard.mcp.domain

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/**
 * 速率限制 + 安全失败锁定（每个 Token 独立）。
 * - 每分钟请求数上限（默认 120，即 2 次/秒；正常客户端宽裕，仍保留解锁失败锁定作为抗爆破主防线）。
 * - unlock_phone 连续失败 5 次 → 锁定 10 分钟，期间拒绝自动解锁（LOCKED_OUT）。
 */
class RateLimiter(
    private val maxPerMinute: Int = 120,
    private val failThreshold: Int = 5,
    private val lockoutMs: Long = 10 * 60_000L,
) {
    private val timestamps = ConcurrentLinkedDeque<Long>()
    private val failures = AtomicInteger(0)
    private var lockedOutUntil = 0L

    /** 请求级限流：true=放行 */
    fun tryRequest(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        while (timestamps.firstOrNull()?.let { now - it > 60_000 } == true) timestamps.removeFirst()
        if (timestamps.size >= maxPerMinute) return false
        timestamps.addLast(now)
        return true
    }

    fun isLockedOut(): Boolean = Clock.System.now().toEpochMilliseconds() < lockedOutUntil

    /** 记录一次解锁失败；返回 true 表示已触发锁定 */
    fun recordUnlockFailure(): Boolean {
        if (isLockedOut()) return true
        val n = failures.incrementAndGet()
        if (n >= failThreshold) {
            lockedOutUntil = Clock.System.now().toEpochMilliseconds() + lockoutMs
            failures.set(0)
            return true
        }
        return false
    }

    fun resetFailures() = failures.set(0)

    /** 当前连续失败次数（UI「连续解锁失败 n / 5」展示用） */
    fun failureCount(): Int = failures.get()

    /** 阈值（UI 展示 5 用，避免前后端各写一份） */
    fun failThreshold(): Int = failThreshold

    fun lockedOutRemainingSec(): Int {
        val rem = lockedOutUntil - Clock.System.now().toEpochMilliseconds()
        return if (rem <= 0) 0 else (rem / 1000).toInt()
    }
}
