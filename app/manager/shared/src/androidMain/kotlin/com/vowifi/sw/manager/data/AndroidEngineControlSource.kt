package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ClientCapabilitiesResult
import com.vowifi.sw.manager.model.ClientCapabilitiesFailure
import com.vowifi.sw.manager.model.EngineControlAction
import com.vowifi.sw.manager.model.EngineControlCompletion
import com.vowifi.sw.manager.model.EngineControlOutcome
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.EngineConnectionProgress
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidEngineControlSource private constructor(
    private val runControl: suspend (String, String?) -> String,
    private val request: suspend (String, String) -> EngineProtocolFrame.Response,
    private val removeClient: (String) -> Unit,
    private val closeBackend: () -> Unit,
    private val queryCapabilities: suspend (String) -> ClientCapabilitiesResult,
    private val progressEvents: ((String) -> SharedFlow<EngineConnectionProgressEvent>)?,
    parentContext: CoroutineContext,
) : EngineControlSource {
    internal constructor(
        runControl: suspend (String, String?) -> String,
        request: suspend (String, String) -> EngineProtocolFrame.Response,
        removeClient: (String) -> Unit,
        closeBackend: () -> Unit,
        queryCapabilities: suspend (String) -> ClientCapabilitiesResult,
        parentContext: CoroutineContext,
    ) : this(runControl, request, removeClient, closeBackend, queryCapabilities, null, parentContext)
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
        pool::connectionProgress,
        Dispatchers.IO,
    )

    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val operations = Mutex()
    private val pendingOwners = mutableSetOf<String>()
    private var refreshInFlight: CompletableDeferred<RefreshResult>? = null
    private var controlSequence = 0L
    private val controlCompletions = mutableMapOf<String, EngineControlCompletion>()
    private val progressWatches = mutableMapOf<String, Pair<SharedFlow<EngineConnectionProgressEvent>, Job>>()
    private data class Progress(val snapshot: EngineConnectionProgress, val event: EngineConnectionProgressEvent, val revision: Long)
    private val latestProgress = mutableMapOf<String, Progress>()
    private var progressRevision = 0L
    @Volatile
    private var closed = false
    private val mutableState = MutableStateFlow<EngineControlUiState>(EngineControlUiState.Loading)
    override val state: StateFlow<EngineControlUiState> = mutableState.asStateFlow()

    init {
        refresh()
        scope.launchEngineAutoRefresh(state, refresh = ::refresh)
    }

    override fun refresh() {
        requestRefresh()
    }

    override suspend fun refreshAndAwait(): RefreshResult = requestRefresh().await()

    private fun requestRefresh(): Deferred<RefreshResult> {
        var producer: Job? = null
        val accepted = synchronized(pendingOwners) {
            if (closed) return@synchronized CompletableDeferred(RefreshResult.Unsupported)
            refreshInFlight?.let { return@synchronized it }
            if (!operations.tryLock()) return@synchronized CompletableDeferred(RefreshResult.Busy)
            val completion = CompletableDeferred<RefreshResult>()
            refreshInFlight = completion
            var result: RefreshResult? = null
            producer = scope.launch(start = CoroutineStart.LAZY) { result = refreshLocked() }.also { job ->
                job.invokeOnCompletion { error ->
                    synchronized(pendingOwners) { refreshInFlight = null }
                    // Unlock and notify outside the short state lock: queued control/callers may resume inline.
                    operations.unlock()
                    when (error) {
                        null -> completion.complete(checkNotNull(result))
                        is CancellationException -> completion.cancel(error)
                        else -> completion.completeExceptionally(error)
                    }
                }
            }
            completion
        }
        producer?.start()
        return accepted
    }

    override fun start(iccid: String) {
        launchOperation(iccid, EngineLifecycle.STOPPED) {
            updateInstance(iccid) { it.copy(lifecycle = EngineLifecycle.STARTING, message = null) }
            runCatching { runControl("start", iccid) }
                .onSuccess {
                    refreshLocked()
                    completeControl(iccid, EngineControlAction.Start, EngineControlOutcome.Completed)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    updateFailure(iccid, error)
                    completeControl(iccid, EngineControlAction.Start, EngineControlOutcome.Failed)
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
            completeControl(iccid, EngineControlAction.Stop,
                if (failure == null) EngineControlOutcome.Completed else EngineControlOutcome.Failed)
        }
    }

    private fun launchOperation(owner: String, eligible: EngineLifecycle, operation: suspend () -> Unit) {
        synchronized(pendingOwners) {
            if (closed || !pendingOwners.add(owner)) return
        }
        scope.launch {
            // Publish acceptance before waiting for the existing operation lock, not a fake phase.
            synchronized(pendingOwners) { publishPending() }
            operations.withLock {
                if (closed) return@withLock
                val current = mutableState.value as? EngineControlUiState.Ready
                if (current != null && current.instances.none { it.iccid == owner && it.lifecycle == eligible }) return@withLock
                operation()
            }
        }.invokeOnCompletion {
            synchronized(pendingOwners) { pendingOwners.remove(owner); publishPending() }
        }
    }

    private suspend fun refreshLocked(): RefreshResult {
        val progressBefore = synchronized(pendingOwners) { progressRevision }
        var readFailed = false
        val refreshed = try {
            val listed = EngineControlScriptCodec.instances(runControl("list", null))
            val running = listed.map { instance ->
                if (instance.lifecycle == EngineLifecycle.RUNNING) status(instance) { readFailed = true } else instance
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
        currentCoroutineContext().ensureActive()
        synchronized(pendingOwners) {
            if (!closed) {
                if (refreshed is EngineControlUiState.Ready) {
                    val active = refreshed.instances.filter { it.lifecycle == EngineLifecycle.RUNNING }.map { it.iccid }.toSet()
                    progressWatches.keys.filter { it !in active }.forEach { owner ->
                        progressWatches.remove(owner)?.second?.cancel()
                        latestProgress.remove(owner)
                    }
                }
                mutableState.value = if (refreshed is EngineControlUiState.Ready) refreshed.copy(
                    instances = refreshed.instances.map { row -> row.copy(
                        runtime = row.runtime?.let { runtime ->
                            val recent = latestProgress[row.iccid]
                            if (recent != null && recent.revision > progressBefore && recent.event.isCurrent()) recent.snapshot.applyTo(runtime)
                            else runtime
                        },
                        operationPending = row.iccid in pendingOwners,
                        controlCompletion = controlCompletions[row.iccid],
                    ) },
                ) else refreshed
            }
        }
        return if (readFailed || refreshed !is EngineControlUiState.Ready) RefreshResult.Failed("无法完整读取引擎状态")
            else RefreshResult.Completed
    }

    private suspend fun status(instance: EngineInstanceState, onReadFailure: () -> Unit): EngineInstanceState = try {
        watchProgress(instance.iccid)
        val response = request(instance.iccid, "getStatus")
        response.error?.let { error -> throw EngineControlException(error.message) }
        val runtime = EngineProtocolCodec.status(response)
        val capabilities = queryCapabilities(instance.iccid)
        if (capabilities is ClientCapabilitiesResult.Unavailable && capabilities.reason != ClientCapabilitiesFailure.Unsupported) {
            onReadFailure()
        }
        currentCoroutineContext().ensureActive()
        instance.copy(runtime = runtime, message = null, clientCapabilities = capabilities)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onReadFailure()
        removeClient(instance.iccid)
        val message = if (error is IOException && error.message.isSocketPermissionDenied()) {
            "控制连接权限被拒绝，请检查 Root 授权与后端运行环境"
        } else {
            error.message ?: "控制 socket 不可用"
        }
        instance.copy(message = message, clientCapabilities = null)
    }

    private fun watchProgress(owner: String) {
        val stream = progressEvents?.invoke(owner) ?: return
        synchronized(pendingOwners) {
            if (closed || progressWatches[owner]?.first === stream) return
            progressWatches.remove(owner)?.second?.cancel()
            latestProgress.remove(owner)
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                stream.collect { event ->
                    val snapshot = EngineProtocolCodec.connectionProgress(event.frame) ?: return@collect
                    synchronized(pendingOwners) {
                        if (closed || !event.isCurrent()) return@synchronized
                        val previous = latestProgress[owner]
                        if (previous != null && previous.event.connectionToken === event.connectionToken &&
                            previous.snapshot.sequence >= snapshot.sequence) return@synchronized
                        latestProgress[owner] = Progress(snapshot, event, ++progressRevision)
                        updateInstance(owner) { row -> row.copy(runtime = row.runtime?.let(snapshot::applyTo)) }
                    }
                }
            }
            progressWatches[owner] = stream to job
        }
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

    private fun updateInstance(iccid: String, transform: (EngineInstanceState) -> EngineInstanceState) = synchronized(pendingOwners) {
        if (closed) return@synchronized
        val current = mutableState.value
        if (current !is EngineControlUiState.Ready) return@synchronized
        mutableState.value = current.copy(
            instances = current.instances.map { instance ->
                if (instance.iccid == iccid) transform(instance) else instance
            },
        )
    }

    private fun publishPending() {
        if (closed) return
        val current = mutableState.value as? EngineControlUiState.Ready ?: return
        mutableState.value = current.copy(instances = current.instances.map { it.copy(operationPending = it.iccid in pendingOwners) })
    }

    private fun completeControl(owner: String, action: EngineControlAction, outcome: EngineControlOutcome) = synchronized(pendingOwners) {
        if (!closed) {
            val completion = EngineControlCompletion(++controlSequence, action, outcome)
            controlCompletions[owner] = completion
            updateInstance(owner) { it.copy(controlCompletion = completion) }
        }
    }

    override fun close() {
        synchronized(pendingOwners) {
            if (closed) return
            closed = true
            pendingOwners.clear()
            // Closing cancels accepted/queued work; it must not leave its UI progress latched.
            val current = mutableState.value as? EngineControlUiState.Ready
            if (current != null) mutableState.value = current.copy(
                instances = current.instances.map { it.copy(operationPending = false) },
            )
            refreshInFlight?.cancel()
            controlCompletions.clear()
            latestProgress.clear()
            progressWatches.clear()
        }
        scope.cancel()
        closeBackend()
    }
}

private suspend fun runEngineControlScript(command: String, iccid: String?): String =
    runEngineControlProcess(command, iccid) { com.vowifi.sw.root.RootBridge.startCommand(it) }

internal suspend fun runEngineControlProcess(command: String, iccid: String?, start: (String) -> Process): String = withContext(Dispatchers.IO) {
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
    val process = start(invocation)
    // Startup includes strict binding, a bounded 40s carrier read and the
    // existing socket admission window. Do not kill that transaction at 20s.
    val timeoutSeconds = if (command == "start") 75L else 20L
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
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

private const val CONTROL_SCRIPT = "/data/adb/softvowifi/runtime/control.sh"

private fun String?.isSocketPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

private class RootCommandException(
    message: String,
    val failure: ControlScriptFailure? = null,
) : Exception(message)

private class EngineControlException(message: String) : Exception(message)
