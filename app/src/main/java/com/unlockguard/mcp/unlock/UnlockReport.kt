package com.unlockguard.mcp.unlock

import com.unlockguard.mcp.domain.UnlockChannel

/**
 * 解锁过程中的单步结果 —— 用于「解锁验证」页面逐步展示，
 * 让使用者能看清到底卡在哪一环，而不是只拿到一句「解锁失败」。
 */
data class UnlockStep(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
)

/**
 * 一次通道注入的完整记录。
 *
 * @param channel 本次使用的通道
 * @param backend 该通道实际生效的注入后端（`inputmanager` / `shell` / `accessibility` / `-`）
 * @param injected 注入动作是否全部下发成功
 * @param unlocked 以「锁屏是否真的解开」为准的最终判定（永不假装成功）
 * @param note 降级原因等补充说明
 */
data class InjectReport(
    val channel: UnlockChannel,
    val backend: String,
    val steps: List<UnlockStep>,
    val injected: Boolean,
    val unlocked: Boolean,
    val elapsedMs: Long,
    val note: String = "",
)

/** 解锁验证结果（面向 UI 的完整报告） */
data class VerifyReport(
    val ok: Boolean,
    val channel: UnlockChannel,
    val backend: String,
    val elapsedMs: Long,
    val steps: List<UnlockStep>,
    val conclusion: String,
)
