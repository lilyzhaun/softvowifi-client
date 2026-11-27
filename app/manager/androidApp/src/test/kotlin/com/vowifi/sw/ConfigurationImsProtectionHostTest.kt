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
class ConfigurationImsProtectionHostTest {
    @Test fun catalogProtectionSurvivesCallOnlySaveAndExplicitUserSaveClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.protectionSupported = true
            engine.catalogProtection = Json.parseToJsonElement(GROUP).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationImsProtection(), row.imsProtection)
                assertEquals("catalog", row.facts!!.imsProtection!!.source)
                assertNull(row.facts!!.imsProtection!!.overrideValue)
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_protection_saved"
                val saved = submit(fixture, overrides(callOnly.instance).copy(imsProtection = selected())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(selected(), saved.instance.imsProtection)
                assertEquals("user", saved.instance.facts!!.imsProtection!!.source)
                val clear = submit(fixture, overrides(saved.instance).copy(imsProtection = ConfigurationImsProtection())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationImsProtection(), clear.instance.imsProtection)
                assertEquals("catalog", clear.instance.facts!!.imsProtection!!.source)
                assertNull(clear.instance.facts!!.imsProtection!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["imsProtection"])
            }
        }
    }

    @Test fun nextStartUsesTheRealSourceRouteAndOnlyMatchingReadbackConfirms() = runBlocking {
        for (mode in listOf("module_protection_saved", "module_protection_mismatch", "normal")) NextCallSocketFixture().use { engine ->
            engine.protectionSupported = true
            engine.mode = mode
            NextCallCommandShadow.reset(engine)
            engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(imsProtection = selected()))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                val groups = NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject
                assertEquals(setOf("imsProtection"), groups.keys)
                assertEquals(Json.parseToJsonElement(GROUP), groups["imsProtection"])
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                when (mode) {
                    "module_protection_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals(selected(), confirmed.instance.imsProtection)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val clear = submit(fixture, overrides(confirmed.instance).copy(imsProtection = ConfigurationImsProtection()))
                        assertTrue(clear is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["imsProtection"])
                        assertEquals(ConfigurationImsProtection(), (clear as ConfigurationApplyOutcome.Confirmed).instance.imsProtection)
                    }
                    "module_protection_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun unchangedProtectionDoesNotContaminateCallWritesAndPoolRejectsHotUpdate() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.protectionSupported = true
            engine.protection = Json.parseToJsonElement(GROUP).jsonObject
            NextCallCommandShadow.reset(engine)
            engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"$INITIAL","overrides":{"imsProtection":$GROUP}}""") { true }.result)
            assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals(previous.imsProtection, (outcome as ConfigurationApplyOutcome.Confirmed).instance.imsProtection)
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unknownProtectionCannotBeReplacedWithoutAConfirmedClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine)
            engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(previous.imsProtection)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(previous).copy(imsProtection = selected())))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            }
        }
    }

    private fun selected() = ConfigurationImsProtectionCodec.decode(Json.parseToJsonElement(GROUP))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride,
        row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection)
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
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"suites":["esp-aes128-sha1-96","esp-null-sha1-96"]}"""
    }
}
