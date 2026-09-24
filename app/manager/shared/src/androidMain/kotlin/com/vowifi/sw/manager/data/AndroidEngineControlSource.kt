package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidEngineControlSource internal constructor(
    private val runControl: suspend (String, String?) -> String,
    private val request: suspend (String, String) -> EngineProtocolFrame.Response,
    private val removeClient: (String) -> Unit,
    private val closeBackend: () -> Unit,
    private val queryCapabilities: suspend (String) -> ClientCapabilitiesResult,
    parentContext: CoroutineContext,
) : EngineControlSource {
    constructor() : this(AndroidEngineClientPool(), ownsPool = true)
    constructor(pool: AndroidEngineClientPool) : this(pool, ownsPool = false)
    constructor(pool: AndroidEngineClientPool, controlCommand: suspend (String, String?) -> String) :
        this(pool, ownsPool = false, controlCommand = controlCommand)
    private constructor(
        pool: AndroidEngineClientPool,
        ownsPool: Boolean,
        controlCommand: suspend (String, String?) -> String = ::runEngineControlScript,
    ) : this(
        controlCommand,
        { owner, method -> pool.clientFor(owner).request(method) },
        { owner -> pool.remove(owner)?.close() },
        { if (ownsPool) pool.close() },
        pool::clientCapabilities,
        Dispatchers.IO,
    )

    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val operations = Mutex()
    private val pendingOwners = mutableSetOf<String>()
    @Volatile
    private var closed = false
    private val mutableState = MutableStateFlow<EngineControlUiState>(EngineControlUiState.Loading)
    override val state: StateFlow<EngineControlUiState> = mutableState.asStateFlow()

    init {
        refresh()
        scope.launchEngineAutoRefresh(state, refresh = ::refresh)
    }

    override fun refresh() {
        if (closed || !operations.tryLock()) return
        scope.launch { refreshLocked() }.invokeOnCompletion { operations.unlock() }
    }

    override fun start(iccid: String) {
        launchOperation(iccid, EngineLifecycle.STOPPED) {
            updateInstance(iccid) { it.copy(lifecycle = EngineLifecycle.STARTING, message = null) }
            runCatching { runControl("start", iccid) }
                .onSuccess { refreshLocked() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    updateFailure(iccid, error)
                }
        }
    }

    override fun stop(iccid: String) {
        launchOperation(iccid, EngineLifecycle.RUNNING) {
            updateInstance(iccid) { it.copy(lifecycle = EngineLifecycle.STOPPING, message = null) }
            var failure: Exception? = null
            try {
                val response = request(iccid, "stop")
                response.error?.let { error -> throw EngineControlException(error.message) }
                removeClient(iccid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
            try {
                runControl("stop", iccid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
            if (failure != null) removeClient(iccid)
            refreshLocked()
            failure?.let { error -> updateInstance(iccid) { it.copy(message = error.message ?: "引擎停止失败") } }
        }
    }

    private fun launchOperation(owner: String, eligible: EngineLifecycle, operation: suspend () -> Unit) {
        synchronized(pendingOwners) {
            if (closed || !pendingOwners.add(owner)) return
        }
        scope.launch {
            operations.withLock {
                if (closed) return@withLock
                val current = mutableState.value as? EngineControlUiState.Ready
                if (current != null && current.instances.none { it.iccid == owner && it.lifecycle == eligible }) return@withLock
                operation()
            }
        }.invokeOnCompletion { synchronized(pendingOwners) { pendingOwners.remove(owner) } }
    }

    private suspend fun refreshLocked() {
        val refreshed = try {
            val listed = EngineControlScriptCodec.instances(runControl("list", null))
            val running = listed.map { instance ->
                if (instance.lifecycle == EngineLifecycle.RUNNING) status(instance) else instance
            }
            EngineControlUiState.Ready(running)
        } catch (error: CancellationException) {
            throw error
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
        synchronized(pendingOwners) {
            if (!closed) mutableState.value = refreshed
        }
    }

    private suspend fun status(instance: EngineInstanceState): EngineInstanceState = try {
        val response = request(instance.iccid, "getStatus")
        response.error?.let { error -> throw EngineControlException(error.message) }
        val runtime = EngineProtocolCodec.status(response)
        val capabilities = queryCapabilities(instance.iccid)
        currentCoroutineContext().ensureActive()
        instance.copy(runtime = runtime, message = null, clientCapabilities = capabilities)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        removeClient(instance.iccid)
        val message = if (error is IOException && error.message.isSocketPermissionDenied()) {
            "控制 socket 权限被拒绝，请检查模块 SELinux 与 socket 权限"
        } else {
            error.message ?: "控制 socket 不可用"
        }
        instance.copy(message = message, clientCapabilities = null)
    }

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

    override fun close() {
        synchronized(pendingOwners) {
            if (closed) return
            closed = true
        }
        scope.cancel()
        closeBackend()
    }
}

private suspend fun runEngineControlScript(command: String, iccid: String?): String = withContext(Dispatchers.IO) {
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

private const val CONTROL_SCRIPT = "/data/adb/modules/vowifi_sw/control.sh"

private fun String?.isSocketPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

private class RootCommandException(
    message: String,
    val failure: ControlScriptFailure? = null,
) : Exception(message)

private class EngineControlException(message: String) : Exception(message)
