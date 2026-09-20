package com.vowifi.sw.manager.data

import android.content.Context
import android.os.FileObserver
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidObserverLogSource(
    context: Context,
    private val parser: RegistrationLogParser = RegistrationLogParser(),
) : ObserverLogSource {
    private val output = File(context.filesDir, "ims-registration.jsonl")
    private val mutableState = MutableStateFlow<RegistrationUiState>(RegistrationUiState.Loading)
    override val state: StateFlow<RegistrationUiState> = mutableState.asStateFlow()
    private val observer = object : FileObserver(
        context.filesDir,
        FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.MOVED_TO or FileObserver.CREATE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == output.name) refresh()
        }
    }

    init {
        observer.startWatching()
        refresh()
    }

    override fun refresh() {
        mutableState.value = runCatching {
            if (output.exists()) parser.parse(output.readText()) else RegistrationUiState.Empty
        }.getOrElse { error ->
            RegistrationUiState.ReadError(error.message ?: "无法读取 IMS 观测日志")
        }
    }

    override fun close() {
        observer.stopWatching()
    }
}
