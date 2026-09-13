package com.unlockguard.mcp.domain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 审计日志：所有 MCP 调用本地留痕（时间 / 来源 IP / 工具 / 入参摘要 / 结果 / 错误码）。
 * 内存保留最近 N 条供 UI，全量追加到应用私有文件，支持导出 CSV。
 */
class AuditLog(private val context: Context) {

    @Serializable
    data class Entry(
        val time: String,
        val sourceIp: String,
        val tool: String,
        val args: String,
        val ok: Boolean,
        val errorCode: String?,
    )

    private val file = File(context.filesDir, "audit.log")
    private val mutex = Mutex()
    private val keep = 200

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> = _recent.asStateFlow()

    suspend fun record(sourceIp: String, tool: String, args: String, ok: Boolean, errorCode: String?) {
        val entry = Entry(
            time = Instant.now().atZone(ZoneId.systemDefault()).format(FMT),
            sourceIp = sourceIp,
            tool = tool,
            args = args,
            ok = ok,
            errorCode = errorCode,
        )
        mutex.withLock {
            file.appendText(Json.encodeToString(Entry.serializer(), entry) + "\n")
            val list = (_recent.value + entry).takeLast(keep)
            _recent.value = list
        }
    }

    /** 重新载入最近 N 条（App 启动时调用） */
    suspend fun reload() {
        mutex.withLock {
            if (!file.exists()) return@withLock
            val lines = file.readLines().takeLast(keep)
            _recent.value = lines.mapNotNull {
                runCatching { Json.decodeFromString<Entry>(it) }.getOrNull()
            }
        }
    }

    fun exportCsv(): File {
        val out = File(context.filesDir, "audit_export.csv")
        val sb = StringBuilder("time,source_ip,tool,args,ok,error_code\n")
        _recent.value.forEach {
            sb.append("${esc(it.time)},${esc(it.sourceIp)},${esc(it.tool)},${esc(it.args)},${it.ok},${esc(it.errorCode ?: "")}\n")
        }
        out.writeText(sb.toString())
        return out
    }

    private fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
