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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.unlockguard.mcp.domain.RateLimiter

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
 * Streamable HTTP MCP Server（Ktor CIO）—— 实现 MCP **2026-07-28** 修订版（无状态 Streamable HTTP）。
 *
 * 相较 2024-11-05 / 2025-03-26 的有状态版本，本修订的关键变化：
 *  - 移除 `initialize` / `initialized` 握手与协议级会话（`Mcp-Session-Id` 不再 mint / echo）；
 *  - 每次请求在 `MCP-Protocol-Version` 头（及 `_meta`）自带协议版本；
 *  - 新增 `server/discover` 供客户端查询支持的协议版本与能力；
 *  - `tools/list` 等列表响应须带 `ttlMs` / `cacheScope` 缓存元数据；
 *  - 未实现的 RPC method 返回 HTTP 404（而非 200）；
 *  - 服务端 MUST 校验 `Origin` 头以防御 DNS 重绑定。
 *
 * 端点：
 *  - POST   /mcp：JSON-RPC（server/discover / ping / tools/list / tools/call）
 *  - GET    /mcp：405（无状态修订已移除 GET/SSE 推送通道）
 *  - DELETE /mcp：405（无状态修订已移除会话终止端点）
 *  - GET    /health：免 Token 低敏健康检查（区分「服务挂了」与「Token 错了」）
 * 鉴权：Bearer Token；限流：未鉴权也按客户端 IP 限流（抗 token 爆破）。
 */
class McpServer(private val ctx: McpContext) {

    // 直接复用生产单例，切勿在此另建 Json 实例（否则易与 mcpJson 配置漂移）。
    private val json = mcpJson
    // Ktor 3 起 EmbeddedServer 不再实现 ApplicationEngine，故按具体泛型类型持有。
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * DNS 重绑定防护（MUST）：允许的来源主机集合。
     * - 非浏览器客户端（Node SDK / curl）通常不发送 `Origin` 头 → 默认放行（null/blank）；
     * - 仅 localhost / 回环放行；外部站点（含 DNS 重绑定攻击者驱动的跨站请求）一律 403。
     * 注意：LAN 浏览器来源（如 `http://192.168.x.x:8790`）会被拒绝，但本服务的真实客户端是
     * token 鉴权的 SDK（不发 Origin），不受影响；如需放行特定 LAN 来源可在此扩展。
     */
    private val allowedOriginHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    fun start() {
        runCatching {
            engine = embeddedServer(CIO, host = ctx.bindHost, port = ctx.port) {
                routing {
                    get("/health") {
                        // /health 同样按 IP 限流；仅本机回显版本/LAN 信息，避免对局域网泄露（F3）
                        val ip = call.request.local.remoteHost
                        if (!ctx.ipLimiters.getOrPut(ip) { RateLimiter() }.tryRequest()) {
                            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                            return@get
                        }
                        // MUST: 所有入站连接校验 Origin（含 /health），非法来源 → 403
                        if (!allowedOrigin(call.request.headers["Origin"])) {
                            call.respondText("{\"error\":\"origin_not_allowed\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                            return@get
                        }
                        call.respondText(healthJson(isLocal(ip)), ContentType.Application.Json)
                    }

                    post("/mcp") { call.handlePost() }
                    get("/mcp") { call.handleGet() }
                    delete("/mcp") { call.respondText("", status = HttpStatusCode.MethodNotAllowed) }
                }
            }.start(wait = false)
        }.onFailure { e ->
            // 端口被占用 / 绑定失败不得冒泡到 onStartCommand，否则前台服务启动抛异常会崩进程
            ctx.lastStartError = "端口 ${ctx.port} 绑定失败：${e.message}"
            Log.e(TAG, "MCP server 启动失败", e)
            return
        }
        ctx.lastStartError = null
        Log.i(TAG, "MCP server started on ${ctx.bindHost}:${ctx.port} (MCP $SUPPORTED_VERSION)")
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        Log.i(TAG, "MCP server stopped")
    }

    // ---------- 鉴权 ----------
    private fun validAuth(header: String?): Boolean {
        if (header == null) return false
        val parts = header.split(" ", limit = 2)
        return parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true) && parts[1] == ctx.token
    }

    // ---------- Origin 校验（DNS 重绑定防护，MUST）----------
    private fun allowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true // 非浏览器客户端（SDK/curl）通常不发送 Origin
        val host = origin.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return host in allowedOriginHosts
    }

    // ---------- POST /mcp ----------
    private suspend fun ApplicationCall.handlePost() {
        // 抗爆破：未鉴权也按客户端 IP 限流（key 为 IP 而非 token，否则逐个猜测 token 可绕过 429）
        val clientIp = request.local.remoteHost
        if (!ctx.ipLimiters.getOrPut(clientIp) { RateLimiter() }.tryRequest()) {
            respondJson(HttpStatusCode.TooManyRequests,
                RpcResponse(error = RpcError(-32000, ErrorCodes.RATE_LIMITED, hintObj("请求过于频繁，请稍后"))))
            return
        }
        // MUST: 校验 Origin，非法来源（如 DNS 重绑定驱动的跨站请求）→ 403
        if (!allowedOrigin(request.headers["Origin"])) {
            respondJson(HttpStatusCode.Forbidden,
                RpcResponse(error = RpcError(-32020, "Origin not allowed")))
            return
        }
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
        // 通知（无 id）：202 Accepted 无 body。无状态修订未定义客户端→服务端通知，按传输规则静默接受
        if (req.id == null) {
            respondText("", status = HttpStatusCode.Accepted)
            return
        }
        // MUST: 每个 POST 必须带 MCP-Protocol-Version 头；缺失 → 400
        val headerVersion = request.headers["MCP-Protocol-Version"]
        if (headerVersion == null) {
            respondJson(HttpStatusCode.BadRequest,
                RpcResponse(id = req.id, error = RpcError(-32020, "Missing MCP-Protocol-Version header",
                    hintObj("supported: $SUPPORTED_VERSION"))))
            return
        }
        // MUST: 不支持的协议版本 → 400（列出支持的版本）
        // 不支持的协议版本 → 400 + -32022 UnsupportedProtocolVersion（与 HeaderMismatch -32020 区分）
        if (headerVersion != SUPPORTED_VERSION) {
            respondJson(HttpStatusCode.BadRequest,
                RpcResponse(id = req.id, error = RpcError(-32022, "Unsupported protocol version: $headerVersion",
                    hintObj("supported: $SUPPORTED_VERSION"))))
            return
        }
        // MUST: 报文体 _meta 中的协议版本须与头一致（若提供），否则 HeaderMismatch → 400
        val metaVersion = req.params?.jsonObject?.get("_meta")?.jsonObject
            ?.get("io.modelcontextprotocol/protocolVersion")?.jsonPrimitive?.content
        if (metaVersion != null && metaVersion != headerVersion) {
            respondJson(HttpStatusCode.BadRequest,
                RpcResponse(id = req.id, error = RpcError(-32020,
                    "HeaderMismatch: MCP-Protocol-Version ($headerVersion) != _meta ($metaVersion)")))
            return
        }
        // 合规：若客户端携带 Mcp-Method 头，须与报文体一致（不一致视为头/体被篡改）
        // 注：仅在校验「存在时一致」；缺失该头时不强制拒绝，以兼容尚未发送标准头的客户端。
        val headerMethod = request.headers["Mcp-Method"]
        if (headerMethod != null && headerMethod != req.method) {
            respondJson(HttpStatusCode.BadRequest,
                RpcResponse(id = req.id, error = RpcError(-32020,
                    "HeaderMismatch: Mcp-Method ($headerMethod) != body (${req.method})")))
            return
        }

        val rl = ctx.rateLimiters.getOrPut(ctx.token) { com.unlockguard.mcp.domain.RateLimiter() }
        if (!rl.tryRequest()) {
            respondJson(HttpStatusCode.TooManyRequests,
                RpcResponse(id = req.id, error = RpcError(-32000, ErrorCodes.RATE_LIMITED, hintObj("请求过于频繁，请稍后"))))
            return
        }

        when (req.method) {
            "ping" -> respondJson(HttpStatusCode.OK,
                RpcResponse(id = req.id, result = JsonObject(mapOf("ok" to JsonPrimitive(true)))))
            "server/discover" -> respondJson(HttpStatusCode.OK, RpcResponse(id = req.id, result = discoverResult()))
            "tools/list" -> respondJson(HttpStatusCode.OK, RpcResponse(id = req.id, result = toolsList()))
            "tools/call" -> respondJson(HttpStatusCode.OK, handleCall(req))
            // MUST: 未实现的 RPC method → HTTP 404 + -32601
            else -> respondJson(HttpStatusCode.NotFound,
                RpcResponse(id = req.id, error = RpcError(-32601, "Method not found")))
        }
    }

    // ---------- GET /mcp ----------
    private suspend fun ApplicationCall.handleGet() {
        // 无状态 Streamable HTTP（2026-07-28）已移除 GET/SSE 推送通道 → 405
        respondText("", status = HttpStatusCode.MethodNotAllowed)
    }

    // ---------- tools/call ----------
    private suspend fun ApplicationCall.handleCall(req: RpcRequest): RpcResponse {
        val params = req.params?.jsonObject ?: JsonObject(emptyMap())
        val name = params["name"]?.jsonPrimitive?.content
        if (name == null) return RpcResponse(id = req.id, error = RpcError(-32602, "missing tool name"))
        // 未知工具返回标准 JSON-RPC -32601（F5），而非 200 软错误
        if (name !in KNOWN_TOOLS) {
            return RpcResponse(id = req.id, error = RpcError(-32601, "Method not found"))
        }
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val sourceIp = request.local.remoteHost // 真实客户端 IP，供审计与限流
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
    /**
     * server/discover（2026-07-28 新增 MUST）：声明本服务支持的协议版本与能力，
     * 替代旧版的 initialize 握手，供客户端在跳过握手的前提下完成版本/能力协商。
     */
    private fun discoverResult(): JsonElement = buildJsonObject {
        put("resultType", JsonPrimitive("complete"))
        putJsonArray("supportedVersions") { add(JsonPrimitive(SUPPORTED_VERSION)) }
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        // 规范：serverInfo 置于 _meta.io.modelcontextprotocol/serverInfo（客户端不应据此做安全决策）
        putJsonObject("_meta") {
            putJsonObject("io.modelcontextprotocol/serverInfo") {
                put("name", JsonPrimitive("unlock-guard-mcp"))
                put("version", JsonPrimitive(ctx.version))
            }
        }
        put("ttlMs", JsonPrimitive(DISCOVER_TTL_MS))
        put("cacheScope", JsonPrimitive("private"))
    }

    /**
     * 工具清单。每个工具的 description 与参数 description 都写清：用途 / 前置条件 / 副作用 /
     * 单位 / 取值范围 / 默认值，让 AI 客户端在 tools/list 阶段即可正确理解并正确传参。
     * props: 参数名 -> (JSON 类型, 人类可读描述)
     */
    private fun toolsList(): JsonElement = buildJsonObject {
        putJsonArray("tools") {
            add(toolDef("get_phone_state",
                "只读探针：返回手机当前屏幕是否亮、是否锁屏、Shizuku/无障碍两通道是否可用、当前租约信息" +
                    "(lease_id/remaining_sec/holder/channel_used)、是否处于解锁失败锁定、以及服务启动错误。" +
                    "调用任何写操作前应先调用它了解状态。无副作用。",
                emptyMap()))

            add(toolDef("unlock_phone",
                "解锁手机并建立带 TTL 的保活租约：租约有效期内屏幕保持不锁屏，到期或 release_lease 后自动还原设置快照。" +
                    "需 Shizuku 或无障碍通道已授权。已有活跃租约时拒绝(LEASE_CONFLICT)。这是有副作用的敏感操作。",
                mapOf(
                    "ttl_seconds" to ("integer" to "租约时长(秒)。范围 1–1800，默认 300。负值会被拒绝(INVALID_PARAMS)。"),
                )))

            add(toolDef("release_lease",
                "提前释放当前或指定租约，并还原保活期间改动的系统设置(灭屏超时/锁屏宽限)。幂等。不自动锁屏(设备空闲会自行锁屏)。",
                mapOf(
                    "lease_id" to ("string" to "要释放的租约 id，取自 unlock_phone 返回的 lease_id。省略则释放当前活跃租约。"),
                )))

            add(toolDef("lock_phone",
                "立即锁屏。按 无障碍 GLOBAL_ACTION_LOCK_SCREEN(首选，保留指纹) → 设备管理员 lockNow(保底，生物识别失效) → Shizuku 逐级降级。" +
                    "返回实际通道 channel_used 与 biometric_preserved(生物识别是否仍可用，客户端据此决定后续等指纹还是输 PIN)。有副作用。",
                emptyMap()))

            add(toolDef("set_screen_timeout",
                "直接写系统设置：灭屏超时与锁屏宽限时间。需 WRITE_SETTINGS 权限，缺权限返回 PERMISSION_MISSING。" +
                    "注意：不经过租约管理，重启或系统策略可能回退。",
                mapOf(
                    "screen_off_ms" to ("integer" to "灭屏超时(毫秒)。默认 60000。"),
                    "lock_after_ms" to ("integer" to "锁屏宽限(毫秒)：亮屏后多久无操作才锁屏。默认 5000。"),
                )))

            add(toolDef("restore_settings",
                "还原租约保存的系统设置快照(灭屏超时/锁屏宽限)。无活跃快照时返回 restored:false。会释放当前租约。",
                emptyMap()))

            add(toolDef("grant_debug_auth",
                "调试授权引导：返回无线调试(Shizuku)授权是否还需手动确认及提示文案。当前固定返回 adb_auth_required:false；" +
                    "若手机弹窗要求授权，请在设备上点「允许」。",
                emptyMap()))
        }
        // 2026-07-28 MUST：列表响应须带缓存元数据，否则网关缓存失效、判定不合规
        put("ttlMs", JsonPrimitive(TOOLS_LIST_TTL_MS))
        put("cacheScope", JsonPrimitive("private"))
    }

    private fun toolDef(name: String, desc: String, props: Map<String, Pair<String, String>>): JsonObject = buildJsonObject {
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
            putJsonArray("required") {} // 所有参数均有默认值或可选，无强制必填
            put("additionalProperties", JsonPrimitive(false))
            putJsonObject("properties") {
                props.forEach { (k, v) ->
                    val (type, pdesc) = v
                    putJsonObject(k) {
                        put("type", JsonPrimitive(type))
                        put("description", JsonPrimitive(pdesc))
                    }
                }
            }
        })
    }

    private fun healthJson(local: Boolean = false): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("status", JsonPrimitive("ok"))
        // 仅本机回显服务名/版本/LAN 标志，避免对局域网暴露版本信息（F3）
        if (local) {
            put("service", JsonPrimitive("unlock-guard-mcp"))
            put("version", JsonPrimitive(ctx.version))
            put("lan", JsonPrimitive(ctx.isLan))
        }
    })

    private fun isLocal(ip: String): Boolean =
        ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"

    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, resp: RpcResponse) {
        respondText(json.encodeToString(RpcResponse.serializer(), resp), ContentType.Application.Json, status)
    }

    private fun hintObj(hint: String): JsonObject = buildJsonObject { put("hint", JsonPrimitive(hint)) }

    companion object {
        private const val TAG = "McpServer"
        /** 本服务实现的 MCP 协议版本（2026-07-28 无状态 Streamable HTTP 修订） */
        const val SUPPORTED_VERSION: String = "2026-07-28"
        /** tools/list 等列表响应缓存时长（毫秒） */
        private const val TOOLS_LIST_TTL_MS = 60_000L
        /** server/discover 响应缓存时长（毫秒）；服务端身份/能力极少变化，给较长 TTL */
        private const val DISCOVER_TTL_MS = 3_600_000L
        private val KNOWN_TOOLS = setOf(
            "get_phone_state", "unlock_phone", "release_lease", "lock_phone",
            "set_screen_timeout", "restore_settings", "grant_debug_auth",
        )
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
