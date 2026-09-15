@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.unlockguard.mcp.mcp

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 生产 JSON 配置（线上序列化的唯一来源）。
 *
 * 这里刻意**不开** `encodeDefaults`：kotlinx.serialization 默认 `encodeDefaults = false`，
 * 「值等于默认值」的属性会被省略。`RpcResponse` 的 `result` / `error` 均为默认值 `null`，
 * 于是为空时自动省略 —— 正是靠这一点实现 JSON-RPC 2.0 要求的 result/error 互斥
 * （成功只出 result，失败只出 error，绝不会同时出现 `result:null` 或 `error:null`）。
 *
 * 反过来，那些「必须永远出现在报文里」的字段（`jsonrpc`、`id`）不能靠默认值规则，
 * 用逐字段 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)` 钉住（见 RpcResponse）。
 *
 * 本实例是 internal 顶层常量，供单元测试直接复用，避免「测试通过但生产配置悄悄漂移」。
 */
internal val mcpJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Streamable HTTP MCP Server（Ktor CIO）。
 * - POST /mcp：JSON-RPC（initialize / ping / tools/list / tools/call）
 * - GET  /mcp：SSE 端点事件（会话建立）
 * - DELETE /mcp：终止会话
 * - GET  /health：免 Token 低敏健康检查（区分「服务挂了」与「Token 错了」）
 * 鉴权：Bearer Token；限流：每 Token 每分钟上限。
 */
class McpServer(private val ctx: McpContext) {

    // 直接复用生产单例，切勿在此另建 Json 实例（否则易与 mcpJson 配置漂移）。
    private val json = mcpJson
    // Ktor 3 起 EmbeddedServer 不再实现 ApplicationEngine，故按具体泛型类型持有。
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val sessions = ConcurrentHashMap<String, Long>()

    fun start() {
        engine = embeddedServer(CIO, host = ctx.bindHost, port = ctx.port) {
            routing {
                get("/health") { call.respondText(healthJson(), ContentType.Application.Json) }

                post("/mcp") { call.handlePost() }
                get("/mcp") { call.handleGet() }
                delete("/mcp") { call.respondText("", status = HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        Log.i(TAG, "MCP server started on ${ctx.bindHost}:${ctx.port}")
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        sessions.clear()
        Log.i(TAG, "MCP server stopped")
    }

    // ---------- 鉴权 ----------
    private fun validAuth(header: String?): Boolean {
        if (header == null) return false
        val parts = header.split(" ", limit = 2)
        return parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true) && parts[1] == ctx.token
    }

    // ---------- POST /mcp ----------
    private suspend fun ApplicationCall.handlePost() {
        if (!validAuth(request.headers["Authorization"])) {
            respondJson(HttpStatusCode.Unauthorized, RpcResponse(error = RpcError(-32001, "Unauthorized")))
            return
        }
        val body = runCatching { receiveText() }.getOrElse {
            respondJson(HttpStatusCode.BadRequest, RpcResponse(error = RpcError(-32700, "Parse error")))
            return
        }
        val req = runCatching { json.decodeFromString(RpcRequest.serializer(), body) }.getOrElse {
            respondJson(HttpStatusCode.BadRequest, RpcResponse(error = RpcError(-32700, "Parse error")))
            return
        }
        // JSON-RPC 通知（无 id）不得返回响应体；静默接受（含 notifications/initialized）
        if (req.id == null) {
            respondText("", status = HttpStatusCode.Accepted)
            return
        }

        // 会话校验：带 Mcp-Session-Id 但服务端未记录（且非 initialize）→ 拒绝，避免伪造 sid 越权
        val headerSid = request.headers["Mcp-Session-Id"]
        val sid = if (headerSid.isNullOrBlank()) {
            newSession()
        } else if (sessions.containsKey(headerSid) || req.method == "initialize") {
            headerSid
        } else {
            respondJson(HttpStatusCode.NotFound,
                RpcResponse(id = req.id, error = RpcError(-32002, "Invalid or expired session")))
            return
        }

        val rl = ctx.rateLimiters.getOrPut(ctx.token) { com.unlockguard.mcp.domain.RateLimiter() }
        if (!rl.tryRequest()) {
            respondJson(HttpStatusCode.TooManyRequests,
                RpcResponse(id = req.id, error = RpcError(-32000, ErrorCodes.RATE_LIMITED, hintObj("请求过于频繁，请稍后"))))
            return
        }

        val resp = when (req.method) {
            "initialize" -> RpcResponse(id = req.id, result = initResult())
            "ping" -> RpcResponse(id = req.id, result = JsonObject(mapOf("ok" to JsonPrimitive(true))))
            "tools/list" -> RpcResponse(id = req.id, result = toolsList())
            "tools/call" -> handleCall(req)
            else -> RpcResponse(id = req.id, error = RpcError(-32601, "Method not found"))
        }
        response.headers.append("Mcp-Session-Id", sid)
        respondJson(HttpStatusCode.OK, resp)
    }

    // ---------- GET /mcp (SSE) ----------
    private suspend fun ApplicationCall.handleGet() {
        // Streamable HTTP（2024-11-05）中 GET /mcp 是「服务端→客户端」可选的推送通道；
        // 本服务不主动推送，按规范返回 405，不再下发旧的 event: endpoint 协商事件。
        respondText("", status = HttpStatusCode.MethodNotAllowed)
    }

    // ---------- tools/call ----------
    private suspend fun handleCall(req: RpcRequest): RpcResponse {
        val params = req.params?.jsonObject ?: JsonObject(emptyMap())
        val name = params["name"]?.jsonPrimitive?.content
        if (name == null) return RpcResponse(id = req.id, error = RpcError(-32602, "missing tool name"))
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val sourceIp = "127.0.0.1" // 真实来源见接入层；本机服务以本地优先
        // 参数类型错误 / 业务异常若冒泡会导致 500 裸文本；在此统一兜成结构化错误
        val env = runCatching { Tools.dispatch(ctx, name, args, sourceIp) }.getOrElse { e ->
            Log.w(TAG, "tools/call 执行异常: $name", e)
            ToolEnvelope(false, null, McpError(
                ErrorCodes.INVALID_PARAMS, "调用失败：${e.message}", "请检查参数类型（整数类字段勿传字符串）"))
        }

        val content = buildJsonObject {
            put("type", JsonPrimitive("text"))
            // MCP 规范要求 TextContent.text 必须是 string：结构化结果先序列化为字符串
            put("text", JsonPrimitive(json.encodeToString(env)))
        }
        val result = buildJsonObject {
            putJsonArray("content") { add(content) }
            put("isError", JsonPrimitive(!env.ok))
        }
        return RpcResponse(id = req.id, result = result)
    }

    // ---------- 辅助 ----------
    private fun newSession(): String {
        val id = "sess_" + (System.nanoTime().toString(36))
        sessions[id] = System.currentTimeMillis()
        return id
    }

    private fun initResult(): JsonElement = buildJsonObject {
        put("protocolVersion", JsonPrimitive("2024-11-05"))
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        putJsonObject("serverInfo") {
            put("name", JsonPrimitive("unlock-guard-mcp"))
            put("version", JsonPrimitive(ctx.version))
        }
    }

    private fun toolsList(): JsonElement {
        fun schema(props: Map<String, String>): JsonObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                props.forEach { (k, v) ->
                    putJsonObject(k) { put("type", JsonPrimitive(v)); put("description", JsonPrimitive(k)) }
                }
            }
        }
        return buildJsonObject {
            putJsonArray("tools") {
                add(toolDef("get_phone_state", "查询手机屏幕/锁屏/通道可用性/租约状态", emptyMap()))
                add(toolDef("unlock_phone", "解锁并返回带 TTL 的租约", mapOf("ttl_seconds" to "integer")))
                add(toolDef("release_lease", "提前释放租约并还原设置", mapOf("lease_id" to "string")))
                add(
                    toolDef(
                        "lock_phone",
                        "锁屏。按 无障碍 GLOBAL_ACTION_LOCK_SCREEN（首选，保留指纹）→ 设备管理员 lockNow（保底，生物识别失效）→ Shizuku 逐级降级；" +
                            "返回实际使用的 channel_used 与 biometric_preserved",
                        emptyMap(),
                    ),
                )
                add(toolDef("set_screen_timeout", "设置熄屏与锁屏宽限时间", mapOf("screen_off_ms" to "integer", "lock_after_ms" to "integer")))
                add(toolDef("restore_settings", "还原系统设置快照（会释放当前租约）", emptyMap()))
                add(toolDef("grant_debug_auth", "上报无线调试授权状态与引导", emptyMap()))
            }
        }
    }

    private fun toolDef(name: String, desc: String, props: Map<String, String>): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(desc))
        // 工具级注解：帮助客户端做权限 / 确认策略（只读 vs 有副作用）
        putJsonObject("annotations") {
            when (name) {
                "get_phone_state", "grant_debug_auth" -> put("readOnlyHint", JsonPrimitive(true))
                else -> put("destructiveHint", JsonPrimitive(true))
            }
            if (name == "release_lease") put("idempotentHint", JsonPrimitive(true))
        }
        put("inputSchema", buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonArray("required") {} // 当前所有参数均有默认值，无强制必填
            put("additionalProperties", JsonPrimitive(false))
            putJsonObject("properties") {
                props.forEach { (k, v) -> putJsonObject(k) { put("type", JsonPrimitive(v)); put("description", JsonPrimitive(k)) } }
            }
        })
    }

    private fun healthJson(): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("status", JsonPrimitive("ok"))
        put("service", JsonPrimitive("unlock-guard-mcp"))
        put("version", JsonPrimitive(ctx.version))
        put("lan", JsonPrimitive(ctx.isLan))
    })

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, resp: RpcResponse) {
        respondText(json.encodeToString(RpcResponse.serializer(), resp), ContentType.Application.Json, status)
    }

    private fun hintObj(hint: String): JsonObject = buildJsonObject { put("hint", JsonPrimitive(hint)) }

    companion object {
        private const val TAG = "McpServer"
    }
}

@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
)

@Serializable
data class RpcResponse(
    // P1-A：JSON-RPC 2.0 §4 要求每个响应都带 "jsonrpc":"2.0"，§5 要求 id 必填。
    // 这两个字段都有默认值，靠默认值省略规则会被吃掉，故用 ALWAYS 逐字段强制写出
    // （不受全局 encodeDefaults 影响，且无需开启全局开关）。
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val jsonrpc: String = "2.0",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val id: JsonElement? = null,
    // result / error 刻意不加 @EncodeDefault：它们默认 null，为空时省略，实现二者的互斥。
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)
