package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidEnvironmentDiagnosticsSource : EnvironmentDiagnosticsSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationGate = EngineOperationGate()
    private val mutableState = MutableStateFlow<EnvironmentDiagnosticsUiState>(
        EnvironmentDiagnosticsUiState.Loading,
    )
    override val state: StateFlow<EnvironmentDiagnosticsUiState> = mutableState.asStateFlow()
    @Volatile
    private var closed = false

    init {
        refresh()
    }

    override fun refresh() {
        if (closed || !operationGate.tryEnter()) return
        scope.launch {
            mutableState.value = readDiagnostics()
        }.invokeOnCompletion {
            operationGate.release()
        }
    }

    private fun readDiagnostics(): EnvironmentDiagnosticsUiState = try {
        val process = ProcessBuilder("su", "-c", DIAGNOSE_COMMAND)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            EnvironmentDiagnosticsEvaluator.failure("环境诊断超时")
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() == 0) {
                EnvironmentDiagnosticsEvaluator.fromOutput(output)
            } else {
                mapCommandFailure(output)
            }
        }
    } catch (error: IOException) {
        if (error.message.isPermissionDenied()) {
            EnvironmentDiagnosticsEvaluator.permissionDenied()
        } else {
            EnvironmentDiagnosticsEvaluator.failure("无法启动环境诊断")
        }
    } catch (_: Exception) {
        EnvironmentDiagnosticsEvaluator.failure()
    }

    private fun mapCommandFailure(output: String): EnvironmentDiagnosticsUiState {
        val failure = EnvironmentDiagnosticsCodec.failure(output)
        return when {
            output.isPermissionDenied() -> EnvironmentDiagnosticsEvaluator.permissionDenied()
            failure?.code == "USAGE" || output.isUnavailable() ->
                EnvironmentDiagnosticsEvaluator.unavailable()
            else -> EnvironmentDiagnosticsEvaluator.failure()
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
    }

    private companion object {
        const val DIAGNOSE_COMMAND = "/data/adb/modules/vowifi_sw/control.sh diagnose"
        const val TIMEOUT_SECONDS = 20L
    }
}

private fun String?.isPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

private fun String.isUnavailable(): Boolean =
    contains("not found", ignoreCase = true) ||
        contains("No such file", ignoreCase = true) ||
        contains("unknown diagnose", ignoreCase = true) ||
        contains("unknown command", ignoreCase = true) ||
        contains("USAGE", ignoreCase = true)
