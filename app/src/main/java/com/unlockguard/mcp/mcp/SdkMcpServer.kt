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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /** 参数类型：json 用于 JSON Schema 的 type 字段，label 用于文档中的中文类型名 */
    private enum class ArgType(val json: String, val label: String) {
        INT("integer", "整数"),
        STRING("string", "字符串"),
        BOOL("boolean", "布尔"),
    }

    /**
     * 参数规格。
     *
     * 关键约定：**所有参数说明文本统一由 [describe] 生成**，工具描述里的参数段与
     * JSON Schema 中每个属性的 `description` 用的是**同一份字符串**。
     * 若两处各写一遍，迟早出现「人看到的说明」和「AI 读到的说明」不一致。
     */
    private data class ArgSpec(
        val name: String,
        val type: ArgType,
        val desc: String,
        val required: Boolean = false,
        /** 默认值（选填参数务必写明，AI 才知道不传时会怎样） */
        val default: String? = null,
        /** 取值范围 / 允许值 */
        val range: String? = null,
        /** 标准示例值：同时进入 JSON Schema 的 examples 与说明文本 */
        val example: JsonElement? = null,
    ) {
        fun describe(): String = buildString {
            // 固定顺序：必填性 → 说明 → （默认值；取值范围；示例）
            append(if (required) "必填" else "选填")
            append("。").append(desc)
            val extra = buildList {
                if (default != null) add("默认值：$default")
                if (range != null) add("取值范围：$range")
                if (example != null) add("示例：$example")
            }
            if (extra.isNotEmpty()) append("（").append(extra.joinToString("；")).append("）")
        }
    }

    /**
     * 工具规格。
     *
     * [describe] 固定按六段输出：**用途 → 适用场景 → 参数 → 调用示例 → 返回 → 注意**。
     * 人读是层次分明的说明文档，AI 读是可稳定解析的固定模板，两边看到的是同一份文本。
     */
    private data class ToolSpec(
        val name: String,
        /** 一句话用途（第一句就要说清「做什么」与「有没有副作用」） */
        val summary: String,
        val scenarios: List<String> = emptyList(),
        /** 成功时 data 的字段说明（AI 据此决定下一步） */
        val returns: String,
        /** 前提、约束、错误码、副作用 */
        val notes: List<String> = emptyList(),
        val args: List<ArgSpec> = emptyList(),
    ) {
        fun describe(): String = buildString {
            append(summary)
            append("\n\n【适用场景】")
            if (scenarios.isEmpty()) append("\n- （通用）") else scenarios.forEach { append("\n- ").append(it) }
            append("\n\n【参数】")
            if (args.isEmpty()) {
                append("\n- 无。调用时 arguments 传空对象 {}")
            } else {
                args.forEach { a ->
                    append("\n- ").append(a.name).append("（").append(a.type.label).append("）：").append(a.describe())
                }
            }
            append("\n\n【调用示例】\narguments = ").append(callExample())
            append("\n\n【返回】\n").append(returns)
            if (notes.isNotEmpty()) {
                append("\n\n【注意】")
                notes.forEach { append("\n- ").append(it) }
            }
        }

        /** 生成可直接照抄的参数示例，统一人与 AI 的传参格式 */
        private fun callExample(): String {
            if (args.isEmpty()) return "{}"
            return args.joinToString(", ", prefix = "{ ", postfix = " }") { a ->
                val v = a.example ?: JsonPrimitive("…")
                "\"${a.name}\": $v"
            }
        }
    }

    private val tools = listOf(
        ToolSpec(
            name = "get_phone_state",
            summary = "查询手机当前状态：屏幕与锁屏情况、Shizuku/无障碍两条解锁通道是否可用、当前活跃租约、是否被安全锁定，以及 MCP 服务启动错误。**只读，无任何副作用。**",
            scenarios = listOf(
                "执行 unlock_phone / lock_phone / 改设置等写操作前，先确认前置条件是否满足",
                "操作失败后定位原因：是哪条通道不可用、是否已被安全锁定、服务端是否报错",
                "周期性巡检设备是否在线、MCP 服务是否正常",
            ),
            returns = "data 含：screen_on(是否亮屏)、locked(是否锁屏)、channels.shizuku 与 channels.accessibility(两通道是否可用)、" +
                "lease{lease_id, remaining_sec, holder, channel_used}(无活跃租约时为 null)、locked_out(是否已被安全锁定)、" +
                "server_start_error(服务启动错误，空字符串表示服务正常)。",
            notes = listOf(
                "完全只读、可放心高频调用，不会解锁、不会改变任何设置",
                "这是排查一切问题的第一步：结果不符合预期时先调它",
            ),
        ),
        ToolSpec(
            name = "unlock_phone",
            summary = "解锁手机并建立带 TTL 的保活租约：租约有效期内屏幕保持不锁屏；到期或调用 release_lease 后自动还原被临时改动的系统设置。**有副作用。**",
            scenarios = listOf(
                "AI 需要操作手机、连续执行多步任务时，先解锁并保活",
                "需要屏幕在一段时间内保持不自动锁屏（如连续读取屏幕内容）",
            ),
            args = listOf(
                ArgSpec(
                    name = "ttl_seconds",
                    type = ArgType.INT,
                    desc = "租约时长（秒）。超过上限会被自动收敛到上限值。",
                    required = false,
                    default = "300（5 分钟）",
                    range = "1–1800（30 分钟）；小于 1 会被拒绝并返回 INVALID_PARAMS",
                    example = JsonPrimitive(300),
                ),
            ),
            returns = "成功时 data 含：lease_id(租约 id，后续 release_lease 用)、ttl_seconds(实际生效的秒数)、" +
                "expire_at(到期时间戳，毫秒)、channel_used(SHIZUKU 或 ACCESSIBILITY，表示本次实际走通的通道)。",
            notes = listOf(
                "前提：Shizuku 或无障碍至少一路可用，否则返回 SHIZUKU_UNAVAILABLE 等失败码",
                "同一时刻只允许一个活跃租约：已有租约时返回 LEASE_CONFLICT，且 data 会附上现有租约信息，请先 release_lease 或等其到期",
                "有副作用：会真实解锁手机，并临时修改灭屏/锁屏相关系统设置（租约到期后自动还原）",
            ),
        ),
        ToolSpec(
            name = "release_lease",
            summary = "提前释放租约，并还原保活期间被改动的系统设置（灭屏超时、锁屏宽限）。幂等，重复调用无副作用。",
            scenarios = listOf(
                "任务提前结束、不再需要屏幕保活时主动归还",
                "unlock_phone 返回 LEASE_CONFLICT 时，先释放旧租约再重新申请",
            ),
            args = listOf(
                ArgSpec(
                    name = "lease_id",
                    type = ArgType.STRING,
                    desc = "要释放的租约 id，取自 unlock_phone 返回的 lease_id。省略则释放当前活跃租约。",
                    required = false,
                    default = "释放当前活跃租约",
                    range = "unlock_phone 返回的 lease_id；id 不存在时返回 LEASE_CONFLICT",
                    example = JsonPrimitive("lease-a1b2c3"),
                ),
            ),
            returns = "成功时 data 含 released=true。",
            notes = listOf(
                "只还原系统设置、**不主动锁屏**：设备空闲到超时会自行锁屏",
                "幂等：重复释放同一租约不会报错",
            ),
        ),
        ToolSpec(
            name = "lock_phone",
            summary = "立即锁屏，并如实回报实际生效的通道，以及**锁屏后生物识别（指纹/人脸）是否仍可用**。**有副作用。**",
            scenarios = listOf(
                "任务结束、需要把手机恢复到锁定状态",
                "验证锁屏链路是否可用",
            ),
            returns = "成功时 data 含：locked=true、channel_used(SHIZUKU / ACCESSIBILITY / DEVICE_ADMIN / NONE)、" +
                "already_locked(调用前是否已处于锁定态)、biometric_preserved(生物识别是否仍可用)。",
            notes = listOf(
                "通道顺序固定：Shizuku → 无障碍 → 设备管理员（与解锁链路一致）",
                "**关键**：biometric_preserved=false 表示本次走了设备管理员兜底，生物识别已失效，之后只能输入 PIN 才能解锁",
                "调用前屏幕若已锁定，则不执行任何动作，返回 already_locked=true",
            ),
        ),
        ToolSpec(
            name = "set_screen_timeout",
            summary = "直接写系统设置：调整灭屏超时与锁屏宽限时间。这是**持久化**修改，不经过租约管理，不会自动还原。",
            scenarios = listOf(
                "需要长期改变设备的灭屏习惯（区别于 unlock_phone 的临时保活）",
            ),
            args = listOf(
                ArgSpec(
                    name = "screen_off_ms",
                    type = ArgType.INT,
                    desc = "灭屏超时（毫秒）：无操作多久后熄屏。",
                    required = false,
                    default = "60000（1 分钟）",
                    range = "建议 ≥ 10000",
                    example = JsonPrimitive(60000),
                ),
                ArgSpec(
                    name = "lock_after_ms",
                    type = ArgType.INT,
                    desc = "锁屏宽限（毫秒）：亮屏后多久无操作才真正锁屏。",
                    required = false,
                    default = "5000（5 秒）",
                    range = "建议 ≥ 0",
                    example = JsonPrimitive(5000),
                ),
            ),
            returns = "成功时 data 含 applied=true。",
            notes = listOf(
                "需要系统「修改系统设置」(WRITE_SETTINGS) 权限，缺失时返回 PERMISSION_MISSING",
                "与 unlock_phone 的临时保活不同：此修改**不会自动还原**，需自行改回或调用 restore_settings",
            ),
        ),
        ToolSpec(
            name = "restore_settings",
            summary = "立即还原租约保存的系统设置快照（灭屏超时、锁屏宽限），并释放当前租约。",
            scenarios = listOf(
                "想提前结束保活，把系统设置恢复到解锁前的状态",
            ),
            returns = "成功时 data 含 restored(是否成功还原)。",
            notes = listOf(
                "没有活跃快照时 restored=false，这是正常情况、不是错误",
                "会一并释放当前租约",
            ),
        ),
        ToolSpec(
            name = "grant_debug_auth",
            summary = "查询无线调试(Shizuku)授权是否还需要人工确认，并返回给用户的操作提示。",
            scenarios = listOf(
                "Shizuku 显示未授权、需要判断下一步该怎么办时",
            ),
            returns = "成功时 data 含 adb_auth_required(是否需要人工确认) 与 hint(给用户的操作提示文案)。",
            notes = listOf(
                "本工具只做查询与引导，不会改变任何授权状态",
            ),
        ),
        ToolSpec(
            name = "set_screen_brightness",
            summary = "调节屏幕亮度（经 Shizuku 写系统设置）。**有副作用。**",
            scenarios = listOf(
                "环境光变化或省电需要时，由 AI 调整屏幕亮度",
            ),
            args = listOf(
                ArgSpec(
                    name = "level",
                    type = ArgType.INT,
                    desc = "亮度值，Android 原生量纲。",
                    required = true,
                    range = "0–255（0 最暗、255 最亮）",
                    example = JsonPrimitive(120),
                ),
                ArgSpec(
                    name = "auto",
                    type = ArgType.BOOL,
                    desc = "是否交还系统自动调节：true=自动亮度；false=按 level 固定。",
                    required = false,
                    default = "false（手动，按 level 固定）",
                    range = "true / false",
                    example = JsonPrimitive(false),
                ),
            ),
            returns = "成功时 data 含 level(实际写入的亮度值) 与 auto(当前是否为自动亮度)。",
            notes = listOf(
                "**必须 Shizuku 授权**，未授权时返回 SHIZUKU_UNAVAILABLE",
                "自动亮度开启时系统会持续改写亮度值、覆盖手动设置；因此 auto=false 时会同时关闭自动亮度，否则设置会被覆盖",
            ),
        ),
        ToolSpec(
            name = "run_adb_command",
            summary = "以 adb shell 权限执行一条 shell 命令并返回退出码与输出（经 Shizuku 在特权进程执行）。**有副作用，属高危能力。**",
            scenarios = listOf(
                "读取系统信息：dumpsys、getprop、pm list 等",
                "修改系统设置或执行自动化：settings、input、am、wm、svc 等",
            ),
            args = listOf(
                ArgSpec(
                    name = "command",
                    type = ArgType.STRING,
                    desc = "要执行的 shell 命令。**不要**带 adb shell 前缀，也不要带 adb -s 之类参数。",
                    required = true,
                    range = "单条命令；可用 && / ; / | 串联",
                    example = JsonPrimitive("settings put system screen_brightness 120"),
                ),
                ArgSpec(
                    name = "timeout_ms",
                    type = ArgType.INT,
                    desc = "超时时间（毫秒）。超时会强制终止子进程。",
                    required = false,
                    default = "15000（15 秒）",
                    range = "1000–60000",
                    example = JsonPrimitive(15000),
                ),
            ),
            returns = "ok=true 表示「命令已执行」，data 含：exit_code(命令退出码)、stdout(标准输出)、stderr(标准错误)、" +
                "timed_out(是否超时)、succeeded(退出码是否为 0)。**命令自身的成败看 succeeded 与 exit_code**。",
            notes = listOf(
                "**必须 Shizuku 授权**，未授权时返回 SHIZUKU_UNAVAILABLE",
                "能力等价于手机连电脑后的 adb shell：settings / pm / am / input / wm / svc / dumpsys / cmd / appops / getprop 等；**不含 root 能力**（除非 Shizuku 本身以 root 启动）",
                "安全护栏会拒绝不可逆操作：恢复出厂、关机、删除分区根目录、写磁盘分区、提权 su/sudo、关闭 ADB 调试、卸载本应用；被拒时返回 COMMAND_BLOCKED。**重启允许执行**",
                "删除分区内的具体文件（如 rm /sdcard/Download/a.apk）等正常用途不受限制",
                "单条输出流上限约 8000 字符，超长会被截断",
            ),
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
                description = spec.describe(),
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        spec.args.forEach { a ->
                            putJsonObject(a.name) {
                                put("type", JsonPrimitive(a.type.json))
                                // 与工具描述中的参数说明同源（同一个 describe()），
                                // 保证人类读到的和 AI 从 schema 解析到的是同一份文本
                                put("description", JsonPrimitive(a.describe()))
                                a.example?.let { ex -> putJsonArray("examples") { add(ex) } }
                            }
                        }
                    },
                    // 此前遗漏：不传 required 会让所有参数在 schema 里都是「可选」，AI 无法判断必填项
                    required = spec.args.filter { it.required }.map { it.name },
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
