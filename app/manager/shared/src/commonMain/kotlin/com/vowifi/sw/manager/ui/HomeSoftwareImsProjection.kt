package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimPresence

internal data class HomeSoftwareImsProjection(
    val readable: Boolean,
    val active: List<SimControlInstance>,
    val registered: Int,
    val unknown: Int,
    val connected: Int,
) {
    val total: Int get() = active.size
    val allRegistered: Boolean get() = readable && total > 0 && registered == total
    val metric: String get() = when {
        !readable || unknown > 0 -> "未知"
        total == 0 -> "无 SIM"
        else -> registered.toString()
    }
    val overview: String get() = when {
        !readable -> "无法确认活动 SIM 注册状态"
        total == 0 -> "暂无活动 SIM"
        else -> "$registered / $total 张活动 SIM 已注册" + if (unknown > 0) " · $unknown 张未知" else ""
    }
    val tone: SemanticTone get() = when {
        !readable || unknown > 0 -> SemanticTone.WARNING
        allRegistered -> SemanticTone.SUCCESS
        registered > 0 -> SemanticTone.WARNING
        else -> SemanticTone.NEUTRAL
    }
}

internal fun homeSoftwareImsProjection(engine: SimControlUiState): HomeSoftwareImsProjection {
    val active = (engine as? SimControlUiState.Ready)?.instances.orEmpty().filter {
        it.presence == SimPresence.ACTIVE_MATCHED || it.presence == SimPresence.ACTIVE_UNCONFIGURED
    }
    val running = active.filter { it.lifecycle == EngineLifecycle.RUNNING }
    return HomeSoftwareImsProjection(
        readable = engine is SimControlUiState.Ready,
        active = active,
        registered = running.count { it.runtime?.ims == "REGISTERED" },
        unknown = running.count { it.runtime?.ims !in setOf("REGISTERED", "REGISTERING", "UNREGISTERED") },
        connected = running.count { it.runtime?.tunnel == "CONNECTED" },
    )
}
