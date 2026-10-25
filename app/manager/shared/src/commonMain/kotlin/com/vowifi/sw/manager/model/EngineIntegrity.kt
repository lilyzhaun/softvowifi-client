package com.vowifi.sw.manager.model

enum class EngineExecutableMatch { SAME, DIFFERENT, UNREADABLE }

data class RunningEngineIntegrity(val pid: Long?, val match: EngineExecutableMatch)

/** null roster means unreadable; an empty roster means a confirmed idle engine snapshot. */
data class EngineIntegritySnapshot(
    val fileFingerprint: String? = null,
    val running: List<RunningEngineIntegrity>? = null,
) {
    override fun toString(): String = "EngineIntegritySnapshot"
}
