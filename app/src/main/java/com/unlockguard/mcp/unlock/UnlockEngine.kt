package com.unlockguard.mcp.unlock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.unlockguard.mcp.domain.LeaseManager
import com.unlockguard.mcp.domain.PinStore
import com.unlockguard.mcp.domain.RateLimiter
import com.unlockguard.mcp.domain.LockChannel
import com.unlockguard.mcp.domain.UnlockChannel
import com.unlockguard.mcp.device.ScreenLockAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 通道可用性 */
data class ChannelAvailability(
    val shizuku: Boolean,
    val accessibility: Boolean,
)

/** 解锁结果：成功带通道；失败带错误码与引导提示 */
sealed interface UnlockResult {
    data class Ok(val channel: UnlockChannel) : UnlockResult
    data class Failed(val code: String, val hint: String) : UnlockResult
}

sealed interface LockResult {
    /**
     * @param channel            实际生效的锁屏通道（[LockChannel.NONE] = 调用前屏幕本来就锁着）
     * @param alreadyLocked      调用前已处于锁定态，未执行任何动作
     * @param biometricPreserved 锁屏后指纹/人脸是否仍可用。设备管理员兜底时为 false —— 这是
     *                           三级降级里唯一对用户可见的差异，必须如实回报给 AI 客户端。
     */
    data class Ok(
        val channel: LockChannel,
        val alreadyLocked: Boolean = false,
        val biometricPreserved: Boolean = true,
    ) : LockResult

    data class Failed(val code: String, val hint: String) : LockResult
}

interface UnlockEngine {
    fun availability(): ChannelAvailability
    fun isShizukuAvailable(): Boolean
    fun isAccessibilityEnabled(): Boolean
    fun isLockedOut(): Boolean
    /** 当前连续解锁失败次数（UI 展示用） */
    fun failureCount(): Int = 0
    /** 安全锁定剩余秒数（UI 展示用） */
    fun lockedOutRemainingSec(): Int = 0
    suspend fun tryUnlock(): UnlockResult
    suspend fun lock(): LockResult
    fun isScreenLocked(): Boolean
    fun isScreenOn(): Boolean

    /** 解锁验证：可选先锁屏，然后实际走一遍完整解锁并逐步回报 */
    suspend fun verify(lockFirst: Boolean): VerifyReport
}

/**
 * 真实解锁引擎。通道优先级是**硬约束**：
 *
 * 1. **Shizuku（主）** —— 有授权且 UserService 绑定成功时，事件由 shizuku_server 以 shell/root 身份注入；
 * 2. **无障碍（备）** —— 仅当 Shizuku 不可用，或主通道注入没生效时才启用；
 * 3. 两者都不可用 → 明确报错，永不假装成功。
 *
 * 判定成功只有一个标准：`KeyguardManager.isKeyguardLocked()` 变回 false。
 * 注入动作"下发成功"不等于"解锁成功"，二者严格区分，避免把失败谎报成成功。
 */
class RealUnlockEngine(
    private val context: Context,
    private val pinStore: PinStore,
    private val rateLimiter: RateLimiter,
    private val leaseManager: LeaseManager,
) : UnlockEngine {

    private val shizuku = ShizukuChannel(context)
    private val accessibility = AccessibilityChannel()

    override fun availability() = ChannelAvailability(shizuku.isAvailable(), accessibility.isEnabled())
    override fun isShizukuAvailable() = shizuku.isAvailable()
    override fun isAccessibilityEnabled() = accessibility.isEnabled()
    override fun isLockedOut(): Boolean = rateLimiter.isLockedOut()
    override fun failureCount(): Int = rateLimiter.failureCount()
    override fun lockedOutRemainingSec(): Int = rateLimiter.lockedOutRemainingSec()

    override fun isScreenLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
            ?.isKeyguardLocked ?: false

    override fun isScreenOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive ?: true
    }

    override suspend fun tryUnlock(): UnlockResult {
        val pin = pinStore.getPin()
            ?: return UnlockResult.Failed("PERMISSION_MISSING", "请先在 App 内设置 PIN")

        if (rateLimiter.isLockedOut())
            return UnlockResult.Failed("LOCKED_OUT", "连续失败锁定中，约 ${rateLimiter.lockedOutRemainingSec()}s 后恢复")

        val accessibilityReady = accessibility.isEnabled()
        var a11yResult: A11yUnlockResult? = null

        // ---------- 主通道：Shizuku（未授权则直接跳到备通道） ----------
        if (shizuku.isAvailable()) {
            val report = shizuku.unlock(pin)

            if (!isScreenLocked()) {
                rateLimiter.resetFailures()
                return UnlockResult.Ok(UnlockChannel.SHIZUKU)
            }

            // 注入都没成功 → 主通道链路不通，交无障碍确认；不计入 PIN 失败
            if (!report.injected) {
                a11yResult = if (accessibilityReady) accessibility.unlock(pin) else null
                if (a11yResult is A11yUnlockResult.PinEntered && !isScreenLocked()) {
                    rateLimiter.resetFailures()
                    return UnlockResult.Ok(UnlockChannel.ACCESSIBILITY)
                }
                return UnlockResult.Failed(
                    "SHIZUKU_INJECT_FAILED",
                    "Shizuku 事件注入未生效（${report.note.ifBlank { "后端 " + report.backend }}），已尝试无障碍备通道${a11yHint(a11yResult)}",
                )
            }

            // 注入成功但没解开 → 用备通道再确认一次（结果用于失败结算）
            a11yResult = if (accessibilityReady) accessibility.unlock(pin) else null
            if (a11yResult is A11yUnlockResult.PinEntered && !isScreenLocked()) {
                rateLimiter.resetFailures()
                return UnlockResult.Ok(UnlockChannel.ACCESSIBILITY)
            }
        } else if (accessibilityReady) {
            a11yResult = accessibility.unlock(pin)
            if (a11yResult is A11yUnlockResult.PinEntered && !isScreenLocked()) {
                rateLimiter.resetFailures()
                return UnlockResult.Ok(UnlockChannel.ACCESSIBILITY)
            }
        } else {
            return UnlockResult.Failed(
                "SHIZUKU_UNAVAILABLE",
                "Shizuku 未授权且无无障碍通道：请先授权 Shizuku，或开启无障碍服务",
            )
        }

        // 失败结算：
        // 仅当"确实把 PIN 输进去但屏仍锁"才视为 PIN 不符并计入连续失败；
        // 无障碍侧的基础设施失败（点不亮屏 / 定位不到锁屏 / 缺数字键）与 PIN 对错无关，
        // 不计入 —— 否则会把"ROM 限制"误判成"PIN 错误"，误导用户且可能误触发 LOCKED_OUT。
        return when (a11yResult) {
            is A11yUnlockResult.PinEntered, null -> failWithCount()
            A11yUnlockResult.ScreenOffWakeFailed ->
                UnlockResult.Failed("A11Y_WAKE_FAILED", "无障碍无法点亮屏幕（HyperOS 限制或电池优化拦截全局动作），请改用 Shizuku，或保持屏幕亮着再解锁")
            A11yUnlockResult.KeyguardNotFound ->
                UnlockResult.Failed("A11Y_KEYGUARD_NOT_FOUND", "无障碍已点亮屏但定位不到 PIN 锁屏（误拉起面板收不回，或 Keyguard 窗口被 ROM 隐藏），请改用 Shizuku")
            is A11yUnlockResult.DigitMissing ->
                UnlockResult.Failed("A11Y_DIGIT_MISSING", "PIN 键盘缺数字键 '${a11yResult.ch}'，可能是 ROM 节点降级，或 PIN 与锁屏密码不一致，请改用 Shizuku")
        }
    }

    private fun failWithCount(): UnlockResult {
        val locked = rateLimiter.recordUnlockFailure()
        return if (locked) {
            UnlockResult.Failed("LOCKED_OUT", "连续失败达上限，已暂停自动解锁 10 分钟")
        } else {
            UnlockResult.Failed("PIN_MISMATCH", "PIN 输入后锁屏未解开，请核对 PIN 是否为锁屏密码")
        }
    }

    private fun a11yHint(r: A11yUnlockResult?): String = when (r) {
        null -> "（无障碍不可用）"
        A11yUnlockResult.PinEntered -> "（无障碍已输入 PIN 但屏仍锁）"
        A11yUnlockResult.ScreenOffWakeFailed -> "（无障碍也无法点亮屏幕）"
        A11yUnlockResult.KeyguardNotFound -> "（无障碍已亮屏但定位不到 PIN 锁屏）"
        is A11yUnlockResult.DigitMissing -> "（无障碍缺数字键 '${r.ch}'）"
    }

    override suspend fun lock(): LockResult {
        // 已处于锁定态：无需任何动作
        if (isScreenLocked()) {
            return LockResult.Ok(LockChannel.NONE, alreadyLocked = true, biometricPreserved = true)
        }

        // 1) 无障碍锁屏（首选）：GLOBAL_ACTION_LOCK_SCREEN 等效按电源键，**保留指纹/人脸/Smart Lock**。
        if (accessibility.isEnabled()) {
            accessibility.lock()
            delay(LOCK_VERIFY_DELAY_MS)
            if (isScreenLocked()) {
                return LockResult.Ok(LockChannel.ACCESSIBILITY, biometricPreserved = true)
            }
        }

        // 2) 设备管理员兜底：lockNow() 强制进入 PRIMARY_BOUNCER，**生物识别失效、只能输 PIN**。
        if (ScreenLockAdmin.lockNow(context)) {
            delay(LOCK_VERIFY_DELAY_MS)
            if (isScreenLocked()) {
                return LockResult.Ok(LockChannel.DEVICE_ADMIN, biometricPreserved = false)
            }
        }

        // 3) Shizuku 注入 SLEEP（等效电源键，保留生物识别）
        if (shizuku.isAvailable()) {
            shizuku.lock()
            delay(LOCK_VERIFY_DELAY_MS)
            if (isScreenLocked()) {
                return LockResult.Ok(LockChannel.SHIZUKU, biometricPreserved = true)
            }
        }

        return LockResult.Failed(
            "PERMISSION_MISSING",
            "锁屏需要无障碍、设备管理员或 Shizuku 中至少一项可用",
        )
    }

    /* --------------------------------------------------------------------- */
    /* 解锁验证                                                               */
    /* --------------------------------------------------------------------- */

    override suspend fun verify(lockFirst: Boolean): VerifyReport = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        val steps = mutableListOf<UnlockStep>()
        Log.i(TAG, "verify 开始 lockFirst=$lockFirst")

        fun report(ok: Boolean, ch: UnlockChannel, backend: String, conclusion: String) =
            VerifyReport(
                ok = ok,
                channel = ch,
                backend = backend,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                steps = steps.toList(),
                conclusion = conclusion,
            )

        val pin = pinStore.getPin()
            ?: run {
                Log.w(TAG, "verify: 未设置 PIN，直接结束")
                return@withContext report(false, UnlockChannel.NONE, "-", "尚未设置 PIN：请先在「设置 → 解锁凭据」中设置解锁 PIN")
            }

        steps += UnlockStep("读取解锁 PIN", true, "已从 Keystore 加密存储读取（${pin.length} 位）")
        Log.d(TAG, "verify: PIN 长度=${pin.length}")

        if (rateLimiter.isLockedOut()) {
            steps += UnlockStep("安全锁定检查", false, "连续失败已达上限")
            Log.w(TAG, "verify: 处于安全锁定中，结束")
            return@withContext report(
                false, UnlockChannel.NONE, "-",
                "当前处于安全锁定中，约 ${rateLimiter.lockedOutRemainingSec()}s 后重试",
            )
        }
        steps += UnlockStep("安全锁定检查", true, "未处于失败锁定状态")

        if (lockFirst) {
            val lockResult = lock()
            val lockedOk = lockResult is LockResult.Ok
            Log.i(TAG, "verify: 先行锁屏结果=$lockedOk channel=${(lockResult as? LockResult.Ok)?.channel}")
            steps += UnlockStep(
                "先行锁屏",
                lockedOk,
                if (lockedOk) "已下发锁屏指令（无障碍 / 设备管理员 / Shizuku 三级兜底）" else "锁屏能力不可用（需 无障碍 / 设备管理员 / Shizuku 至少一项）",
            )
            delay(1800)
            // 锁屏 = 等效按电源键，屏已熄灭。无障碍输入 PIN 前必须先把屏点亮并回到 PIN 锁屏：
            // 走 accessibility.wake()（全局动作间接亮屏），结果记入步骤，便于在日志里看到是否点亮。
            // HyperOS 上全局动作可能仍点不亮屏 —— 此时 inputPinOnKeyguard 会直接放弃并报错，不谎报成功；
            // 用户需改用 Shizuku（KEYCODE_WAKEUP 可靠亮屏），或保持屏幕亮着再触发验证。
            val woke = accessibility.wake()
            Log.i(TAG, "verify: 锁屏后无障碍唤醒结果=$woke")
            steps += UnlockStep(
                "点亮屏幕（无障碍）",
                woke,
                if (woke) "已亮屏，准备输入 PIN" else "无障碍无法点亮屏幕（HyperOS 限制）：PIN 输入可能失败，建议改用 Shizuku 或保持屏幕亮着再验证",
            )
            delay(500)
        }

        val wasLocked = isScreenLocked()
        val screenOn = isScreenOn()
        Log.i(TAG, "verify: 锁屏状态 wasLocked=$wasLocked screenOn=$screenOn")
        steps += UnlockStep(
            "锁屏状态确认",
            wasLocked,
            if (wasLocked) "屏幕当前已锁定，开始执行解锁" else "屏幕当前未锁定：本次将验证注入链路，但无法证明能解开锁屏",
        )

        var channel = UnlockChannel.NONE
        var backend = "-"
        var injected = false
        var shizukuNote = ""

        if (shizuku.isAvailable()) {
            Log.i(TAG, "verify: 走 Shizuku 主通道")
            val rep = shizuku.unlock(pin)
            steps += rep.steps
            channel = UnlockChannel.SHIZUKU
            backend = rep.backend
            injected = rep.injected
            shizukuNote = rep.note
            Log.i(TAG, "verify: Shizuku 结果 injected=${rep.injected} unlocked=${!isScreenLocked()}")
        } else {
            Log.i(TAG, "verify: Shizuku 不可用，降级无障碍备通道")
            steps += UnlockStep(
                "Shizuku 主通道",
                false,
                "未授权或服务端非特权身份 → 按优先级降级到无障碍备通道",
            )
        }

        var unlocked = wasLocked && !isScreenLocked()
        Log.d(TAG, "verify: 主通道后 unlocked=$unlocked")

        // 备通道：仅在主通道没解开时启用
        if (!unlocked && accessibility.isEnabled()) {
            Log.i(TAG, "verify: 走无障碍备通道，输入 PIN")
            val r = accessibility.unlock(pin)
            delay(600)
            val nowLocked = isScreenLocked()
            unlocked = wasLocked && !nowLocked
            Log.i(TAG, "verify: 无障碍 unlock 结果=$r nowLocked=$nowLocked → unlocked=$unlocked")
            steps += UnlockStep(
                "无障碍备通道",
                unlocked,
                if (unlocked) {
                    "在锁屏界面完成 PIN 输入并解开"
                } else when (r) {
                    A11yUnlockResult.PinEntered -> "已下发 PIN 输入但锁屏仍未解开（PIN 可能不符，或自动提交式键盘未触发校验）"
                    A11yUnlockResult.ScreenOffWakeFailed -> "无障碍无法点亮屏幕（HyperOS 限制）：PIN 输入被放弃，建议改用 Shizuku 或保持屏幕亮着再验证"
                    A11yUnlockResult.KeyguardNotFound -> "已点亮屏但定位不到 PIN 锁屏（误拉起面板收不回 / Keyguard 被 ROM 隐藏），建议改用 Shizuku"
                    is A11yUnlockResult.DigitMissing -> "PIN 键盘缺数字键 '${r.ch}'（ROM 节点降级，或 PIN 与锁屏密码不一致），建议改用 Shizuku"
                },
            )
            if (unlocked) {
                channel = UnlockChannel.ACCESSIBILITY
                backend = "accessibility"
            }
        } else if (!unlocked) {
            Log.w(TAG, "verify: 无障碍未启用，无法尝试备通道")
        }

        // 结算：成功清空失败计数；确实是 PIN 错才计入，注入失败不计入
        if (unlocked) {
            rateLimiter.resetFailures()
        } else if (injected) {
            rateLimiter.recordUnlockFailure()
        }

        val conclusion = when {
            unlocked -> "解锁链路正常：事件已注入且屏幕成功解开（通道：${channelName(channel)}，后端：$backend）"
            !wasLocked -> "注入链路已跑通，但当前未处于锁屏，无法给出「能否解锁」的结论。建议勾选「先锁屏再验证」重试。"
            !injected -> "解锁未成功：事件未能注入到系统（${shizukuNote.ifBlank { "注入后端 $backend" }}）。请确认 Shizuku 服务在运行、已授权，或开启无障碍服务作为备通道。"
            else -> "解锁未成功：事件已成功注入但屏幕仍未解开，请核对 App 内 PIN 是否与锁屏密码完全一致。"
        }
        Log.i(TAG, "verify 结束 ok=$unlocked channel=$channel backend=$backend 耗时=${SystemClock.elapsedRealtime() - started}ms")
        report(unlocked, channel, backend, conclusion)
    }

    private fun channelName(ch: UnlockChannel) = when (ch) {
        UnlockChannel.SHIZUKU -> "Shizuku 主通道"
        UnlockChannel.ACCESSIBILITY -> "无障碍备通道"
        UnlockChannel.NONE -> "无"
    }

    private companion object {
        const val TAG = "UnlockEngine"

        /**
         * 锁屏动作下发后等待系统状态机落定的时间，用 KeyguardManager 复核前的缓冲。
         * 350ms 是真机上"熄屏 → Keyguard 进入 LOCKED 态"的实测惯用值。
         */
        const val LOCK_VERIFY_DELAY_MS = 350L
    }
}
