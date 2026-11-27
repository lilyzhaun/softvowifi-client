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
class ConfigurationEsnHostTest {
    @Test fun observedEsnUsesExistingConnectionAndClearsInvalidOrRetiredFacts() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var esn: JsonElement = JsonPrimitive(true)
        var connected = true
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                val value = if (method == "getStatus") buildJsonObject {
                    put("tunnel", if (connected) "CONNECTED" else "BACKOFF")
                    put("configuration", buildJsonObject {
                        put("schema", 1)
                        put("loaded", buildJsonObject {
                            put("schema", 1); put("configRevision", REVISION); put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
                        })
                        put("negotiated", buildJsonObject {})
                        put("tunnel", buildJsonObject {
                            put("ike", Json.parseToJsonElement("""{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14}"""))
                            put("child", buildJsonObject { put("encryption", 20); put("keyBits", 128); put("integrity", 0); put("esn", esn); put("pfs", 14) })
                        })
                    })
                } else buildJsonObject { put("version", 1); put("pong", true) }
                socket.respond(request.getValue("id").jsonPrimitive.long, value)
            }
        }.use { pool ->
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET)); assertTrue(methods.isEmpty())
            pool.clientFor(S3_CONFIG_TARGET).request("ping")
            assertEquals(true, pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel?.child?.esn)
            for (bad in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) {
                esn = bad; assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            }
            esn = JsonPrimitive(false)
            assertEquals(false, pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel?.child?.esn)
            esn = JsonPrimitive(true); connected = false
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET)?.tunnel)
            pool.remove(S3_CONFIG_TARGET)?.close(); assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            assertEquals(1, methods.count { it == "hello" })
            assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
        }
    }

    @Test fun completeEsnCatalogUsesRealSourceSaveClearWithoutHotWrite() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.tunnelSupported = true
            engine.catalogTunnel = Json.parseToJsonElement(ESN).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val selected = ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(ESN))
                assertEquals(ConfigurationTunnelSecurity(), row.tunnelSecurity)
                assertEquals("catalog", row.facts!!.tunnelSecurity!!.source)
                assertEquals(selected, row.facts!!.tunnelSecurity!!.value)
                engine.mode = "module_tunnel_saved"
                val saved = submit(fixture, overrides(row).copy(tunnelSecurity = selected)) as ConfigurationApplyOutcome.Confirmed
                assertEquals(selected, saved.instance.tunnelSecurity)
                assertEquals(Json.parseToJsonElement(ESN), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["tunnelSecurity"])
                val clear = submit(fixture, overrides(saved.instance).copy(tunnelSecurity = ConfigurationTunnelSecurity())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationTunnelSecurity(), clear.instance.tunnelSecurity)
                assertEquals(selected, clear.instance.facts!!.tunnelSecurity!!.value)
                assertNull(clear.instance.facts!!.tunnelSecurity!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelSecurity"])
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            }
        }
    }

    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        expectedRevision = row.configRevision, callSignaling = row.callSignaling, callMedia = row.callMedia, tunnelSecurity = row.tunnelSecurity)

    private suspend fun submit(fixture: S3ConfigurationIoFixture, value: ConfigurationOverrides): ConfigurationApplyOutcome {
        val completed = CompletableDeferred<ConfigurationApplyOutcome>()
        assertEquals(ConfigurationApplyAcceptance.Accepted, fixture.source.submit(fixture.targetId, value) { completed.complete(it) })
        val scope = fixture.source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(fixture.source) as CoroutineScope
        withTimeout(4_000) { awaitS3ConfigurationProducer(scope, null) }
        return withTimeout(1_000) {
            while (!completed.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); yield() }
            completed.await()
        }
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val ESN = """{"ikeSuites":["ike-cbc256-sha256-prfsha256-dh14"],"childSuites":["esp-gcm128-esn"],"layout":"multiple-complete","childPFSGroup":14}"""
    }
}
