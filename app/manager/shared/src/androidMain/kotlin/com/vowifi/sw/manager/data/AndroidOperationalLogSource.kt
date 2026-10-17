package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogList
import com.vowifi.sw.manager.model.OperationalLogLoadInfo
import com.vowifi.sw.manager.model.OperationalLogTail
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AndroidOperationalLogSource internal constructor(
    command: LogCommand,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : OperationalLogSource {
    constructor() : this(CancelLogShellRun(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val reader = OperationalLogReader(command)
    private val groups = OperationalLogGroupReader(reader)
    private val lock = Any()
    private val mutableState = MutableStateFlow<OperationalLogUiState>(OperationalLogUiState.Loading)
    override val state: StateFlow<OperationalLogUiState> = mutableState.asStateFlow()
    private var closed = false
    private var generation = 0L
    private var selected: String? = null
    private var filter = OperationalLogFilter()
    private var catalog = OperationalLogList(0, emptyList())
    private var legacy = false
    private var active: Job? = null
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val cache = LinkedHashMap<String, Snapshot>()
    private var displayed: OperationalLogTail? = null
    private var refreshRequest: Request? = null
    private var readIssue: String? = null
    private var blockingFailure: OperationalLogUiState? = null

    init {
        scope.launch {
            for (request in requests) {
                val previous = synchronized(lock) { active }
                previous?.cancel()
                previous?.join()
                synchronized(lock) {
                    if (!closed && generation == request.generation) {
                        active = scope.launch(start = CoroutineStart.LAZY) { load(request) }.also { job ->
                            job.invokeOnCompletion { error ->
                                if (error != null) synchronized(lock) {
                                    if (refreshRequest === request) refreshRequest = null
                                    request.completion?.completeExceptionally(error)
                                }
                            }
                            job.start()
                        }
                    } else {
                        request.completion?.complete(RefreshResult.Cancelled)
                    }
                }
            }
        }
        refresh()
    }
    override fun refresh() {
        requestRefresh()
    }
    override suspend fun refreshAndAwait(): RefreshResult =
        requestRefresh()?.await() ?: RefreshResult.Unsupported

    private fun requestRefresh(): CompletableDeferred<RefreshResult>? = synchronized(lock) {
        if (closed || !scope.isActive) return@synchronized null
        refreshRequest?.let { return@synchronized it.completion }
        cache.clear()
        enqueue(relist = true).completion
    }

    override fun selectFile(fileId: String): Unit = synchronized(lock) {
        if (closed || selected == fileId || catalog.files.none { it.id == fileId }) return
        selected = fileId
        readIssue = null; blockingFailure = null
        enqueue(relist = false)
        Unit
    }

    private fun enqueue(relist: Boolean): Request {
        refreshRequest?.completion?.complete(RefreshResult.Cancelled)
        refreshRequest = null
        generation++
        active?.cancel()
        val snapshot = if (legacy || relist) null else cache[selected]
        val hot = snapshot != null && nowMillis() - snapshot.at < 5000
        val request = Request(generation, relist, hot,
            if (relist) CompletableDeferred(scope.coroutineContext[Job]) else null)
        if (relist) refreshRequest = request
        requests.trySend(request)
        // 同 file 的展示快照与已失效的 IO cache 分开；只有选择才允许 hot 命中。
        publish(if (relist) displayedTail() else snapshot?.tail,
            OperationalLogLoadInfo(reading = !hot, cached = snapshot != null, legacy = legacy, issue = readIssue))
        return request
    }

    private suspend fun load(request: Request) {
        try {
            if (request.hot) synchronized(lock) { finish(request, RefreshResult.Completed) }
            else if (!request.relist || list(request)) read(request, retry = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: LogReadFailure) {
            synchronized(lock) {
                if (!current(request)) {
                    finish(request, RefreshResult.Cancelled)
                    return
                }
                readIssue = error.safeMessage
                if (catalog.files.isEmpty() || blockingFailure != null || error.blocking) {
                    blockingFailure = error.state; mutableState.value = error.state
                }
                else {
                    val snapshot = cache[selected]
                    publish(snapshot?.tail ?: displayedTail(), OperationalLogLoadInfo(
                        cached = snapshot != null,
                        legacy = legacy,
                        issue = error.safeMessage,
                    ))
                }
                finish(request, if (error.state is OperationalLogUiState.Unavailable) RefreshResult.Unsupported
                    else RefreshResult.Failed(error.safeMessage))
            }
        }
    }

    private suspend fun list(request: Request, completeEmpty: Boolean = true): Boolean {
        val result = reader.catalog()
        return synchronized(lock) {
            if (!current(request)) {
                finish(request, RefreshResult.Cancelled)
                return@synchronized false
            }
            val choice = groups.selection(result.catalog.files, catalog.files.firstOrNull { it.id == selected }, selected)
            catalog = result.catalog
            legacy = result.legacy
            cache.keys.retainAll(catalog.files.map { it.id }.toSet())
            if (!choice.sameGroup) readIssue = null
            else if (choice.id != selected) displayed = displayed?.copy(id = requireNotNull(choice.id))
            selected = choice.id
            if (selected == null) blockingFailure = null
            publish(displayedTail(), OperationalLogLoadInfo(reading = selected != null, legacy = legacy, issue = readIssue))
            if (selected == null && completeEmpty) finish(request, RefreshResult.Completed)
            selected != null
        }
    }

    private suspend fun read(request: Request, retry: Boolean) {
        val identity = synchronized(lock) {
            if (!current(request)) {
                finish(request, RefreshResult.Cancelled)
                return
            }
            Triple(selected ?: return, legacy, catalog.files)
        }
        val result = try {
            groups.read(identity.first, identity.second, identity.third)
        } catch (error: LogReadFailure) {
            if (error.missing && retry && list(request, completeEmpty = false)) {
                read(request, retry = false)
                return
            }
            throw error
        }
        synchronized(lock) {
            if (!current(request)) return
            val tail = result.tail
            if (!legacy && result.issue == null) {
                cache[identity.first] = Snapshot(tail, nowMillis())
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            readIssue = result.issue; blockingFailure = null
            publish(tail, OperationalLogLoadInfo(legacy = legacy, issue = readIssue))
            finish(request, result.issue?.let { RefreshResult.Failed(it) } ?: RefreshResult.Completed)
        }
    }

    private fun current(request: Request) = !closed && scope.isActive && generation == request.generation

    private fun finish(request: Request, result: RefreshResult) {
        if (refreshRequest === request) refreshRequest = null
        request.completion?.complete(if (current(request)) result else RefreshResult.Cancelled)
    }

    private fun displayedTail() = displayed?.takeIf { it.id == selected }

    private fun publish(tail: OperationalLogTail?, info: OperationalLogLoadInfo) {
        if (info.reading) blockingFailure?.let { mutableState.value = it; return }
        displayed = tail
        val chosen = selected
        if (chosen == null || catalog.files.isEmpty()) {
            mutableState.value = if (info.reading) OperationalLogUiState.Loading else OperationalLogUiState.Empty
            return
        }
        val entries = tail?.lines.orEmpty()
        val filtered = OperationalLogEvaluator.filter(entries, filter)
        mutableState.value = if (tail != null && (tail.truncated || tail.issues.isNotEmpty())) {
            OperationalLogUiState.Warning(entries, tail.truncated, tail.issues, catalog.files, chosen, filter, filtered, info)
        } else {
            OperationalLogUiState.Ready(entries, catalog.files, chosen, filter, filtered, info)
        }
    }

    override fun setKindFilter(kind: OperationalLogKind?) = applyFilter { it.copy(kind = kind) }
    override fun setLevelFilter(level: OperationalLogLevel?) = applyFilter { it.copy(level = level) }
    override fun setInstanceFilter(displayLabel: String?) = applyFilter { it.copy(displayLabel = displayLabel) }

    private fun applyFilter(transform: (OperationalLogFilter) -> OperationalLogFilter): Unit = synchronized(lock) {
        if (closed) return
        filter = transform(filter)
        mutableState.value = OperationalLogEvaluator.withFilter(mutableState.value, filter)
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        generation++
        refreshRequest?.completion?.cancel()
        refreshRequest = null
        requests.close()
        cache.clear()
        displayed = null
        scope.cancel()
    }

    private data class Request(
        val generation: Long,
        val relist: Boolean,
        val hot: Boolean,
        val completion: CompletableDeferred<RefreshResult>?,
    )
    private data class Snapshot(val tail: OperationalLogTail, val at: Long)
}
