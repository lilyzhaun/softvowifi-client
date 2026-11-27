@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.os.Looper
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.configurationEditSummary
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ScalarReadbackCommandShadow::class, RootFrameworkShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data", "com.vowifi.sw.root"])
class ConfigurationScalarReadbackHostTest {
    @Test fun transportMismatchIsNotConfirmed() = mismatch("transport")
    @Test fun smscMismatchIsNotConfirmed() = mismatch("smsc")
    @Test fun epdgMismatchIsNotConfirmed() = mismatch("epdgFqdn")
    @Test fun realmMismatchIsNotConfirmed() = mismatch("imsRealm")
    @Test fun dnsMapMismatchIsNotConfirmed() = mismatch("dnsOverrides")
    @Test fun sipSecurityMismatchIsNotConfirmed() = mismatch("imsSipSecurity")
    @Test fun paniInitialMismatchIsNotConfirmed() = mismatch("paniInitial")
    @Test fun paniCountryMismatchIsNotConfirmed() = mismatch("paniCountry")
    @Test fun digestRealmMismatchIsNotConfirmed() = mismatch("imsDigestRealm")

    @Test fun refusedRunningSaveKeepsTheDraftAndExplainsWhyItWasNotWritten() = runBlocking {
        NextCallSocketFixture().use { engine ->
            ScalarReadbackCommandShadow.reset(engine)
            NextCallCommandShadow.running = true
            ScalarReadbackCommandShadow.resultCode = "BUSY"
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val draft = ConfigurationEditorDraft(row)
                draft.paniCountry.edit { replace(0, length, "FR") }
                val completed = CompletableDeferred<Unit>()
                draft.submit { id, patch, finish -> fixture.source.submit(id, patch) { result ->
                    finish(result)
                    completed.complete(Unit)
                } }
                val scope = fixture.source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(fixture.source) as CoroutineScope
                withTimeout(4000) { awaitS3ConfigurationProducer(scope, null) }
                withTimeout(1000) { while (!completed.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); yield() } }
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                assertTrue("the source must report idle admission, not unreadable configuration", ready.message.orEmpty().contains("空闲"))
                assertTrue(configurationEditSummary(draft).contains("未保存"))
                assertTrue(configurationEditSummary(draft).contains("空闲"))
                assertTrue(draft.dirty)
                assertFalse(draft.pending)
                assertEquals("FR", draft.paniCountry.text.toString())
                assertEquals(row.configRevision, ready.instances.single { it.instanceId == fixture.targetId }.configRevision)
                assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                assertEquals(1, ScalarReadbackCommandShadow.writes)
                assertEquals(setOf("paniCountry"), ScalarReadbackCommandShadow.payloads.single().keys)
            }
        }
    }

    @Test fun matchingSetAndClearReadbacksConfirmAllScalarChoices() = runBlocking {
        for (field in FIELDS) NextCallSocketFixture().use { engine ->
            ScalarReadbackCommandShadow.reset(engine)
            S3ConfigurationIoFixture().use { fixture ->
                val before = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val original = overrides(before)
                val result = submit(fixture, choice(field, original)) as ConfigurationApplyOutcome.Confirmed
                assertEquals(field, ConfigurationApplyEffect.Module, result.effect)
                assertEquals(field, setOf(field), ScalarReadbackCommandShadow.payloads.single().keys)
                val restored = submit(fixture, original.copy(expectedRevision = result.instance.configRevision))
                    as ConfigurationApplyOutcome.Confirmed
                assertEquals(field, original.copy(expectedRevision = restored.instance.configRevision), overrides(restored.instance))
                assertEquals(field, 2, ScalarReadbackCommandShadow.writes)
                assertEquals(field, 0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            }
        }
    }

    @Test fun oneSavedCallGroupCannotHideAnUnappliedScalarInAMixedSubmission() = runBlocking {
        NextCallSocketFixture().use { engine ->
            ScalarReadbackCommandShadow.reset(engine)
            NextCallCommandShadow.running = true
            ScalarReadbackCommandShadow.omitted = "smsc"
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val chosen = overrides(row).copy(smsc = "+15550000002", callSignaling = row.callSignaling!!.copy(includePANI = false))
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, chosen))
                assertEquals(setOf("smsc", "callSignaling"), ScalarReadbackCommandShadow.payloads.single().keys)
                assertEquals(1, ScalarReadbackCommandShadow.writes)
                assertFalse((fixture.ready().instances.single { it.instanceId == fixture.targetId }).callSignaling!!.includePANI!!)
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            }
        }
    }

    @Test fun equalEffectivePolicyStillRequiresTheSubmittedUserSource() = runBlocking {
        for (field in POLICY_FIELDS) NextCallSocketFixture().use { engine ->
            ScalarReadbackCommandShadow.reset(engine)
            ScalarReadbackCommandShadow.sourceOnlyMismatch = field
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertSame(field, ConfigurationApplyOutcome.Unconfirmed, submit(fixture, choice(field, overrides(row))))
                assertEquals(field, 1, ScalarReadbackCommandShadow.writes)
            }
        }
    }

    @Test fun clearingAUserPolicyIsNotConfirmedByAnUnknownReadbackSource() = runBlocking {
        for (field in POLICY_FIELDS) NextCallSocketFixture().use { engine ->
            ScalarReadbackCommandShadow.reset(engine)
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val original = overrides(row)
                val saved = submit(fixture, choice(field, original)) as ConfigurationApplyOutcome.Confirmed
                ScalarReadbackCommandShadow.sourceOnlyMismatch = field
                ScalarReadbackCommandShadow.unknownSource = true
                assertSame(field, ConfigurationApplyOutcome.Unconfirmed,
                    submit(fixture, original.copy(expectedRevision = saved.instance.configRevision)))
                assertEquals(2, ScalarReadbackCommandShadow.writes)
            }
        }
    }

    private fun mismatch(field: String) = runBlocking {
        for (code in listOf("APPLIED", "UNCHANGED")) for (newRevision in listOf(false, true)) {
            NextCallSocketFixture().use { engine ->
                ScalarReadbackCommandShadow.reset(engine)
                ScalarReadbackCommandShadow.omitted = field
                ScalarReadbackCommandShadow.resultCode = code
                ScalarReadbackCommandShadow.changeRevision = newRevision
                S3ConfigurationIoFixture().use { fixture ->
                    val before = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                    val result = submit(fixture, choice(field, overrides(before)))
                    assertSame("$field / $code / newRevision=$newRevision", ConfigurationApplyOutcome.Unconfirmed, result)
                    assertEquals(setOf(field), ScalarReadbackCommandShadow.payloads.single().keys)
                    assertEquals(1, ScalarReadbackCommandShadow.writes)
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    assertEquals("refresh must not replay an unknown save", 1, ScalarReadbackCommandShadow.writes)
                }
            }
        }
    }

    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn,
        row.imsRealm, row.dns, row.imsPolicy.securityOverride, row.imsPolicy.initialOverride,
        row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride, row.dnsOverrides,
        expectedRevision = row.configRevision, callSignaling = row.callSignaling)

    private fun choice(field: String, base: ConfigurationOverrides): ConfigurationOverrides = when (field) {
        "transport" -> base.copy(transport = ConfigurationTransport.WIFI)
        "smsc" -> base.copy(smsc = "+15550000002")
        "epdgFqdn" -> base.copy(epdgFqdn = "selected.epdg.example.test")
        "imsRealm" -> base.copy(imsRealm = "selected.ims.example.test")
        "dnsOverrides" -> base.copy(dns = "203.0.113.5", dnsOverrides = mapOf("wifi" to "2001:db8::53"))
        "imsSipSecurity" -> base.copy(imsSipSecurity = "swu_udp_aka")
        "paniInitial" -> base.copy(paniInitial = "wlan-country")
        "paniCountry" -> base.copy(paniCountry = " us ")
        "imsDigestRealm" -> base.copy(imsDigestRealm = "digest.example.test")
        else -> error("unknown test field")
    }

    private suspend fun submit(fixture: S3ConfigurationIoFixture, value: ConfigurationOverrides): ConfigurationApplyOutcome {
        val completion = CompletableDeferred<ConfigurationApplyOutcome>()
        assertEquals(ConfigurationApplyAcceptance.Accepted, fixture.source.submit(fixture.targetId, value) { completion.complete(it) })
        val scope = fixture.source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(fixture.source) as CoroutineScope
        withTimeout(4000) { awaitS3ConfigurationProducer(scope, null) }
        return withTimeout(1000) {
            while (!completion.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); yield() }
            completion.await()
        }
    }

    private companion object {
        val POLICY_FIELDS = listOf("imsSipSecurity", "paniInitial", "paniCountry", "imsDigestRealm")
        val FIELDS = listOf("transport", "smsc", "epdgFqdn", "imsRealm", "dnsOverrides") + POLICY_FIELDS
    }
}
