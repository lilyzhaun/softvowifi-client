package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFormatException
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogList
import com.vowifi.sw.manager.model.OperationalLogTail
import com.vowifi.sw.manager.model.OperationalLogUiState
import java.io.IOException

internal class OperationalLogReader(private val command: LogCommand) {
    suspend fun catalog(): LogCatalogResult = try {
        LogCatalogResult(invoke("catalog", OperationalLogShellCodec::catalog), false)
    } catch (error: LogReadFailure) {
        if (!error.unsupported) throw error
        LogCatalogResult(invoke("list", OperationalLogShellCodec::list), true)
    }

    suspend fun read(id: String, legacy: Boolean, file: OperationalLogFile? = null): OperationalLogTail {
        val tail = if (legacy) invoke("tail $id") { OperationalLogShellCodec.tail(it, file) }
        else invoke("read $id") { OperationalLogShellCodec.read(it, file) }
        if (tail.id != id) throw LogReadFailure(OperationalLogEvaluator.failure("日志身份不匹配"))
        return tail
    }

    private suspend fun <T> invoke(arguments: String, parse: (String) -> T): T {
        val result = try {
            command.run("/data/adb/modules/vowifi_sw/control.sh logs $arguments")
        } catch (error: IOException) {
            throw LogReadFailure(if (permission(error.message.orEmpty())) OperationalLogEvaluator.permissionDenied()
            else OperationalLogEvaluator.failure("无法启动日志读取"))
        }
        if (result.timedOut) throw LogReadFailure(OperationalLogEvaluator.failure("日志读取超时"))
        if (result.overflowed) throw LogReadFailure(OperationalLogEvaluator.failure("日志结果超出边界"))
        val failure = EngineControlScriptCodec.failure(result.output)
        if (result.exitCode != 0 || failure != null) {
            val state = when {
                permission(result.output) || failure?.code == "PERMISSION_DENIED" -> OperationalLogEvaluator.permissionDenied()
                failure?.code in setOf("USAGE", "UNAVAILABLE") -> OperationalLogEvaluator.unavailable()
                else -> OperationalLogEvaluator.failure()
            }
            throw LogReadFailure(state, missing = failure?.code == "NOT_FOUND", unsupported = failure?.code == "USAGE")
        }
        return try { parse(result.output) } catch (_: OperationalLogFormatException) {
            throw LogReadFailure(OperationalLogEvaluator.failure("日志结果无法解析"))
        }
    }

    private fun permission(output: String) = output.contains("Permission denied", true) ||
        output.contains("EACCES", true) || output.contains("error=13", true)
}

internal data class LogCatalogResult(val catalog: OperationalLogList, val legacy: Boolean)

internal class LogReadFailure(
    val state: OperationalLogUiState,
    val missing: Boolean = false,
    val unsupported: Boolean = false,
    val blocking: Boolean = false,
) : Exception() {
    val safeMessage: String get() = when (state) {
        is OperationalLogUiState.Failure -> state.message
        is OperationalLogUiState.PermissionDenied -> state.message
        is OperationalLogUiState.Unavailable -> state.message
        else -> "无法读取脱敏日志"
    }
}
