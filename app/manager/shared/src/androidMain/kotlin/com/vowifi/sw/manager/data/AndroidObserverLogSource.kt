package com.vowifi.sw.manager.data

import android.content.Context
import android.os.FileObserver
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
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
                val next = try {
                    if (output.exists()) parser.parse(ObserverLogTail.read(output)) else RegistrationUiState.Empty
                } catch (_: IOException) {
                    RegistrationUiState.ReadError("无法读取 IMS 观测日志")
                }
                ensureActive()
                mutableState.value = next
            }
        }
        observer.startWatching()
        refresh()
    }

    override fun refresh() {
        requests.trySend(Unit)
    }

    override fun close() {
        observer.stopWatching()
        requests.close()
        scope.cancel()
    }
}
