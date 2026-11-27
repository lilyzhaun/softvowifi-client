package com.vowifi.sw.manager.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal enum class ConfigurationCommandApplyResult { Applied, Unchanged, RevisionConflict, Busy, Failed, Unconfirmed }

internal fun configurationApplyResult(outcome: ConfigurationCommandOutcome): ConfigurationCommandApplyResult {
    if (outcome.timedOut || outcome.overflowed || outcome.writeFailed) return ConfigurationCommandApplyResult.Unconfirmed
    // config apply reserves exit 9 for a refusal before the target file is written.
    if (outcome.exitCode == 9) {
        val refused = try { Json.decodeFromString(BusyReceipt, outcome.output) } catch (_: SerializationException) { false }
        return if (refused) ConfigurationCommandApplyResult.Busy else ConfigurationCommandApplyResult.Unconfirmed
    }
    val response = ConfigurationCodec.result(outcome.output) ?: return ConfigurationCommandApplyResult.Unconfirmed
    return when {
        response.ok && response.code == "APPLIED" && outcome.exitCode == 0 -> ConfigurationCommandApplyResult.Applied
        response.ok && response.code == "UNCHANGED" && outcome.exitCode == 0 -> ConfigurationCommandApplyResult.Unchanged
        !response.ok && response.code == "CONFIG_REVISION_CONFLICT" && outcome.exitCode == 1 -> ConfigurationCommandApplyResult.RevisionConflict
        response.code == "BUSY" -> ConfigurationCommandApplyResult.Unconfirmed
        !response.ok && response.code != "ROLLBACK_FAILED" -> ConfigurationCommandApplyResult.Failed
        else -> ConfigurationCommandApplyResult.Unconfirmed
    }
}

// Read the three json_error members before a JsonObject could discard duplicate keys.
private object BusyReceipt : DeserializationStrategy<Boolean> {
    override val descriptor = ControlScriptFailure.serializer().descriptor

    override fun deserialize(decoder: Decoder): Boolean = decoder.decodeStructure(descriptor) {
        val fields = mutableMapOf<String, JsonPrimitive>()
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            val value = decodeSerializableElement(descriptor, index, JsonPrimitive.serializer())
            if (fields.put(descriptor.getElementName(index), value) != null) throw SerializationException("Duplicate refusal field")
        }
        fields["ok"] == JsonPrimitive(false) && fields["code"] == JsonPrimitive("BUSY") && fields["message"]?.isString == true
    }
}
