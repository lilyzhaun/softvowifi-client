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
class ConfigurationImsTimingHostTest {
    @Test fun catalogTimingSurvivesCallOnlySaveAndExplicitUserSaveClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.imsTimingSupported = true
            engine.catalogImsTiming = ConfigurationImsTimingCodec.encode(selected()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationImsTiming(), row.imsTiming)
                assertEquals("catalog", row.facts!!.imsTiming!!.source)
                assertNull(row.facts!!.imsTiming!!.overrideValue)
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_ims_timing_saved"
                val saved = submit(fixture, overrides(callOnly.instance).copy(imsTiming = selected())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(selected(), saved.instance.imsTiming)
                assertEquals("user", saved.instance.facts!!.imsTiming!!.source)
                val clear = submit(fixture, overrides(saved.instance).copy(imsTiming = ConfigurationImsTiming())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationImsTiming(), clear.instance.imsTiming)
                assertEquals("catalog", clear.instance.facts!!.imsTiming!!.source)
                assertNull(clear.instance.facts!!.imsTiming!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["imsTiming"])
            }
        }
    }

    @Test fun nextStartSaveAndClearRequireAuthoritativeWholePolicyReadback() = runBlocking {
        for (mode in listOf("module_ims_timing_saved", "module_ims_timing_mismatch", "normal")) NextCallSocketFixture().use { engine ->
            engine.imsTimingSupported = true; engine.mode = mode
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationImsTiming(), row.imsTiming)
                val result = submit(fixture, overrides(row).copy(imsTiming = selected()))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(setOf("imsTiming"), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
                when (mode) {
                    "module_ims_timing_saved" -> {
                        assertTrue(result is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = result as ConfigurationApplyOutcome.Confirmed
                        assertEquals(selected(), confirmed.instance.imsTiming)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val clear = submit(fixture, overrides(confirmed.instance).copy(imsTiming = ConfigurationImsTiming()))
                        assertTrue(clear is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(ConfigurationImsTiming(), (clear as ConfigurationApplyOutcome.Confirmed).instance.imsTiming)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["imsTiming"])
                    }
                    "module_ims_timing_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, result)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, result)
                }
            }
        }
    }

    @Test fun callOnlyPreservesTimingAndPoolRejectsHotReplacement() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.imsTimingSupported = true; engine.imsTiming = ConfigurationImsTimingCodec.encode(selected()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"r1_abcdefghijklmnopabcdefghijklmnop","overrides":{"imsTiming":null}}""") { true }.result)
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val result = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(result is ConfigurationApplyOutcome.Confirmed)
                assertEquals(selected(), (result as ConfigurationApplyOutcome.Confirmed).instance.imsTiming)
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unknownTimingCannotBeReplacedWithoutConfirmedClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(row.imsTiming)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(imsTiming = selected())))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    private fun selected() = ConfigurationImsTiming(ConfigurationImsTimingPolicy(registration = ConfigurationImsRegistrationTiming(2400, 60)))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride,
        row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection,
        row.epdgAddressFamily, row.epdgSelection, row.tunnelIdentity, row.imsTiming)
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
