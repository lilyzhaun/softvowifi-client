package com.vowifi.sw.manager.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Untrusted file boundary. Never displays original JSON, unknown versions, or unknown fields. */
internal object DiagnosticCodec {
    private val fields = setOf("v", "wall_ms", "mono_ms", "run", "pid", "component", "level", "name",
        "stage", "result", "reason", "slot", "count", "accepted", "attempt", "network_generation",
        "suppressed", "first_mono_ms", "last_mono_ms", "dropped", "evicted", "family", "source_family", "endpoint", "notification")
    private val runPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun parse(line: String): DiagnosticRecord? = try {
        if (line.length > 2047 || line.encodeToByteArray().size > 2047) null
        else (Json.parseToJsonElement(line) as? JsonObject)?.let(::record)
    } catch (_: IllegalArgumentException) { null }

    private fun record(value: JsonObject): DiagnosticRecord? {
        if (value.keys.any { it !in fields } || number(value, "v") != 1L) return null
        val run = text(value, "run")?.takeIf(runPattern::matches) ?: return null
        val pid = number(value, "pid")?.takeIf { it in 1..4_194_304L }?.toInt() ?: return null
        val wall = number(value, "wall_ms")?.takeIf { it in 0..9_999_999_999_999L } ?: return null
        val mono = number(value, "mono_ms")?.takeIf { it in 0..1_000_000_000_000L } ?: return null
        val optional = listOf("slot", "count", "accepted", "attempt", "network_generation")
        if (optional.any { it in value && number(value, it) == null }) return null
        if (optional.dropLast(1).any { (number(value, it) ?: 0L) !in 0..1_000_000_000L }) return null
        if (listOf("family", "source_family").any { it in value && enumValue<DiagnosticFamily>(value, it) == null }) return null
        if ("endpoint" in value && text(value,"endpoint") == null) return null
        val notification = if ("notification" in value) NotificationDiagnostic.parse(value["notification"]) ?: return null else null
        val event = DiagnosticEvent(
            enumValue<DiagnosticComponent>(value, "component") ?: return null,
            enumValue<DiagnosticLevel>(value, "level") ?: return null,
            enumValue<DiagnosticName>(value, "name") ?: return null,
            enumValue<DiagnosticStage>(value, "stage") ?: return null,
            enumValue<DiagnosticResult>(value, "result") ?: return null,
            enumValue<DiagnosticReason>(value, "reason") ?: return null,
            number(value, "slot")?.toInt(), number(value, "count")?.toInt(),
            number(value, "accepted")?.toInt(), number(value, "attempt")?.toInt(),
            number(value, "network_generation"),
            enumValue<DiagnosticFamily>(value, "family"), enumValue<DiagnosticFamily>(value, "source_family"), text(value,"endpoint"),
            notification,
        )
        if (!event.valid()) return null
        val suppressed = number(value, "suppressed")?.takeIf { it in 0..1_000_000_000L } ?: return null
        val first = number(value, "first_mono_ms")?.takeIf { it in 0..mono } ?: return null
        val last = number(value, "last_mono_ms")?.takeIf { it in first..mono } ?: return null
        val dropped = number(value, "dropped")?.takeIf { it in 0..1_000_000_000L } ?: return null
        val evicted = number(value, "evicted")?.takeIf { it in 0..1_000_000_000L } ?: return null
        return DiagnosticRecord(event, run, pid, wall, mono, suppressed, first, last, dropped, evicted)
    }

    private fun number(value: JsonObject, key: String): Long? = (value[key] as? JsonPrimitive)?.let {
        if (it.isString) null else it.content.toLongOrNull()
    }
    private fun text(value: JsonObject, key: String): String? = (value[key] as? JsonPrimitive)?.let {
        if (it.isString) it.content else null
    }
    private inline fun <reified T : Enum<T>> enumValue(value: JsonObject, key: String): T? =
        enumValues<T>().firstOrNull { it.name.lowercase() == text(value, key) }
}
