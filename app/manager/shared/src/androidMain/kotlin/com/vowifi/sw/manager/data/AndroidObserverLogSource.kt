package com.vowifi.sw.manager.data

import android.content.Context
import android.os.FileObserver
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidObserverLogSource(
    context: Context,
    private val parser: RegistrationLogParser = RegistrationLogParser(),
) : ObserverLogSource {
    private val output = File(context.filesDir, "ims-registration.jsonl")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var closed = false
    private var batch: CompletableDeferred<RefreshResult>? = null
    private val mutableState = MutableStateFlow<RegistrationUiState>(RegistrationUiState.Loading)
    override val state: StateFlow<RegistrationUiState> = mutableState.asStateFlow()
    private val observer = object : FileObserver(
        context.filesDir,
        FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == output.name) refresh()
        }
    }

    init {
        scope.launch {
            for (request in requests) {
                val completion = synchronized(lock) {
                    if (closed) return@launch
                    val current = batch ?: CompletableDeferred<RefreshResult>().also { batch = it }
                    // An await may have supplied a wake after receive but before this claim.
                    // No file read has started yet: fold that wake into this snapshot only.
                    requests.tryReceive()
                    current
                }
                try {
                    val next = try {
                        if (output.exists()) parser.parse(ObserverLogTail.read(output)) else RegistrationUiState.Empty
                    } catch (_: IOException) {
                        RegistrationUiState.ReadError("无法读取 IMS 观测日志")
                    }
                    ensureActive()
                    val result = when (next) {
                        is RegistrationUiState.ReadError -> RefreshResult.Failed("无法读取 IMS 观测日志")
                        is RegistrationUiState.ParseError -> RefreshResult.Failed("IMS 观测日志无法解析")
                        is RegistrationUiState.Data -> if (next.snapshot.issues.isEmpty()) RefreshResult.Completed
                            else RefreshResult.Failed("部分 IMS 观测日志无法解析")
                        RegistrationUiState.Empty -> RefreshResult.Completed
                        RegistrationUiState.Loading -> RefreshResult.Unsupported
                    }
                    synchronized(lock) {
                        if (!closed) {
                            mutableState.value = next
                            batch = null
                            completion.complete(result)
                        }
                    }
                } catch (error: CancellationException) {
                    completion.cancel(error)
                    throw error
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                    throw error
                } finally {
                    synchronized(lock) {
                        if (batch === completion) {
                            batch = null
                            completion.cancel()
                        }
                    }
                }
            }
        }
        observer.startWatching()
        refresh()
    }

    override fun refresh() {
        synchronized(lock) { if (!closed) requests.trySend(Unit) }
    }

    override suspend fun refreshAndAwait(): RefreshResult {
        val completion = synchronized(lock) {
            if (closed) return RefreshResult.Unsupported
            batch ?: CompletableDeferred<RefreshResult>().also {
                batch = it
                requests.trySend(Unit)
            }
        }
        return completion.await()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            batch?.cancel(CancellationException("Observer source closed"))
        }
        observer.stopWatching()
        requests.close()
        scope.cancel()
    }
}
