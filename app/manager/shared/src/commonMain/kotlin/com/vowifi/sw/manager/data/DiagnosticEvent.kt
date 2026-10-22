package com.vowifi.sw.manager.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// No free-form strings, exception messages, identities or arbitrary maps enter this API.
enum class DiagnosticComponent { APP, SUBSCRIPTIONS, CONFIGURATION, UNDERLAY, MODULE, ENGINE, DNS, TUNNEL }
enum class DiagnosticLevel { INFO, WARN, ERROR }
enum class DiagnosticName { PROCESS_STARTED, PROCESS_STOPPING, OBSERVED, REJECTED, RECOVERED, SUPPRESSED }
enum class DiagnosticStage {
    PROCESS, PERMISSION, ENUMERATE, IDENTITY, NORMALIZE, LISTENER, CONFIG_MATCH, CONFIG_CREATE,
    CONFIG_REFRESH, CONFIG_APPLY, STRICT_VERIFY, DISCOVER, PUBLISH, SNAPSHOT, SELECT, SOURCE, DNS, SOCKET,
    WRITE, RESPONSE, CONNECT, STOP, SOCKET_CONTROL,
}
enum class DiagnosticResult { OBSERVED, STARTED, SUCCESS, FAILURE, CANCELLED, UNAVAILABLE, STOPPED }
enum class DiagnosticReason {
    NONE, UNKNOWN, UNAVAILABLE, PERMISSION_DENIED, ROOT_DENIED, EMPTY, INVALID_LENGTH,
    UNSUPPORTED_CHARACTERS, LISTENER_FAILED, IO_FAILED, LOCK_BUSY, UNSAFE_PATH, STORAGE_LIMIT,
    ROTATION_FAILED, USER_STOP, NETWORK_PROPERTIES_CHANGED, ACCESS_CONTEXT_CHANGED,
    SNAPSHOT_INVALIDATED, SIM_RETIRED, CANCEL_UNKNOWN,
    CAPABILITIES_MISSING, LINK_PROPERTIES_MISSING, NETWORK_BLOCKED, NO_INTERNET,
    INVALID_INTERFACE, ADDRESS_UNAVAILABLE, DNS_UNAVAILABLE, FAMILY_MISMATCH, LIMIT_REACHED,
    INVALID_RESPONSE, TIMEOUT, IDENTITY_MISMATCH, TARGET_RUNNING, TARGET_EXISTS,
    PROFILE_UNSUPPORTED, UNCONFIRMED, NOT_ACTIVE, PUBLISH_FAILED, INVALID_SLOT,
    SNAPSHOT_MISSING, VERSION_UNSUPPORTED, BOOT_MISMATCH, SNAPSHOT_STALE, SNAPSHOT_FUTURE,
    NO_CANDIDATE, SELECTOR_INVALID, NETWORK_UNREACHABLE, CONNECTION_REFUSED, BIND_FAILED, CAPACITY_EXHAUSTED,
}
enum class DiagnosticFamily { IPV4, IPV6, UNKNOWN }

data class DiagnosticEvent(
    val component: DiagnosticComponent,
    val level: DiagnosticLevel,
    val name: DiagnosticName,
    val stage: DiagnosticStage,
    val result: DiagnosticResult,
    val reason: DiagnosticReason = DiagnosticReason.NONE,
    val slot: Int? = null,
    val count: Int? = null,
    val accepted: Int? = null,
    val attempt: Int? = null,
    val networkGeneration: Long? = null,
    val family: DiagnosticFamily? = null,
    val sourceFamily: DiagnosticFamily? = null,
    val endpoint: String? = null,
) {
    internal fun valid(): Boolean = (slot == null || slot in 0..1) &&
        listOf(count, accepted, attempt).all { it == null || it in 0..1_000_000_000 } &&
        (networkGeneration == null || networkGeneration in 0..1_000_000_000_000L) &&
        (endpoint == null || endpoint.matches(Regex("[0-9a-f]{32}")))

    internal val priority: Boolean get() = result == DiagnosticResult.FAILURE ||
        result == DiagnosticResult.CANCELLED || name == DiagnosticName.RECOVERED || name == DiagnosticName.PROCESS_STOPPING
}

internal data class DiagnosticStats(
    val queued: Int, val queuedBytes: Int, val keys: Int, val dropped: Long,
    val evicted: Long, val coalesced: Long,
)

internal data class DiagnosticRecord(
    val event: DiagnosticEvent, val run: String, val pid: Int, val wall: Long, val mono: Long,
    val suppressed: Long = 0, val first: Long = mono, val last: Long = mono,
    val dropped: Long = 0, val evicted: Long = 0,
) {
    fun bytes(): ByteArray = (buildJsonObject {
        put("v", 1); put("wall_ms", wall); put("mono_ms", mono)
        put("run", run); put("pid", pid)
        put("component", event.component.name.lowercase()); put("level", event.level.name.lowercase())
        put("name", event.name.name.lowercase()); put("stage", event.stage.name.lowercase())
        put("result", event.result.name.lowercase()); put("reason", event.reason.name.lowercase())
        event.slot?.let { put("slot", it) }; event.count?.let { put("count", it) }
        event.accepted?.let { put("accepted", it) }; event.attempt?.let { put("attempt", it) }
        event.networkGeneration?.let { put("network_generation", it) }
        event.family?.let { put("family", it.name.lowercase()) }
        event.sourceFamily?.let { put("source_family", it.name.lowercase()) }
        event.endpoint?.let { put("endpoint", it) }
        put("suppressed", suppressed); put("first_mono_ms", first); put("last_mono_ms", last)
        put("dropped", dropped); put("evicted", evicted)
    }.toString() + "\n").encodeToByteArray()
}
