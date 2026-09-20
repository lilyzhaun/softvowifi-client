package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import kotlinx.coroutines.flow.StateFlow

interface EngineControlSource {
    val state: StateFlow<EngineControlUiState>
    fun refresh()
    fun start(iccid: String)
    fun stop(iccid: String)
    fun close() = Unit
}

internal fun lifecycleAfterControlFailure(
    current: EngineLifecycle,
    code: String?,
): EngineLifecycle = when (code) {
    "CONFIG_MISSING" -> EngineLifecycle.CONFIG_MISSING
    "BUSY", "START_CLEANUP_FAILED" -> current
    "IDENTITY_MISMATCH", "IDENTITY_UNREADABLE" -> EngineLifecycle.STOPPED
    else -> EngineLifecycle.STOPPED
}

internal fun controlFailureMessage(code: String?, fallback: String): String = when (code) {
    "IDENTITY_MISMATCH" -> "SIM 与配置身份不一致，已阻止启动"
    "IDENTITY_UNREADABLE" -> "无法读取 SIM 身份，已阻止启动"
    else -> fallback
}
