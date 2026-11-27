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
class ConfigurationTunnelIdentityHostTest {
    @Test fun catalogIdentityIsNotAManualEditorAndReturnsAfterClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.identitySupported = true
            engine.catalogIdentityPolicy = ConfigurationTunnelIdentityCodec.encode(policy()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationTunnelIdentity(), row.tunnelIdentity)
                assertEquals("catalog", row.facts!!.tunnelIdentity!!.source)
                assertFalse(row.facts.toString().contains("peer.example.invalid"))
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_identity_saved"
                val saved = submit(fixture, overrides(callOnly.instance).copy(tunnelIdentity = policy())) as ConfigurationApplyOutcome.Confirmed
                assertEquals("user", saved.instance.facts!!.tunnelIdentity!!.source)
                val cleared = submit(fixture, overrides(saved.instance).copy(tunnelIdentity = ConfigurationTunnelIdentity())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationTunnelIdentity(), cleared.instance.tunnelIdentity)
                assertEquals("catalog", cleared.instance.facts!!.tunnelIdentity!!.source)
                assertEquals(ConfigurationIkeIdType.FQDN, cleared.instance.facts!!.tunnelIdentity!!.value!!.peerType)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelIdentity"])
            }
        }
    }

    @Test fun nextStartSaveAndClearRequireAuthoritativeCompletePeerReadback() = runBlocking {
        for (mode in listOf("module_identity_saved", "module_identity_mismatch", "normal")) NextCallSocketFixture().use { engine ->
            engine.identitySupported = true; engine.mode = mode
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationTunnelIdentity(), row.tunnelIdentity)
                val outcome = submit(fixture, overrides(row).copy(tunnelIdentity = policy()))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(setOf("tunnelIdentity"), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, engine.methods.count { it in setOf("setConfig", "getClientCapabilities") })
                when (mode) {
                    "module_identity_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals(policy(), confirmed.instance.tunnelIdentity)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val cleared = submit(fixture, overrides(confirmed.instance).copy(tunnelIdentity = ConfigurationTunnelIdentity()))
                        assertTrue(cleared is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(ConfigurationTunnelIdentity(), (cleared as ConfigurationApplyOutcome.Confirmed).instance.tunnelIdentity)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelIdentity"])
                    }
                    "module_identity_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun callOnlyPreservesIdentityAndPoolRejectsHotReplacement() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.identitySupported = true; engine.identityPolicy = ConfigurationTunnelIdentityCodec.encode(policy()).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, engine.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"r1_abcdefghijklmnopabcdefghijklmnop","overrides":{"tunnelIdentity":null}}""") { true }.result)
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(policy(), row.tunnelIdentity)
                val outcome = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals(policy(), (outcome as ConfigurationApplyOutcome.Confirmed).instance.tunnelIdentity)
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unreadIdentityCannotBeReplacedBeforeExplicitClearAndReadback() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(row.tunnelIdentity)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(tunnelIdentity = policy())))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    private fun policy() = ConfigurationTunnelIdentity(ConfigurationTunnelIdentityPolicy(ConfigurationIkeIdType.KEY_ID, ConfigurationIkeIdType.FQDN,
        ConfigurationPeerIdentity(ConfigurationIkeIdType.FQDN, "peer.example.invalid")))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride, row.imsPolicy.digestRealmOverride,
        row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection, row.epdgAddressFamily, row.epdgSelection, row.tunnelIdentity)
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
