package com.unlockguard.mcp.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 独立探针（不复用工程师的断言）。
 * 直接序列化生产 `mcpJson`，打印真实线上报文，并独立断言 JSON-RPC 2.0 §4/§5 关键不变量：
 *  - §4：每个响应必须带 "jsonrpc":"2.0"；
 *  - §5：id 必须存在（不可判定时为 null）；result 与 error 互斥（不得同时出现）。
 */
class QaWireProbeTest {

    @Test
    fun probe_actual_wire_bytes() {
        val success = mcpJson.encodeToString(
            RpcResponse.serializer(),
            RpcResponse(id = JsonPrimitive(1), result = JsonObject(emptyMap())),
        )
        val error = mcpJson.encodeToString(
            RpcResponse.serializer(),
            RpcResponse(id = JsonPrimitive("sess-abc"), error = RpcError(-32601, "Method not found")),
        )
        val unauthorized = mcpJson.encodeToString(
            RpcResponse.serializer(),
            RpcResponse(error = RpcError(-32001, "Unauthorized")),
        )
        println("QA_PROBE_SUCCESS=" + success)
        println("QA_PROBE_ERROR=" + error)
        println("QA_PROBE_UNAUTH=" + unauthorized)

        // §4 jsonrpc 必现
        assertTrue("success 缺 jsonrpc: $success", success.contains("\"jsonrpc\":\"2.0\""))
        assertTrue("error 缺 jsonrpc: $error", error.contains("\"jsonrpc\":\"2.0\""))
        assertTrue("unauth 缺 jsonrpc: $unauthorized", unauthorized.contains("\"jsonrpc\":\"2.0\""))
        // §5 id 必现（不可判定时为显式 null）
        assertTrue("success 缺 id: $success", success.contains("\"id\":1"))
        assertTrue("unauth 缺 id:null: $unauthorized", unauthorized.contains("\"id\":null"))
        // §5 result/error 互斥
        assertFalse("success 不应含 error: $success", success.contains("\"error\""))
        assertFalse("error 不应含 result: $error", error.contains("\"result\""))
        assertFalse("unauth 不应含 result: $unauthorized", unauthorized.contains("\"result\""))
    }
}
