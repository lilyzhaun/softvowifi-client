package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogList
import com.vowifi.sw.manager.model.OperationalLogLoadInfo
import com.vowifi.sw.manager.model.OperationalLogTail
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CancellationException
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

    init {
        scope.launch {
            for (request in requests) {
                val previous = synchronized(lock) { active }
                previous?.cancel()
                previous?.join()
                synchronized(lock) {
                    if (!closed && generation == request.generation) {
                        active = scope.launch(start = CoroutineStart.LAZY) { load(request) }.also { it.start() }
                    }
                }
            }
        }
        refresh()
    }

    override fun refresh(): Unit = synchronized(lock) {
        if (closed) return
        cache.clear()
        enqueue(relist = true)
    }

    override fun selectFile(fileId: String): Unit = synchronized(lock) {
        if (closed || selected == fileId || catalog.files.none { it.id == fileId }) return
        selected = fileId
        enqueue(relist = false)
    }

    private fun enqueue(relist: Boolean) {
        generation++
        active?.cancel()
        val snapshot = if (legacy || relist) null else cache[selected]
        val hot = snapshot != null && nowMillis() - snapshot.at < 5000
        publish(snapshot?.tail, OperationalLogLoadInfo(reading = !hot, cached = snapshot != null, legacy = legacy))
        requests.trySend(Request(generation, relist, hot))
    }

    private suspend fun load(request: Request) {
        try {
            if (request.hot) return
            if (request.relist && !list(request)) return
            read(request, retry = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: LogReadFailure) {
            synchronized(lock) {
                if (!current(request)) return
                if (catalog.files.isEmpty()) mutableState.value = error.state
                else {
                    val snapshot = cache[selected]
                    publish(snapshot?.tail, OperationalLogLoadInfo(
                        cached = snapshot != null,
                        legacy = legacy,
                        issue = error.safeMessage,
                    ))
                }
            }
        }
    }

    private suspend fun list(request: Request): Boolean {
        val result = reader.catalog()
        return synchronized(lock) {
            if (!current(request)) return@synchronized false
            catalog = result.catalog
            legacy = result.legacy
            cache.keys.retainAll(catalog.files.map { it.id }.toSet())
            selected = OperationalLogEvaluator.selectedFileId(catalog.files, selected)
            publish(null, OperationalLogLoadInfo(reading = selected != null, legacy = legacy))
            selected != null
        }
    }

    private suspend fun read(request: Request, retry: Boolean) {
        val identity = synchronized(lock) {
            if (!current(request)) return
            Pair(selected ?: return, legacy)
        }
        val tail = try {
            reader.read(identity.first, identity.second)
        } catch (error: LogReadFailure) {
            if (error.missing && retry && list(request)) {
                read(request, retry = false)
                return
            }
            throw error
        }
        synchronized(lock) {
            if (!current(request)) return
            if (!legacy) {
                cache[identity.first] = Snapshot(tail, nowMillis())
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            publish(tail, OperationalLogLoadInfo(legacy = legacy))
        }
    }

    private fun current(request: Request) = !closed && scope.isActive && generation == request.generation

    private fun publish(tail: OperationalLogTail?, info: OperationalLogLoadInfo) {
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
        requests.close()
        cache.clear()
        scope.cancel()
    }

    private data class Request(val generation: Long, val relist: Boolean, val hot: Boolean)
    private data class Snapshot(val tail: OperationalLogTail, val at: Long)
}
