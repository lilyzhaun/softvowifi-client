package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsManager
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.StockWfcCard
import com.vowifi.sw.manager.model.StockWfcDiagnostics
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible

class AndroidEnvironmentDiagnosticsSource internal constructor(
    private val start: (String) -> Process,
    parentContext: CoroutineContext,
    private val readStockWfc: () -> StockWfcDiagnostics,
) : EnvironmentDiagnosticsSource {
    constructor(context: Context) : this(
        { command -> com.vowifi.sw.root.RootBridge.startCommand(command) },
        Dispatchers.IO,
        { readStockWfc(context.applicationContext) },
    )

    private val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
    private val lock = Any()
    private var inFlight: CompletableDeferred<RefreshResult>? = null
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
        requestRefresh()
    }

    override suspend fun refreshAndAwait(): RefreshResult =
        requestRefresh()?.await() ?: RefreshResult.Unsupported

    private fun requestRefresh(): CompletableDeferred<RefreshResult>? = synchronized(lock) {
        if (closed || !scope.isActive) return@synchronized null
        inFlight?.let { return@synchronized it }
        val completion = CompletableDeferred<RefreshResult>(scope.coroutineContext[Job])
        inFlight = completion
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            val snapshot = runInterruptible { readDiagnostics() }
            synchronized(lock) {
                if (closed || !scope.isActive) return@synchronized
                mutableState.value = snapshot
                if (inFlight === completion) inFlight = null
                completion.complete(when (snapshot) {
                    is EnvironmentDiagnosticsUiState.Ready -> RefreshResult.Completed
                    is EnvironmentDiagnosticsUiState.PermissionDenied -> RefreshResult.Failed(snapshot.message)
                    is EnvironmentDiagnosticsUiState.Failure -> RefreshResult.Failed(snapshot.message)
                    is EnvironmentDiagnosticsUiState.Unavailable -> RefreshResult.Unsupported
                    EnvironmentDiagnosticsUiState.Loading -> error("diagnose returned Loading")
                })
            }
        }
        worker.invokeOnCompletion { error ->
            synchronized(lock) {
                if (inFlight === completion) inFlight = null
                if (error != null) completion.completeExceptionally(error)
            }
        }
        worker.start()
        completion
    }

    private fun readDiagnostics(): EnvironmentDiagnosticsUiState = try {
        val process = start(DIAGNOSE_COMMAND)
        try {
            process.outputStream.close()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                EnvironmentDiagnosticsEvaluator.failure("环境诊断超时")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (process.exitValue() == 0) {
                    EnvironmentDiagnosticsEvaluator.fromOutput(output, readStockWfc())
                } else {
                    mapCommandFailure(output)
                }
            }
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
        }
    } catch (error: IOException) {
        if (error.message.isPermissionDenied()) {
            EnvironmentDiagnosticsEvaluator.permissionDenied()
        } else {
            EnvironmentDiagnosticsEvaluator.failure("无法启动环境诊断")
        }
    } catch (_: SecurityException) {
        EnvironmentDiagnosticsEvaluator.permissionDenied()
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

    override fun close(): Unit = synchronized(lock) {
        closed = true
        inFlight?.cancel()
        inFlight = null
        scope.cancel()
    }

    private companion object {
        const val DIAGNOSE_COMMAND = "/data/adb/softvowifi/runtime/control.sh diagnose"
        const val TIMEOUT_SECONDS = 20L
    }
}

/** Per-subscription settings read by the authorized Root backend. */
private fun readStockWfc(context: Context): StockWfcDiagnostics = try {
    val snapshot = com.vowifi.sw.root.RootBridge.nativeStatus()
    StockWfcDiagnostics.Readable((0 until snapshot.length()).map { index ->
        val card = snapshot.getJSONObject(index)
        StockWfcCard(slot = card.getInt("slot"), enabled = card.getBoolean("enabled"))
    })
} catch (_: org.json.JSONException) {
    StockWfcDiagnostics.Unreadable
} catch (_: RuntimeException) {
    // SecurityException, IllegalArgumentException for a vanished subscription, or framework failure.
    StockWfcDiagnostics.Unreadable
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
