package com.unlockguard.mcp.mcp

import android.util.Log
import com.unlockguard.mcp.domain.RateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 基于 **官方 MCP Kotlin SDK（io.modelcontextprotocol:kotlin-sdk-server 0.15.0）** 的服务端实现。
 *
 * 背景：此前自研协议层实现了 MCP **2026-07-28**（无生命周期：`initialize` 已移除、每请求带
 * `MCP-Protocol-Version`）。该修订过于超前——主流客户端（Cursor / Claude Desktop / Cline 等）
 * 仍在发 `initialize` + `2025-06-18`/`2025-11-25` 版本头，于是必然连不上。
 * 官方 Kotlin SDK 目前实现的是 2025-era 协议线（含 initialize 握手与**版本协商**），
 * 交由 SDK 处理 JSON-RPC / 生命周期 / 能力协商 / 错误码 / SSE 流，我们只保留业务与安全策略。
 *
 * 保留的自有策略（SDK 不负责传输层安全，全部挂在 Ktor 层）：
 *  - Bearer Token 鉴权；
 *  - 按客户端 IP 限流（未鉴权也限，抗 token 爆破）+ 按 token 限流；
 *  - Origin 校验（DNS 重绑定防护）；
 *  - 审计日志（在 [Tools.dispatch] 内，按真实客户端 IP 记录）。
 *
 * 端点：
 *  - POST /mcp：Streamable HTTP（无状态，每次请求独立会话；GET /mcp 由 SDK 返 405）
 *  - GET  /health：免 Token 低敏健康检查（区分「服务挂了」与「Token 错了」）
 */
class SdkMcpServer(private val ctx: McpContext) {

    // 复用生产 JSON 配置，切勿另建实例（否则与生产序列化行为漂移）
    private val json = mcpJson

    // Ktor 3 起 EmbeddedServer 不再实现 ApplicationEngine，故按具体泛型类型持有
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * DNS 重绑定防护：允许的来源主机集合（对**携带 Origin** 的请求生效）。
     * - 非浏览器客户端（各语言 SDK / curl）通常不发 Origin → 放行（null/blank）；
     * - 浏览器来源仅放行 localhost / 回环。
     * 注：SDK 自带的 enableDnsRebindingProtection 只放行 localhost，会拒绝局域网 Host，
     * 因此这里关闭它、改由本函数按 Origin 校验（LAN 场景需放行同 Wi-Fi 的来源）。
     */
    private val allowedOriginHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** 工具清单：name / description / 参数表（参数名 -> (JSON 类型, 人类可读描述)） */
    private data class ToolSpec(
        val name: String,
        val description: String,
        val props: Map<String, Pair<String, String>> = emptyMap(),
    )

    private val tools = listOf(
        ToolSpec(
            "get_phone_state",
            "只读探针：返回手机当前屏幕是否亮、是否锁屏、Shizuku/无障碍两通道是否可用、当前租约信息" +
                "(lease_id/remaining_sec/holder/channel_used)、是否处于解锁失败锁定、以及服务启动错误。" +
                "调用任何写操作前应先调用它了解状态。无副作用。",
        ),
        ToolSpec(
            "unlock_phone",
            "解锁手机并建立带 TTL 的保活租约：租约有效期内屏幕保持不锁屏，到期或 release_lease 后自动还原设置快照。" +
                "需 Shizuku 或无障碍通道已授权。已有活跃租约时拒绝(LEASE_CONFLICT)。这是有副作用的敏感操作。",
            mapOf("ttl_seconds" to ("integer" to "租约时长(秒)。范围 1–1800，默认 300。负值会被拒绝(INVALID_PARAMS)。")),
        ),
        ToolSpec(
            "release_lease",
            "提前释放当前或指定租约，并还原保活期间改动的系统设置(灭屏超时/锁屏宽限)。幂等。不自动锁屏(设备空闲会自行锁屏)。",
            mapOf("lease_id" to ("string" to "要释放的租约 id，取自 unlock_phone 返回的 lease_id。省略则释放当前活跃租约。")),
        ),
        ToolSpec(
            "lock_phone",
            "立即锁屏。按 无障碍 GLOBAL_ACTION_LOCK_SCREEN(首选，保留指纹) → 设备管理员 lockNow(保底，生物识别失效) → Shizuku 逐级降级。" +
                "返回实际通道 channel_used 与 biometric_preserved(生物识别是否仍可用，客户端据此决定后续等指纹还是输 PIN)。有副作用。",
        ),
        ToolSpec(
            "set_screen_timeout",
            "直接写系统设置：灭屏超时与锁屏宽限时间。需 WRITE_SETTINGS 权限，缺权限返回 PERMISSION_MISSING。" +
                "注意：不经过租约管理，重启或系统策略可能回退。",
            mapOf(
                "screen_off_ms" to ("integer" to "灭屏超时(毫秒)。默认 60000。"),
                "lock_after_ms" to ("integer" to "锁屏宽限(毫秒)：亮屏后多久无操作才锁屏。默认 5000。"),
            ),
        ),
        ToolSpec(
            "restore_settings",
            "还原租约保存的系统设置快照(灭屏超时/锁屏宽限)。无活跃快照时返回 restored:false。会释放当前租约。",
        ),
        ToolSpec(
            "grant_debug_auth",
            "调试授权引导：返回无线调试(Shizuku)授权是否还需手动确认及提示文案。当前固定返回 adb_auth_required:false；" +
                "若手机弹窗要求授权，请在设备上点「允许」。",
        ),
    )

    fun start() {
        runCatching {
            engine = embeddedServer(CIO, host = ctx.bindHost, port = ctx.port) {
                // 鉴权 / 限流 / Origin：必须在 MCP 路由之前拦截
                installGuard()

                // 无状态 Streamable HTTP：每个请求独立会话，GET /mcp 由 SDK 返 405（规范允许）。
                // lambda 的 receiver 是 RoutingContext，故能拿到真实客户端 IP 供审计使用。
                mcpStatelessStreamableHttp(
                    path = "/mcp",
                    enableDnsRebindingProtection = false, // 见 allowedOriginHosts 注释：改由我们按 Origin 校验
                ) {
                    buildServer(call.request.local.remoteHost)
                }

                routing {
                    get("/health") {
                        call.respondText(
                            healthJson(isLocal(call.request.local.remoteHost)),
                            ContentType.Application.Json,
                        )
                    }
                }
            }.start(wait = false)
        }.onFailure { e ->
            // 端口被占用 / 绑定失败不得冒泡，否则前台服务启动抛异常会崩进程
            ctx.lastStartError = "端口 ${ctx.port} 绑定失败：${e.message}"
            Log.e(TAG, "MCP server 启动失败", e)
            return
        }
        ctx.lastStartError = null
        Log.i(TAG, "MCP server started on ${ctx.bindHost}:${ctx.port} (官方 SDK 0.15.0 / 2025-era 协议)")
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        Log.i(TAG, "MCP server stopped")
    }

    /** 每次请求构建一个 Server（无状态模式），并注册全部工具 */
    private fun buildServer(sourceIp: String): Server {
        val server = Server(
            serverInfo = Implementation(name = "unlock-guard-mcp", version = ctx.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )
        tools.forEach { spec ->
            server.addTool(
                name = spec.name,
                description = spec.description,
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        spec.props.forEach { (k, v) ->
                            putJsonObject(k) {
                                put("type", JsonPrimitive(v.first))
                                put("description", JsonPrimitive(v.second))
                            }
                        }
                    },
                ),
            ) { request -> dispatch(spec.name, request, sourceIp) }
        }
        return server
    }

    /**
     * 统一工具执行入口：参数交给 [Tools.dispatch]（内含参数白名单与审计），
     * 结果按 MCP 规范封装为 TextContent（结构化结果先序列化为字符串）。
     */
    private suspend fun dispatch(name: String, request: CallToolRequest, sourceIp: String): CallToolResult {
        val args: JsonObject = request.arguments ?: JsonObject(emptyMap())
        // 参数类型错误 / 业务异常若冒泡会导致 500 裸文本，在此统一兜成结构化错误
        val env = runCatching { Tools.dispatch(ctx, name, args, sourceIp) }.getOrElse { e ->
            Log.w(TAG, "tools/call 执行异常: $name", e)
            ToolEnvelope(
                false, null,
                McpError(
                    ErrorCodes.INVALID_PARAMS,
                    "调用失败：${e.message}",
                    "请检查参数类型（整数类字段勿传字符串）",
                ),
            )
        }
        return CallToolResult(
            content = listOf(TextContent(text = json.encodeToString(env))),
            isError = !env.ok,
        )
    }

    /**
     * 在路由之前拦截：限流 → Origin 校验 → Bearer 鉴权；任一不过即 `finish()` 终止管线
     * （与 Ktor 官方 Authentication 插件同一套路，避免 respond 后路由继续执行导致重复响应）。
     */
    private fun Application.installGuard() {
        intercept(ApplicationCallPipeline.Call) {
            val ip = call.request.local.remoteHost
            // 抗爆破：未鉴权也按客户端 IP 限流（key 为 IP 而非 token，否则逐个猜测 token 可绕过 429）
            if (!ctx.ipLimiters.getOrPut(ip) { RateLimiter() }.tryRequest()) {
                call.respondJson(HttpStatusCode.TooManyRequests, "rate_limited")
                finish()
            }
            if (!allowedOrigin(call.request.headers["Origin"])) {
                call.respondJson(HttpStatusCode.Forbidden, "origin_not_allowed")
                finish()
            }
            if (call.request.path() != "/health") {
                if (!validAuth(call.request.headers["Authorization"])) {
                    call.respondJson(HttpStatusCode.Unauthorized, "unauthorized")
                    finish()
                }
                val rl = ctx.rateLimiters.getOrPut(ctx.token) { RateLimiter() }
                if (!rl.tryRequest()) {
                    call.respondJson(HttpStatusCode.TooManyRequests, "rate_limited")
                    finish()
                }
            }
        }
    }

    private fun validAuth(header: String?): Boolean {
        if (header == null) return false
        val parts = header.split(" ", limit = 2)
        return parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true) && parts[1] == ctx.token
    }

    private fun allowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true // 非浏览器客户端（SDK/curl）通常不发送 Origin
        val host = origin.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return host in allowedOriginHosts
    }

    private fun healthJson(local: Boolean): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
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

    companion object {
        private const val TAG = "SdkMcpServer"
    }
}

/** 轻量 JSON 错误响应（供鉴权/限流拦截使用） */
private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    status: HttpStatusCode,
    error: String,
) {
    respondText("{\"error\":\"$error\"}", ContentType.Application.Json, status)
}
