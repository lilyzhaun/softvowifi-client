package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineExecutableMatch
import com.vowifi.sw.manager.model.EngineIntegritySnapshot
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.RunningEngineIntegrity
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class AndroidEngineIntegrityReader internal constructor(private val command: AndroidSuCommand) {
    constructor() : this(AndroidSuCommand(timeoutSeconds = 5, maxBytes = 8192))

    suspend fun read(state: EngineControlUiState): EngineIntegritySnapshot = runInterruptible(Dispatchers.IO) {
        val pids = (state as? EngineControlUiState.Ready)?.instances
            ?.filter { it.lifecycle == EngineLifecycle.RUNNING }?.map { it.pid }
        val readable = pids.orEmpty().withIndex().filter { (it.value ?: 0L) > 0L }
        fun unreadable() = EngineIntegritySnapshot(running = pids?.map {
            RunningEngineIntegrity(it, EngineExecutableMatch.UNREADABLE)
        })
        val script = buildString {
            fun hash(tag: String, path: String) {
                append("h=\$(sha256sum $path 2>/dev/null); printf '$tag=%s\\n' \"\${h%% *}\"\n")
            }
            hash("F", "/data/adb/softvowifi/runtime/bin/vowifi-engine-arm64")
            readable.forEach { hash("P${it.index}", "/proc/${it.value}/exe") }
        }
        val result = try { command.run(script) } catch (_: IOException) { return@runInterruptible unreadable() }
        if (result.timedOut || result.overflowed || result.writeFailed || result.exitCode != 0) {
            return@runInterruptible unreadable()
        }
        // One boundary check for the fixed, ordered output; no identity or raw command output escapes.
        val fields = result.output.lines().map { it.split('=', limit = 2) }
        val tags = listOf("F") + readable.map { "P${it.index}" }
        if (fields.size != tags.size || fields.indices.any { index ->
                fields[index].size != 2 || fields[index][0] != tags[index] ||
                    fields[index][1].let { it.isNotEmpty() && !it.matches(Regex("[a-f0-9]{64}")) }
            }) return@runInterruptible unreadable()
        val values = fields.associate { it[0] to it[1].takeIf(String::isNotEmpty) }
        val file = values["F"]
        EngineIntegritySnapshot(file?.take(12), pids?.mapIndexed { index, pid ->
            val executable = values["P$index"]
            RunningEngineIntegrity(pid, when {
                file == null || executable == null -> EngineExecutableMatch.UNREADABLE
                file == executable -> EngineExecutableMatch.SAME
                else -> EngineExecutableMatch.DIFFERENT
            })
        })
    }
}
