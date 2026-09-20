package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object EngineControlScriptCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun instances(output: String): List<EngineInstanceState> =
        json.decodeFromString<List<ControlInstance>>(output).map { instance ->
            require(instance.ok) { "control.sh returned an unsuccessful instance" }
            EngineInstanceState(
                iccid = instance.iccid,
                lifecycle = when (instance.state) {
                    "config_missing" -> EngineLifecycle.CONFIG_MISSING
                    "stopped" -> EngineLifecycle.STOPPED
                    "starting" -> EngineLifecycle.STARTING
                    "running" -> EngineLifecycle.RUNNING
                    "stopping" -> EngineLifecycle.STOPPING
                    else -> throw IllegalArgumentException("unknown engine lifecycle: ${instance.state}")
                },
                pid = instance.pid,
            )
        }

    fun failure(output: String): ControlScriptFailure? =
        runCatching { json.decodeFromString<ControlScriptFailure>(output) }
            .getOrNull()
            ?.takeUnless(ControlScriptFailure::ok)
}

@Serializable
data class ControlScriptFailure(
    val ok: Boolean,
    val code: String,
    val message: String,
)

@Serializable
private data class ControlInstance(
    val ok: Boolean,
    val iccid: String,
    val state: String,
    val pid: Long? = null,
)
