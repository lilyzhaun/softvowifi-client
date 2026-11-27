@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.CopyOnWriteArrayList

// Only the cross-process Root receipts are simulated. Source, commands, codec,
// catalog, evaluator and asynchronous submission completion are production code.
@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class ScalarReadbackCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        val result: AndroidSuResult = NextCallCommandShadow().run(command)
        if (command != ConfigurationControlCommand.showLine(S3_CONFIG_TARGET)) return result
        return result.copy(output = JsonObject(Json.parseToJsonElement(result.output).jsonObject + saved).toString())
    }

    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertEquals(ConfigurationControlCommand.applyLine(S3_CONFIG_TARGET), command)
        assertEquals(ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS, timeoutSeconds)
        writes++
        val changes = Json.parseToJsonElement(stdinText).jsonObject.getValue("overrides").jsonObject
        payloads += changes
        if (resultCode == "BUSY") return AndroidSuResult(false, 9,
            """{"ok":false,"code":"BUSY","message":"software business idle state not confirmed"}""", false)
        for ((key, value) in changes) {
            if (key == omitted) continue
            if (key == "callSignaling") {
                engine.explicit = value as? JsonObject ?: buildJsonObject {}
            } else if (key in POLICY_DEFAULTS) {
                val clear = value.jsonPrimitive.content.isEmpty()
                saved[key] = if (clear) JsonPrimitive(POLICY_DEFAULTS.getValue(key)) else value
                saved[key + "Source"] = JsonPrimitive(when {
                    key == sourceOnlyMismatch && unknownSource -> "unrecognized-source"
                    clear || key == sourceOnlyMismatch -> sourceFor(key)
                    else -> "user"
                })
            } else saved[key] = value
        }
        if (changeRevision) engine.revision = if (engine.revision == INITIAL) NEXT else INITIAL
        return AndroidSuResult(false, if (resultCode == "UNCHANGED") 3 else 0,
            """{"ok":true,"code":"$resultCode","message":"saved"}""", false)
    }

    companion object {
        lateinit var engine: NextCallSocketFixture
        private val saved = mutableMapOf<String, JsonElement>()
        val payloads = CopyOnWriteArrayList<JsonObject>()
        var writes = 0
        var omitted: String? = null
        var resultCode = "APPLIED"
        var changeRevision = true
        var sourceOnlyMismatch: String? = null
        var unknownSource = false
        private const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        private const val NEXT = "r1_ponmlkjihgfedcbaponmlkjihgfedcba"
        private val POLICY_DEFAULTS = mapOf("imsSipSecurity" to "tcp_ipsec_3gpp", "paniInitial" to "absent",
            "paniCountry" to "", "imsDigestRealm" to "home.card.test")
        private fun sourceFor(key: String) = when (key) {
            "paniCountry" -> "home_mcc"
            "imsDigestRealm" -> "identity"
            else -> "default"
        }
        fun reset(value: NextCallSocketFixture) {
            engine = value
            NextCallCommandShadow.reset(value)
            NextCallCommandShadow.running = false
            saved.clear(); payloads.clear(); writes = 0
            omitted = null; resultCode = "APPLIED"; changeRevision = true
            sourceOnlyMismatch = null; unknownSource = false
        }
    }
}
