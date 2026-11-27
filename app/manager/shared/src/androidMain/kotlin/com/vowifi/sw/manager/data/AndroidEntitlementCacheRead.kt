package com.vowifi.sw.manager.data

import android.util.JsonReader
import android.util.JsonToken
import com.vowifi.sw.manager.model.EntitlementCacheResult
import com.vowifi.sw.manager.model.EntitlementReadContext
import com.vowifi.sw.manager.model.EntitlementServiceState
import com.vowifi.sw.root.RootBridge
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class EntitlementShell(val run: (String, String?) -> AndroidSuResult, val current: () -> Boolean)

internal class AndroidEntitlementCacheRead(
    private val capture: () -> EntitlementShell? = {
        RootBridge.captureCommandSession()?.let { session ->
            val shell = AndroidSuCommand(timeoutSeconds = 10, maxBytes = 4096, start = session::start)
            EntitlementShell({ line, input -> if (input == null) shell.run(line) else shell.run(line, input) }, session::isCurrent)
        }
    },
    private val now: () -> Instant = Instant::now,
) {
    /** Invoke on the cancellable IO command thread. No refresh, SIM opening or network fallback. */
    fun read(key: String, revision: String, input: EntitlementReadContext, attached: () -> Boolean): EntitlementCacheResult {
        val unknown = EntitlementCacheResult.Unconfirmed
        if (!validSource(input)) return EntitlementCacheResult.InvalidInput
        if (key.length !in 10..32 || !ConfigurationControlCommand.digitsOnly(key) || !validConfigRevision(revision) || !attached()) return unknown
        val shell = capture() ?: return unknown
        return try {
            if (!shell.current()) return unknown
            val owner = shell.run("printf '%s:%s' \"\$VOWIFI_OWNER_PID\" \"\$VOWIFI_OWNER_START\"", null).successfulText()
                ?.takeIf { OWNER.matches(it) } ?: return unknown
            val payload = buildJsonObject {
                put("version", 2); put("operation", "read-cache"); put("rootOwner", owner); put("expectedRevision", revision)
                put("server", input.server); put("terminalVendor", input.vendor); put("terminalModel", input.model); put("terminalSoftware", input.software)
            }.toString()
            if (payload.toByteArray(Charsets.UTF_8).size > 8192) return EntitlementCacheResult.InvalidInput
            if (!attached() || !shell.current()) return unknown
            val reply = shell.run("exec \"\$ENGINE_BIN\" entitlement-query \"\$VOWIFI_DATA_DIR/config/$key.json\"", payload).successfulText() ?: return unknown
            if (!attached()) return unknown
            val result = decodeReceipt(reply, now())
            if (shell.current()) result else unknown
        } catch (_: IOException) { unknown }
    }

    private fun validSource(input: EntitlementReadContext): Boolean {
        if (listOf(input.server, input.vendor, input.model, input.software).any { v ->
                v.isBlank() || v.toByteArray(Charsets.UTF_8).size > 2048 || v.any { it.isISOControl() }
            }) return false
        return try {
            val uri = URI(input.server)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        } catch (_: URISyntaxException) { false }
    }

    private companion object { val OWNER = Regex("[1-9][0-9]{0,9}:[1-9][0-9]{0,19}") }
}

private fun AndroidSuResult.successfulText(): String? = output.takeIf { !timedOut && !overflowed && !writeFailed && exitCode == 0 }

private fun decodeReceipt(text: String, now: Instant): EntitlementCacheResult = try {
    JsonReader(StringReader(text)).use { json ->
        var ok = false; var cache = false; var hit: Boolean? = null; var status: EntitlementCacheResult.Observed? = null
        json.fields { name -> when (name) {
            "ok" -> ok = json.bool(); "source" -> cache = json.string() == "cache"; "hit" -> hit = json.bool()
            "status" -> status = json.observation(now)
            else -> throw IllegalArgumentException()
        } }
        require(ok && cache && hit != null && hit == (status != null) && json.peek() == JsonToken.END_DOCUMENT)
        status ?: EntitlementCacheResult.Missing
    }
} catch (_: IllegalArgumentException) { EntitlementCacheResult.Unconfirmed }
  catch (_: IllegalStateException) { EntitlementCacheResult.Unconfirmed }
  catch (_: DateTimeException) { EntitlementCacheResult.Unconfirmed }
  catch (_: IOException) { EntitlementCacheResult.Unconfirmed }

private fun JsonReader.observation(now: Instant): EntitlementCacheResult.Observed {
    var state: EntitlementServiceState? = null; var valid = false; var version = 0L; var expires: Instant? = null
    var terms = false; var address = false; var hasInput = false
    fields { name -> when (name) {
        "state" -> state = when (string()) {
            "entitled" -> EntitlementServiceState.Entitled; "not-entitled" -> EntitlementServiceState.NotEntitled
            "action-required" -> EntitlementServiceState.ActionRequired; "in-progress" -> EntitlementServiceState.InProgress
            else -> throw IllegalArgumentException()
        }
        "behavior" -> valid = string() == "valid"
        "version" -> { require(peek() == JsonToken.NUMBER); version = nextString().toLong() }
        "validUntil" -> expires = OffsetDateTime.parse(string()).toInstant()
        "userInput" -> {
            hasInput = true
            val keys = fields { k -> when (k) {
                "terms" -> terms = bool(); "address" -> address = bool(); "httpsFormReported" -> { bool(); Unit }
                else -> throw IllegalArgumentException()
            } }
            require(keys.size == 3 && (terms || address))
        }
        else -> throw IllegalArgumentException()
    } }
    val kind = requireNotNull(state); val until = requireNotNull(expires)
    require(valid && version > 0 && now.isBefore(until) && (!hasInput || kind == EntitlementServiceState.ActionRequired))
    return EntitlementCacheResult.Observed(kind, until.toString(), terms, address)
}

private inline fun JsonReader.fields(read: (String) -> Unit): Set<String> {
    beginObject(); val seen = mutableSetOf<String>()
    while (hasNext()) { val key = nextName(); require(seen.add(key)); read(key) }
    endObject(); return seen
}
private fun JsonReader.bool(): Boolean { require(peek() == JsonToken.BOOLEAN); return nextBoolean() }
private fun JsonReader.string(): String { require(peek() == JsonToken.STRING); return nextString() }
