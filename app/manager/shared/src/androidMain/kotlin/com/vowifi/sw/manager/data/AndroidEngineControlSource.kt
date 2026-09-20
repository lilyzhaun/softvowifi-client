package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidEngineControlSource private constructor(
    private val pool: AndroidEngineClientPool,
    private val ownsPool: Boolean,
    private val controlCommand: (suspend (String, String?) -> String)? = null,
) : EngineControlSource {
    constructor() : this(AndroidEngineClientPool(), ownsPool = true)
    constructor(pool: AndroidEngineClientPool) : this(pool, ownsPool = false)
    constructor(pool: AndroidEngineClientPool, controlCommand: suspend (String, String?) -> String) :
        this(pool, ownsPool = false, controlCommand = controlCommand)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationGate = EngineOperationGate()
    @Volatile
    private var closed = false
    private val mutableState = MutableStateFlow<EngineControlUiState>(EngineControlUiState.Loading)
    override val state: StateFlow<EngineControlUiState> = mutableState.asStateFlow()

    init {
        refresh()
        scope.launchEngineAutoRefresh(state, refresh = ::refresh)
    }

    override fun refresh() {
        launchOperation { refreshLocked() }
    }

    override fun start(iccid: String) {
        launchOperation {
            updateInstance(iccid) { it.copy(lifecycle = EngineLifecycle.STARTING, message = null) }
            runCatching { runControl("start", iccid) }
                .onSuccess { refreshLocked() }
                .onFailure { error -> updateFailure(iccid, error) }
        }
    }

    override fun stop(iccid: String) {
        launchOperation {
            updateInstance(iccid) { it.copy(lifecycle = EngineLifecycle.STOPPING, message = null) }
            runCatching {
                val client = clientFor(iccid)
                val response = client.request("stop")
                response.error?.let { error -> throw EngineControlException(error.message) }
                removeClient(iccid)?.close()
                runControl("stop", iccid)
            }.onSuccess {
                refreshLocked()
            }.onFailure { error ->
                runCatching {
                    runControl("stop", iccid)
                }
                removeClient(iccid)?.close()
                refreshLocked()
                updateInstance(iccid) { it.copy(message = error.message ?: "引擎停止失败") }
            }
        }
    }

    private fun launchOperation(operation: suspend () -> Unit) {
        if (isClosed() || !operationGate.tryEnter()) return
        scope.launch { operation() }
            .invokeOnCompletion { operationGate.release() }
    }

    private suspend fun refreshLocked() {
        mutableState.value = try {
            val listed = EngineControlScriptCodec.instances(runControl("list"))
            val running = listed.map { instance ->
                if (instance.lifecycle == EngineLifecycle.RUNNING) status(instance) else instance
            }
            EngineControlUiState.Ready(running)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RootCommandException) {
            if (error.failure != null) {
                EngineControlUiState.Failure(error.failure.message)
            } else if (error.message.orEmpty().contains("not found", ignoreCase = true)) {
                EngineControlUiState.Failure(error.message ?: "控制脚本不存在")
            } else {
                EngineControlUiState.PermissionDenied(error.message ?: "KernelSU 未授权")
            }
        } catch (error: IOException) {
            if (error.message.orEmpty().contains("error=13")) {
                EngineControlUiState.PermissionDenied("请在 KernelSU 中授予 SoftVoWiFi root 权限")
            } else {
                EngineControlUiState.Failure(error.message ?: "无法启动 su")
            }
        } catch (error: Exception) {
            EngineControlUiState.Failure(error.message ?: "无法读取引擎状态")
        }
    }

    private suspend fun status(instance: EngineInstanceState): EngineInstanceState = try {
        val client = clientFor(instance.iccid)
        val response = client.request("getStatus")
        response.error?.let { error -> throw EngineControlException(error.message) }
        val runtime = EngineProtocolCodec.status(response)
        val capabilities = EngineClientCapabilitiesSource { method, params ->
            client.request(method, params, EngineClientCapabilitiesSource.TIMEOUT_MILLIS)
        }.query()
        instance.copy(runtime = runtime, message = null, capabilities = when (capabilities) {
            is ClientCapabilitiesResult.Ready -> capabilities.capabilities
            is ClientCapabilitiesResult.Unavailable -> null
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        removeClient(instance.iccid)?.close()
        val message = if (error is IOException && error.message.isSocketPermissionDenied()) {
            "控制 socket 权限被拒绝，请检查模块 SELinux 与 socket 权限"
        } else {
            error.message ?: "控制 socket 不可用"
        }
        instance.copy(message = message, capabilities = null)
    }

    private fun clientFor(iccid: String): LocalSocketEngineClient {
        check(!closed) { "engine control source is closed" }
        return pool.clientFor(iccid)
    }

    private fun removeClient(iccid: String): LocalSocketEngineClient? = pool.remove(iccid)

    private fun isClosed(): Boolean = closed

    private fun updateFailure(iccid: String, error: Throwable) {
        val failure = (error as? RootCommandException)?.failure
        val fallback = failure?.message ?: error.message ?: "引擎操作失败"
        updateInstance(iccid) {
            it.copy(
                lifecycle = lifecycleAfterControlFailure(it.lifecycle, failure?.code),
                message = controlFailureMessage(failure?.code, fallback),
            )
        }
    }

    private fun updateInstance(iccid: String, transform: (EngineInstanceState) -> EngineInstanceState) {
        val current = mutableState.value
        if (current !is EngineControlUiState.Ready) return
        mutableState.value = current.copy(
            instances = current.instances.map { instance ->
                if (instance.iccid == iccid) transform(instance) else instance
            },
        )
    }

    private suspend fun runControl(command: String, iccid: String? = null): String = withContext(Dispatchers.IO) {
        controlCommand?.let { return@withContext it(command, iccid) }
        val invocation = buildString {
            append(CONTROL_SCRIPT)
            append(' ')
            append(command)
            if (iccid != null) {
                require(iccid.all(Char::isDigit)) { "invalid ICCID" }
                append(' ')
                append(iccid)
            }
        }
        val process = ProcessBuilder("su", "-c", invocation)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RootCommandException("su command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw RootCommandException(
                output.ifBlank { "su exited $exitCode" },
                EngineControlScriptCodec.failure(output),
            )
        }
        output
    }

    override fun close() {
        closed = true
        scope.cancel()
        if (ownsPool) pool.close()
    }

    private companion object {
        const val CONTROL_SCRIPT = "/data/adb/modules/vowifi_sw/control.sh"
    }
}

private fun String?.isSocketPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

private class RootCommandException(
    message: String,
    val failure: ControlScriptFailure? = null,
) : Exception(message)

private class EngineControlException(message: String) : Exception(message)
