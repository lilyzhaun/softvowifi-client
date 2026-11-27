package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.serialization.json.*

/** Acknowledged revision is checked again against read-only disk reconciliation. */
internal data class ConfigurationNextCallResult(
    val result: ConfigurationCommandApplyResult,
    val configRevision: String? = null,
) {
    override fun toString(): String = "ConfigurationNextCallResult"
}

internal fun configurationNextCallResult(
    response: EngineProtocolFrame.Response,
    expectedRevision: String,
    requestedGroups: JsonObject,
): ConfigurationNextCallResult {
    val unconfirmed = ConfigurationNextCallResult(ConfigurationCommandApplyResult.Unconfirmed)
    response.error?.let { error ->
        if (response.result != null) return unconfirmed
        return ConfigurationNextCallResult(when {
            error.code == 1001 && error.message == "CONFIG_REVISION_CONFLICT" -> ConfigurationCommandApplyResult.RevisionConflict
            // Only the lifecycle lock rejection proves this request stopped before writing.
            error.code == 2001 && error.message == "configuration lifecycle busy" -> ConfigurationCommandApplyResult.Busy
            error.code == 1001 -> ConfigurationCommandApplyResult.Failed
            else -> ConfigurationCommandApplyResult.Unconfirmed
        })
    }
    val result = response.result ?: return unconfirmed
    if (result.keys != setOf("state", "effect", "configRevision", "configuration")) return unconfirmed
    fun string(key: String) = (result[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val revision = string("configRevision")
    if (!validConfigRevision(revision) || string("effect") != "next-call") return unconfirmed
    val state = when (string("state")) {
        "Saved" -> ConfigurationCommandApplyResult.Applied.takeIf { revision != expectedRevision }
        "Unchanged" -> ConfigurationCommandApplyResult.Unchanged.takeIf { revision == expectedRevision }
        else -> null
    } ?: return unconfirmed
    val facts = ConfigurationFactsCodec.local(result["configuration"], revision) ?: return unconfirmed
    for ((group, requested) in requestedGroups) {
        val actual = when (group) {
            "callSignaling" -> ConfigurationCallSignalingCodec.read(facts)?.let(ConfigurationCallSignalingCodec::encode)
            "callMedia" -> ConfigurationCallMediaCodec.read(facts)?.let(ConfigurationCallMediaCodec::encode)
            else -> null
        } ?: return unconfirmed
        val desired = if (requested is JsonObject && requested.isEmpty()) JsonNull else requested
        if (actual != desired) return unconfirmed
    }
    return ConfigurationNextCallResult(state, revision)
}
