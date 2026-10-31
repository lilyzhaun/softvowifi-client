package com.vowifi.sw.manager.model

import kotlinx.serialization.Serializable

@Serializable
data class EngineConnectionProgress(
    val sequence: Long,
    val tunnel: String,
    val ims: String,
    val tunnelProgress: String = "",
    val imsProgress: String = "",
) {
    fun applyTo(runtime: EngineRuntimeStatus): EngineRuntimeStatus = runtime.copy(
        tunnel = tunnel, ims = ims, tunnelProgress = tunnelProgress, imsProgress = imsProgress,
    )
}
