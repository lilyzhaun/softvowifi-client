package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence

internal data class EngineActionPresentation(
    val id: EngineActionId,
    val handle: SimHandle,
    val label: String,
    val enabled: Boolean,
)

internal data class SimControlRowPresentation(
    val instance: SimControlInstance,
    val title: String,
    val statusText: String,
    val message: String?,
    val action: EngineActionPresentation,
    val tone: EngineTone,
) {
    val visibleStrings: List<String>
        get() = buildList {
            add(title)
            add(statusText)
            message?.let(::add)
            add(action.label)
            add(processLabel(instance.pid))
            add(safeUiText(instance.runtime?.tunnel, "未连接"))
            add(safeUiText(instance.runtime?.ims, "未注册"))
        }
}

internal data class SimControlBandPresentation(
    val message: String? = null,
    val messageTone: EngineTone = EngineTone.NEUTRAL,
    val rows: List<SimControlRowPresentation> = emptyList(),
)

internal fun dispatchEngineAction(
    action: EngineActionPresentation,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
) {
    when (action.id) {
        EngineActionId.START -> onStart(action.handle)
        EngineActionId.STOP -> onStop(action.handle)
    }
}

internal fun simControlBandPresentation(state: SimControlUiState): SimControlBandPresentation = when (state) {
    SimControlUiState.Loading -> SimControlBandPresentation("正在读取 SIM 与引擎状态")
    is SimControlUiState.PermissionDenied -> SimControlBandPresentation(
        message = "当前权限无法读取 SIM 控制状态",
        messageTone = EngineTone.WARNING,
    )
    is SimControlUiState.Failure -> SimControlBandPresentation(
        message = "无法读取 SIM 控制状态",
        messageTone = EngineTone.ERROR,
    )
    is SimControlUiState.Ready -> readyPresentation(state)
}

private fun readyPresentation(state: SimControlUiState.Ready): SimControlBandPresentation {
    val rows = state.instances.map(::simControlRowPresentation)
    val message = state.message?.let { safeUiText(it, READY_MESSAGE_FALLBACK) }
    return when {
        message != null -> SimControlBandPresentation(message, EngineTone.WARNING, rows)
        rows.isEmpty() -> SimControlBandPresentation("当前没有可显示的 SIM", EngineTone.WARNING)
        else -> SimControlBandPresentation(rows = rows)
    }
}

private fun simControlRowPresentation(instance: SimControlInstance): SimControlRowPresentation {
    val statusText = when (instance.presence) {
        SimPresence.ACTIVE_MATCHED -> engineLabel(instance.lifecycle)
        SimPresence.ACTIVE_UNCONFIGURED -> "未配置/未支持"
        SimPresence.REMOVED_CONFIG_RETAINED -> "SIM 已移除/配置保留"
    }
    val message = when (instance.presence) {
        SimPresence.ACTIVE_MATCHED -> instance.message?.let { safeUiText(it, "操作未完成") }
        SimPresence.ACTIVE_UNCONFIGURED -> "未配置/未支持"
        SimPresence.REMOVED_CONFIG_RETAINED -> "SIM 已移除/配置保留"
    }
    val actionId = when (instance.lifecycle) {
        EngineLifecycle.RUNNING, EngineLifecycle.STOPPING -> EngineActionId.STOP
        else -> EngineActionId.START
    }
    val actionLabel = when (instance.lifecycle) {
        EngineLifecycle.STARTING -> "启动中"
        EngineLifecycle.STOPPING -> "停止中"
        EngineLifecycle.RUNNING -> "停止引擎"
        else -> if (instance.presence == SimPresence.ACTIVE_UNCONFIGURED) "启动引擎（不可用）" else "启动引擎"
    }
    return SimControlRowPresentation(
        instance = instance,
        title = safeUiText(instance.displayLabel, "实例 ••••"),
        statusText = statusText,
        message = message,
        action = EngineActionPresentation(
            id = actionId,
            handle = instance.handle,
            label = actionLabel,
            enabled = if (actionId == EngineActionId.STOP) instance.canStop else instance.canStart,
        ),
        tone = engineTone(instance),
    )
}

internal fun engineTone(instance: SimControlInstance): EngineTone = when (instance.presence) {
    SimPresence.ACTIVE_MATCHED -> engineTone(instance.lifecycle, instance.runtime, instance.message)
    SimPresence.ACTIVE_UNCONFIGURED, SimPresence.REMOVED_CONFIG_RETAINED -> EngineTone.WARNING
}

@Composable
fun EngineControlBand(
    state: SimControlUiState,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
    modifier: Modifier = Modifier,
) {
    SimControlBand(simControlBandPresentation(state), onStart, onStop, modifier)
}

@Composable
internal fun SimControlBand(
    presentation: SimControlBandPresentation,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
    modifier: Modifier = Modifier,
) {
    EngineControlBandFrame(modifier, presentation.message, presentation.messageTone) {
        presentation.rows.forEach { row ->
            EngineInstanceCard(
                title = row.title,
                lifecycle = row.instance.lifecycle,
                pid = row.instance.pid,
                runtime = row.instance.runtime,
                message = row.message,
                statusText = row.statusText,
                tone = row.tone,
                action = EngineCardAction(row.action.id, row.action.label, row.action.enabled),
                showAction = true,
                onAction = { dispatchEngineAction(row.action, onStart, onStop) },
            )
        }
    }
}

private const val READY_MESSAGE_FALLBACK = "SIM 控制状态可能不完整"
