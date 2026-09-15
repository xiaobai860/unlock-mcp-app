package com.unlockguard.mcp.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-RPC 响应「线上格式（wire format）」契约测试（纯 JVM，无需真机 / Android 运行时）。
 *
 * 目的：锁定 MCP 协议不变量 —— 每个响应对象在**序列化后**必须同时带有
 *   (a) `"jsonrpc":"2.0"`；
 *   (b) 正确的 `id`（即使无法判定也必须存在、为 null）；
 *   (c) `result` 与 `error` **互斥**：成功只出 `result`（不得出现 `error` 成员），
 *       失败只出 `error`（不得出现 `result` 成员）——且判定用「成员是否存在」，不是「是否为 null」。
 *
 * 关键：直接复用生产 Json 实例 `mcpJson`（见 McpServer.kt 的 internal 顶层常量），
 * 保证测试与线上逐字一致，避免「测试通过但生产配置悄悄漂移」。
 */
class JsonRpcWireContractTest {

    /** 与生产完全同源的 Json 实例。 */
    private val productionJson = mcpJson

    // ---------- jsonrpc / id 必须出现在线上 ----------

    @Test
    fun `success response carries jsonrpc 2_0 and echoes numeric id on the wire`() {
        // tools/call、tools/list、ping 等成功响应的组装形态
        val resp = RpcResponse(id = JsonPrimitive(1), result = JsonObject(emptyMap()))
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertNotNull("线上 JSON 必须包含 jsonrpc 字段，实际 wire=$wire", obj["jsonrpc"])
        assertEquals("2.0", obj["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, obj["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `server-discover-style response keeps jsonrpc 2_0 with string id`() {
        // 2026-07-28 移除 initialize 握手，改为 server/discover 声明支持的协议版本
        val resp = RpcResponse(
            id = JsonPrimitive("sess-abc"),
            result = JsonObject(mapOf("protocolVersions" to JsonPrimitive("2026-07-28"))),
        )
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertNotNull("线上 JSON 必须包含 jsonrpc 字段，实际 wire=$wire", obj["jsonrpc"])
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("sess-abc", obj["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `error response also carries jsonrpc 2_0`() {
        val resp = RpcResponse(error = RpcError(-32001, "Unauthorized"))
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertNotNull("线上 JSON 必须包含 jsonrpc 字段，实际 wire=$wire", obj["jsonrpc"])
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(-32001, obj["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    /**
     * 回归护栏：对象层面的 jsonrpc 默认值必须为 "2.0"。
     * 同时固化「RpcResponse 首个位置参数是 jsonrpc」这一陷阱 —— 位置参数误用会把
     * 请求 id 灌进 jsonrpc 字段（历史 bug 的根因）。
     */
    @Test
    fun `object-level default is 2_0 and first positional param is jsonrpc`() {
        val resp = RpcResponse(id = JsonPrimitive(7), result = JsonObject(emptyMap()))
        assertEquals("2.0", resp.jsonrpc)
        // 首参是 String 型的 jsonrpc：若误用位置参数传字符串 id，会被塞进 jsonrpc 字段
        assertEquals("req-1", RpcResponse("req-1").jsonrpc)
    }

    @Test
    fun `standard request envelope decodes`() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        val req = productionJson.decodeFromString(RpcRequest.serializer(), body)
        assertEquals("2.0", req.jsonrpc)
        assertEquals("tools/list", req.method)
    }

    // ---------- result / error 字段互斥（本轮关键新增验证点）----------

    @Test
    fun `success response omits the error member entirely`() {
        val resp = RpcResponse(id = JsonPrimitive(1), result = JsonObject(emptyMap()))
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertTrue("成功响应必须带 id，wire=$wire", obj.containsKey("id"))
        assertTrue("成功响应必须带 result，wire=$wire", obj.containsKey("result"))
        // 成员「不存在」，而不是「等于 null」——二者在 JSON-RPC 客户端里的判定语义不同
        assertFalse("成功响应不得出现 error 成员（哪怕为 null），wire=$wire", obj.containsKey("error"))
    }

    @Test
    fun `error response omits the result member entirely`() {
        val resp = RpcResponse(id = JsonPrimitive(7), error = RpcError(-32001, "Unauthorized"))
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertTrue("错误响应必须带 id，wire=$wire", obj.containsKey("id"))
        assertTrue("错误响应必须带 error，wire=$wire", obj.containsKey("error"))
        // result:null 会让部分客户端「见 result 即判成功」而误判，必须整个省略
        assertFalse("错误响应不得出现 result 成员（哪怕为 null），wire=$wire", obj.containsKey("result"))
    }

    @Test
    fun `error response with undeterminable id still carries an explicit null id`() {
        // 鉴权失败 / 解析失败：拿不到请求 id —— JSON-RPC 2.0 §5 要求此时 id 仍必须存在且为 null
        val resp = RpcResponse(error = RpcError(-32700, "Parse error"))
        val wire = productionJson.encodeToString(RpcResponse.serializer(), resp)
        val obj = productionJson.parseToJsonElement(wire).jsonObject

        assertTrue("无法判定 id 时，id 成员仍必须存在，wire=$wire", obj.containsKey("id"))
        assertEquals(JsonNull, obj["id"])
        assertTrue("错误响应必须带 error，wire=$wire", obj.containsKey("error"))
        assertFalse("错误响应不得出现 result 成员，wire=$wire", obj.containsKey("result"))
    }
}
