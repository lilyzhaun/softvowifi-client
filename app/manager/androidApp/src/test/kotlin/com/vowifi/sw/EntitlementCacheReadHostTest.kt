@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class EntitlementCacheReadHostTest {
    private val scope = EntitlementReadContext("https://reader.example.invalid/local", "Vendor", "Model", "Software")
    private val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
    private val epoch = Instant.parse("2030-01-01T00:00:00Z")
    private val miss = """{"ok":true,"source":"cache","hit":false}"""
    private fun ack(s: String) = AndroidSuResult(false, 0, s, false)
    private fun hit(state: String, expires: String = "2030-01-01T00:00:01Z", more: String = "") =
        """{"hit":true,"ok":true,"source":"cache","status":{"state":"$state","behavior":"valid","version":3,"validUntil":"$expires"$more}}"""

    @Test fun realSourceUsesOneReadOnlyV2RequestAndRejectsAnAttachmentChange() = runBlocking {
        var reads = 0; var ownerReads = 0; var changePort = false
        val manager = RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
        val reader = AndroidEntitlementCacheRead({ EntitlementShell({ command, stdin ->
            if (stdin == null) {
                assertEquals("printf '%s:%s' \"\$VOWIFI_OWNER_PID\" \"\$VOWIFI_OWNER_START\"", command)
                ownerReads++; ack("123:456")
            } else {
                assertEquals("exec \"\$ENGINE_BIN\" entitlement-query \"\$VOWIFI_DATA_DIR/config/$S3_CONFIG_TARGET.json\"", command)
                val req = JSONObject(stdin)
                assertEquals(setOf("version","operation","rootOwner","expectedRevision","server","terminalVendor","terminalModel","terminalSoftware"), req.keys().asSequence().toSet())
                assertEquals(2, req.getInt("version")); assertEquals("read-cache",req.getString("operation"))
                assertEquals("123:456",req.getString("rootOwner")); assertEquals(revision,req.getString("expectedRevision"))
                assertEquals(listOf(scope.server,scope.vendor,scope.model,scope.software),listOf("server","terminalVendor","terminalModel","terminalSoftware").map(req::getString))
                reads++
                if (changePort) ReflectionHelpers.setField(manager.activeSubscriptionInfoList!!.single(), "mPortIndex", 1)
                ack(miss)
            }
        }, { true }) }, { epoch })
        AutomaticSmscCommandShadow.running = false
        S3ConfigurationIoFixture(entitlementRead = reader).use { f ->
            f.ready(); ReflectionHelpers.setField(manager.activeSubscriptionInfoList!!.single(), "mPortIndex", 0)
            f.source.refreshAndAwait()
            val row = (f.source.state.value as ConfigurationUiState.Ready).instances.single()
            val read = requireNotNull(f.source.entitlementCacheReader)
            assertEquals(EntitlementCacheResult.Unconfirmed, read(row.instanceId, "old", scope))
            assertEquals(0, ownerReads); assertEquals(0, reads)
            assertEquals(EntitlementCacheResult.Missing, read(row.instanceId, revision, scope))
            changePort = true
            assertEquals(EntitlementCacheResult.Unconfirmed, read(row.instanceId, revision, scope))
            assertEquals(EntitlementCacheResult.Unconfirmed, read(row.instanceId, revision, scope))
            assertEquals(2, ownerReads); assertEquals(2, reads)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun onlyAnUnexpiredLocalReceiptOfTheCapturedRootCanBeDisplayed() {
        var current = true; var drop = false; var calls = 0; var reply = ack(miss)
        val reader = AndroidEntitlementCacheRead({ EntitlementShell({ _, input ->
            calls++
            if (input == null) ack("123:456") else { if (drop) current = false; reply }
        }, { current }) }, { epoch })
        fun read(s: EntitlementReadContext = scope) = reader.read(S3_CONFIG_TARGET, revision, s) { true }
        assertEquals(EntitlementCacheResult.InvalidInput, read(EntitlementReadContext("https://user:pass@example.invalid/x", "x","y","z")))
        assertEquals(0, calls)
        for ((state, expected) in listOf("entitled" to EntitlementServiceState.Entitled, "not-entitled" to EntitlementServiceState.NotEntitled,
            "in-progress" to EntitlementServiceState.InProgress, "action-required" to EntitlementServiceState.ActionRequired)) {
            val input = if (state == "action-required") ",\"userInput\":{\"terms\":true,\"address\":false,\"httpsFormReported\":true}" else ""
            reply = ack(hit(state, more=input)); val result = read() as EntitlementCacheResult.Observed
            assertEquals(expected,result.state); assertEquals(state=="action-required",result.needsTerms); assertFalse(result.needsAddress)
        }
        for (bad in listOf(hit("entitled", epoch.toString()), hit("other"), hit("entitled",more=",\"version\":4"),
            hit("entitled",more=",\"tokenUnlimited\":true"), miss.replace("false","\"false\""), miss.replace("cache","network"),
            miss.replace("false","false,\"hit\":true"), miss.replace("false","true"), miss+miss)) {
            reply = ack(bad); assertEquals(EntitlementCacheResult.Unconfirmed,read())
        }
        for (bad in listOf(ack(miss).copy(exitCode=1),ack(miss).copy(overflowed=true),ack(miss).copy(writeFailed=true))) {
            reply = bad; assertEquals(EntitlementCacheResult.Unconfirmed,read())
        }
        reply = ack(hit("entitled")); drop = true; assertEquals(EntitlementCacheResult.Unconfirmed,read())
        val atLoss = calls; assertEquals(EntitlementCacheResult.Unconfirmed,read()); assertEquals(atLoss,calls)
    }

    @Test fun losingRootDuringTheFinalAttachmentReadCannotPublishItsHit() {
        var live = true; var answered = false
        val reader = AndroidEntitlementCacheRead({ EntitlementShell({ _, stdin ->
            if (stdin == null) ack("123:456") else { answered = true; ack(hit("entitled")) }
        }, { live }) }, { epoch })
        val result = reader.read(S3_CONFIG_TARGET, revision, scope) {
            if (answered) live = false // The replacement service returns the same slot and port.
            true
        }
        assertTrue(answered); assertFalse(live); assertEquals(EntitlementCacheResult.Unconfirmed, result)
    }

    @Test fun nonUtcRfc3339OffsetsAreAcceptedAndNormalizedWhileMissingOffsetOrExpiredStillReject() {
        var reply = ack(miss)
        val reader = AndroidEntitlementCacheRead({ EntitlementShell({ _, input ->
            if (input == null) ack("123:456") else reply
        }, { true }) }, { epoch })
        fun read() = reader.read(S3_CONFIG_TARGET, revision, scope) { true }

        // Same instant as 2030-01-01T00:00:01Z formatted in +08:00 and -05:00 local timezones.
        for (offsetExpiry in listOf("2030-01-01T08:00:01+08:00", "2029-12-31T19:00:01-05:00")) {
            reply = ack(hit("entitled", expires = offsetExpiry))
            val observed = read() as? EntitlementCacheResult.Observed
            assertNotNull("RFC 3339 offset $offsetExpiry must be accepted", observed)
            assertEquals(EntitlementServiceState.Entitled, observed!!.state)
            assertEquals("2030-01-01T00:00:01Z", observed.validUntil)
        }
        // Expired instant in +08:00 (equals epoch 2030-01-01T00:00:00Z) and timestamp without timezone offset must still reject.
        for (rejected in listOf("2030-01-01T08:00:00+08:00", "2030-01-01T00:00:01")) {
            reply = ack(hit("entitled", expires = rejected))
            assertEquals("invalid/expired timestamp $rejected must stay Unconfirmed", EntitlementCacheResult.Unconfirmed, read())
        }
    }
}
