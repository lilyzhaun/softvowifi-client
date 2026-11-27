package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import com.vowifi.sw.manager.model.EngineProtocolFrame

/** An opaque authority and ordering hint, never a retained wire/sample payload. */
internal class EngineRuntimeFactsChange(val sequence: Long, val connection: Any, val isCurrent: () -> Boolean)

/** Receipt order is local to a single socket reader; request IDs need not follow write order. */
internal class ReceivedEngineResponse(val frame: EngineProtocolFrame.Response, val receipt: Long)

internal class ConfigurationRuntimeObservation(
    val facts: ConfigurationRuntimeFacts?,
    val connection: Any,
    private val receipt: Long,
    val isCurrent: () -> Boolean,
) {
    fun follows(other: ConfigurationRuntimeObservation): Boolean = connection === other.connection && receipt > other.receipt
}
