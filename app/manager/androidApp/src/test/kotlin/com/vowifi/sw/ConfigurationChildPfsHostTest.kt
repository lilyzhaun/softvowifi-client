@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.os.Looper
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NextCallCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data", "com.vowifi.sw.root"])
class ConfigurationChildPfsHostTest {
    @Test fun inheritedPfsSurvivesCallOnlySaveAndExplicitWholeGroupSaveClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.tunnelSupported = true
            engine.catalogTunnel = Json.parseToJsonElement(PFS).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationTunnelSecurity(), row.tunnelSecurity)
                assertEquals("catalog", row.facts!!.tunnelSecurity!!.source)
                assertNull(row.facts!!.tunnelSecurity!!.overrideValue)
                assertEquals(selected(), row.facts!!.tunnelSecurity!!.value)
                val call = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_tunnel_saved"
                val saved = submit(fixture, overrides(call.instance).copy(tunnelSecurity = selected())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(selected(), saved.instance.tunnelSecurity)
                assertEquals("user", saved.instance.facts!!.tunnelSecurity!!.source)
                assertEquals(Json.parseToJsonElement(PFS), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["tunnelSecurity"])
                val clear = submit(fixture, overrides(saved.instance).copy(tunnelSecurity = ConfigurationTunnelSecurity())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationTunnelSecurity(), clear.instance.tunnelSecurity)
                assertEquals("catalog", clear.instance.facts!!.tunnelSecurity!!.source)
                assertEquals(selected(), clear.instance.facts!!.tunnelSecurity!!.value)
                assertNull(clear.instance.facts!!.tunnelSecurity!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelSecurity"])
            }
        }
    }

    @Test fun pfsOnlyMismatchCannotConfirmAndThePoolCannotHotUpdateIt() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.tunnelSupported = true
            engine.tunnel = Json.parseToJsonElement(LEGACY).jsonObject
            engine.mode = "module_tunnel_mismatch"
            NextCallCommandShadow.reset(engine); engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"$REVISION","overrides":{"tunnelSecurity":$PFS}}""") { true }.result)
            assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(LEGACY)), row.tunnelSecurity)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(tunnelSecurity = selected())))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(Json.parseToJsonElement(PFS), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["tunnelSecurity"])
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            }
        }
    }

    @Test fun currentConnectionDecodesObservedPfsAndClearsMalformedOrRetiredFacts() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var group: JsonElement = JsonPrimitive(14)
        var connected = true
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                socket.respond(request.getValue("id").jsonPrimitive.long, if (method == "getStatus") status(group, connected)
                    else buildJsonObject { put("version", 1); put("pong", true) })
            }
        }.use { pool ->
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            assertTrue(methods.isEmpty())
            pool.clientFor(S3_CONFIG_TARGET).request("ping")
            assertEquals(14, pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel?.child?.pfs)
            group = JsonPrimitive(0)
            assertEquals(0, pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel?.child?.pfs)
            for (bad in listOf(JsonPrimitive(19), JsonPrimitive("14"), JsonNull)) {
                group = bad
                assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            }
            group = JsonPrimitive(14); connected = false
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel)
            pool.remove(S3_CONFIG_TARGET)?.close()
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            assertEquals(1, methods.count { it == "hello" })
            assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
        }
    }

    private fun selected() = ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(PFS))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        expectedRevision = row.configRevision, callSignaling = row.callSignaling, callMedia = row.callMedia, tunnelSecurity = row.tunnelSecurity)

    private suspend fun submit(fixture: S3ConfigurationIoFixture, desired: ConfigurationOverrides): ConfigurationApplyOutcome {
        val completed = CompletableDeferred<ConfigurationApplyOutcome>()
        assertEquals(ConfigurationApplyAcceptance.Accepted, fixture.source.submit(fixture.targetId, desired) { completed.complete(it) })
        val scope = fixture.source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(fixture.source) as CoroutineScope
        withTimeout(4_000) { awaitS3ConfigurationProducer(scope, null) }
        return withTimeout(1_000) {
            while (!completed.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); yield() }
            completed.await()
        }
    }

    private fun status(group: JsonElement, connected: Boolean) = buildJsonObject {
        put("tunnel", if (connected) "CONNECTED" else "BACKOFF")
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            })
            put("negotiated", buildJsonObject {})
            put("tunnel", buildJsonObject {
                put("ike", Json.parseToJsonElement("""{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14}"""))
                put("child", buildJsonObject {
                    put("encryption", 20); put("keyBits", 128); put("integrity", 0); put("esn", false); put("pfs", group)
                })
            })
        })
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val LEGACY = """{"ikeSuites":["ike-cbc256-sha256-prfsha256-dh14"],"childSuites":["esp-gcm128-noesn"],"layout":"multiple-complete"}"""
        val PFS = LEGACY.dropLast(1) + ",\"childPFSGroup\":14}"
    }
}
