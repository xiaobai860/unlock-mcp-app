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
import java.time.LocalDateTime
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

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "audit.log")
    private val mutex = Mutex()
    private val keep = 200

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> = _recent.asStateFlow()

    /** 日志默认保存天数（持久化，超期记录自动清理） */
    private val _retentionDays =
        MutableStateFlow(prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS))
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

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
            runCatching { file.appendText(Json.encodeToString(Entry.serializer(), entry) + "\n") }
            pruneLocked()
        }
    }

    /** 重新载入最近 N 条（App 启动时调用），顺带清理超期记录 */
    suspend fun reload() {
        mutex.withLock { pruneLocked() }
    }

    /** 修改默认保存天数（持久化）并立即清理超期记录 */
    suspend fun setRetentionDays(days: Int) {
        mutex.withLock {
            val d = days.coerceIn(1, 365)
            prefs.edit().putInt(KEY_RETENTION_DAYS, d).apply()
            _retentionDays.value = d
            pruneLocked()
        }
    }

    /** 清空全部日志（文件与内存），不可恢复 */
    suspend fun clear() {
        mutex.withLock {
            runCatching { file.writeText("") }
            _recent.value = emptyList()
        }
    }

    /**
     * 按 [retentionDays] 清理超期记录并刷新内存列表；必须在 mutex 内调用。
     * 时间解析失败的行一律保留，避免格式差异导致误删。
     */
    private fun pruneLocked() {
        if (!file.exists()) {
            _recent.value = emptyList()
            return
        }
        val cutoff = LocalDateTime.now().minusDays(_retentionDays.value.toLong())
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val kept = lines.filter { line ->
            val t = runCatching {
                LocalDateTime.parse(Json.decodeFromString<Entry>(line).time, FMT)
            }.getOrNull()
            t == null || !t.isBefore(cutoff)
        }
        if (kept.size != lines.size) {
            val text = if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
            runCatching { file.writeText(text) }
        }
        _recent.value = kept.mapNotNull {
            runCatching { Json.decodeFromString<Entry>(it) }.getOrNull()
        }.takeLast(keep)
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
        private const val PREFS = "guard_settings"
        private const val KEY_RETENTION_DAYS = "audit_retention_days"
        const val DEFAULT_RETENTION_DAYS = 7
    }
}
