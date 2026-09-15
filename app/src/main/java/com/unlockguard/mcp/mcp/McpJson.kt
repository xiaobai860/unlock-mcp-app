package com.unlockguard.mcp.mcp

import kotlinx.serialization.json.Json

/**
 * 生产 JSON 配置（线上序列化的唯一来源）。
 *
 * 刻意**不开** `encodeDefaults`：kotlinx.serialization 默认 `encodeDefaults = false`，
 * 「值等于默认值」的属性会被省略。原先正是靠这一点实现 JSON-RPC 2.0 要求的
 * result / error 互斥（成功只出 result，失败只出 error）。
 *
 * 协议层迁到官方 SDK 后，本实例仍用于把 [ToolEnvelope] 结构化结果序列化进
 * `TextContent.text`（MCP 规范要求 text 必须是字符串）。
 *
 * 本实例是 internal 顶层常量，供生产与测试共用，避免「测试通过但生产配置悄悄漂移」。
 */
internal val mcpJson: Json = Json { ignoreUnknownKeys = true }
