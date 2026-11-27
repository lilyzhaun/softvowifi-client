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
class ConfigurationAddressFamilyHostTest {
    @Test fun savedFamilyUsesNextStartAndOnlyMatchingAuthoritativeReadbackConfirms() = runBlocking {
        for (mode in listOf("module_family_saved", "module_family_mismatch", "normal")) NextCallSocketFixture().use { engine ->
            engine.addressFamilySupported = true; engine.mode = mode
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals("", row.epdgAddressFamily)
                val outcome = submit(fixture, overrides(row).copy(epdgAddressFamily = "ipv4-only"))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(JsonObject(mapOf("epdgAddressFamily" to JsonPrimitive("ipv4-only"))), NextCallCommandShadow.modulePayloads.single()["overrides"])
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
                when (mode) {
                    "module_family_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals("ipv4-only", confirmed.instance.epdgAddressFamily)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val clear = submit(fixture, overrides(confirmed.instance).copy(epdgAddressFamily = ""))
                        assertTrue(clear is ConfigurationApplyOutcome.Confirmed)
                        assertEquals("", (clear as ConfigurationApplyOutcome.Confirmed).instance.epdgAddressFamily)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["epdgAddressFamily"])
                    }
                    "module_family_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun unchangedFamilyPreservesCallOnlyRouteAndPoolRejectsFamilyHotUpdate() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.addressFamilySupported = true; engine.addressFamily = "ipv6-only"
            NextCallCommandShadow.reset(engine); engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"r1_abcdefghijklmnopabcdefghijklmnop","overrides":{"epdgAddressFamily":"ipv4-only"}}""") { true }.result)
            assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals("ipv6-only", (outcome as ConfigurationApplyOutcome.Confirmed).instance.epdgAddressFamily)
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unknownFamilyCannotBeOverwrittenOrTreatedAsCompatibility() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(row.epdgAddressFamily)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(epdgAddressFamily = "ipv4-only")))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
            }
        }
    }

    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride,
        row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection, row.epdgAddressFamily)
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
}
