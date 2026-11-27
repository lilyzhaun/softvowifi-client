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
class ConfigurationEpdgSelectionHostTest {
    @Test fun catalogInheritanceSurvivesCallOnlySaveAndReturnsAfterExplicitClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.selectionSupported = true
            engine.catalogSelection = ConfigurationEpdgSelectionCodec.encode(policy()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationEpdgSelection(), row.epdgSelection)
                assertEquals("catalog", row.facts!!.epdgSelection!!.source)
                assertEquals(policy(), row.facts!!.epdgSelection!!.value)
                assertNull(row.facts!!.epdgSelection!!.overrideValue)
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(callOnly is ConfigurationApplyOutcome.Confirmed)
                val current = (callOnly as ConfigurationApplyOutcome.Confirmed).instance
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_selection_saved"
                val manual = submit(fixture, overrides(current).copy(epdgSelection = policy())) as ConfigurationApplyOutcome.Confirmed
                assertEquals("user", manual.instance.facts!!.epdgSelection!!.source)
                val cleared = submit(fixture, overrides(manual.instance).copy(epdgSelection = ConfigurationEpdgSelection())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationEpdgSelection(), cleared.instance.epdgSelection)
                assertEquals("catalog", cleared.instance.facts!!.epdgSelection!!.source)
                assertEquals(policy(), cleared.instance.facts!!.epdgSelection!!.value)
                assertNull(cleared.instance.facts!!.epdgSelection!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["epdgSelection"])
            }
        }
    }

    @Test fun fullPolicyUsesNextStartAndRequiresMatchingAuthoritativeReadback() = runBlocking {
        for (mode in listOf("module_selection_saved", "module_selection_mismatch", "normal")) NextCallSocketFixture().use { engine ->
            engine.selectionSupported = true; engine.mode = mode
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationEpdgSelection(), row.epdgSelection)
                val outcome = submit(fixture, overrides(row).copy(epdgSelection = policy()))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(setOf("epdgSelection"), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
                when (mode) {
                    "module_selection_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals(policy(), confirmed.instance.epdgSelection)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val cleared = submit(fixture, overrides(confirmed.instance).copy(epdgSelection = ConfigurationEpdgSelection()))
                        assertTrue(cleared is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(ConfigurationEpdgSelection(), (cleared as ConfigurationApplyOutcome.Confirmed).instance.epdgSelection)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["epdgSelection"])
                    }
                    "module_selection_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun unchangedPolicyDoesNotPolluteCallOnlyAndRuntimePoolRejectsHotReplacement() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.selectionSupported = true; engine.selection = ConfigurationEpdgSelectionCodec.encode(policy()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"r1_abcdefghijklmnopabcdefghijklmnop","overrides":{"epdgSelection":null}}""") { true }.result)
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(policy(), row.epdgSelection)
                val outcome = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals(policy(), (outcome as ConfigurationApplyOutcome.Confirmed).instance.epdgSelection)
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unknownPolicyCannotBeOverwrittenByAnApparentlyCompleteGroup() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(row.epdgSelection)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(epdgSelection = policy())))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
            }
        }
    }

    private fun policy() = ConfigurationEpdgSelection(listOf(ConfigurationEpdgCandidate(ConfigurationEpdgKind.HOME_PLMN)))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride,
        row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection, row.epdgAddressFamily, row.epdgSelection)
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
