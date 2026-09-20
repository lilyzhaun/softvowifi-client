package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle

internal sealed interface ActiveEngineSelection {
    data object None : ActiveEngineSelection
    data class Single(val iccid: String) : ActiveEngineSelection
    data object Multiple : ActiveEngineSelection
}

internal object ActiveEngineSelector {
    const val NONE_MESSAGE = "当前没有运行中的引擎"
    const val MULTIPLE_MESSAGE = "暂不支持同时使用多个运行中的引擎"

    fun select(instances: List<EngineInstanceState>): ActiveEngineSelection {
        val running = instances.filter { instance -> instance.lifecycle == EngineLifecycle.RUNNING }
        return when (running.size) {
            0 -> ActiveEngineSelection.None
            1 -> ActiveEngineSelection.Single(running.single().iccid)
            else -> ActiveEngineSelection.Multiple
        }
    }
}
