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
class ConfigurationSwuAccessSourceHostTest {
    @Test fun actualSourceRetainsInnerProtocolFactsAndConfirmsTheWholeUpdatedGroup() = runBlocking {
        NextCallSocketFixture().use { engine ->
            val group = GROUP.dropLast(1) + """, "innerProtocol":{"home":"ipv4","roaming":"ipv6"}}"""
            engine.swuAccessSupported = true
            NextCallCommandShadow.reset(engine); NextCallCommandShadow.running = false
            engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val initial = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationSwuAccess(), initial.swuAccess)
                engine.swuAccess = Json.parseToJsonElement(group).jsonObject
                assertEquals(RefreshResult.Completed, withTimeout(3000) { fixture.source.refreshAndAwait() })
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val value = row.swuAccess
                assertNotNull("actual source lost inner protocols after authoritative refresh", value)
                assertEquals(Json.parseToJsonElement(group), ConfigurationSwuAccessCodec.encode(value!!))
                val updated = group.replace("\"roaming\":\"ipv6\"", "\"roaming\":\"dual-stack\"")
                val chosen = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(updated))
                engine.mode = "module_access_saved"
                val saved = submit(fixture, overrides(row).copy(swuAccess = chosen)) as ConfigurationApplyOutcome.Confirmed
                assertEquals(chosen, saved.instance.swuAccess)
                assertEquals(ConfigurationApplyEffect.Module, saved.effect)
                assertEquals(Json.parseToJsonElement(updated), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["swuAccess"])
                val cleared = submit(fixture, overrides(saved.instance).copy(swuAccess = ConfigurationSwuAccess())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationSwuAccess(), cleared.instance.swuAccess)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["swuAccess"])
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                assertEquals(2, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unchangedApnAndMtuCannotHideMismatchedInnerProtocolReadback() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.swuAccessSupported = true
            engine.swuAccess = Json.parseToJsonElement(GROUP).jsonObject
            NextCallCommandShadow.reset(engine)
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val group = GROUP.dropLast(1) + """, "innerProtocol":{"home":"ipv4","roaming":"ipv6"}}"""
                engine.mode = "module_access_mismatch"
                val chosen = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(group))
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(swuAccess = chosen)))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun realStoppedSourceSavesAndClearsTheCompleteGroupWithoutHotRPC() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.swuAccessSupported = true
            NextCallCommandShadow.reset(engine); NextCallCommandShadow.running = false
            engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationSwuAccess(), row.swuAccess)
                engine.mode = "module_access_saved"
                val saved = submit(fixture, overrides(row).copy(swuAccess = selected())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(selected(), saved.instance.swuAccess)
                assertEquals(ConfigurationApplyEffect.Module, saved.effect)
                assertEquals(setOf("swuAccess"), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject.keys)
                assertEquals(Json.parseToJsonElement(GROUP), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["swuAccess"])
                val cleared = submit(fixture, overrides(saved.instance).copy(swuAccess = ConfigurationSwuAccess())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationSwuAccess(), cleared.instance.swuAccess)
                assertEquals("default", cleared.instance.facts!!.swuAccess!!.source)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["swuAccess"])
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                assertEquals(1, engine.sockets)
            }
        }
    }

    @Test fun appliedReplyWithWrongAccessReadbackRemainsUnconfirmedWithoutReplay() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.swuAccessSupported = true
            NextCallCommandShadow.reset(engine)
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                engine.mode = "module_access_mismatch"
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(swuAccess = selected())))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun mixedAccessAndSignalingUseTheRootRouteAndConfirmBothSubmittedGroups() = runBlocking {
        for (mode in listOf("module_access_saved", "module_access_partial_signaling")) NextCallSocketFixture().use { engine ->
            engine.swuAccessSupported = true
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                engine.mode = mode
                val signaling = row.callSignaling!!.copy(includePANI = false)
                val outcome = submit(fixture, overrides(row).copy(swuAccess = selected(), callSignaling = signaling))
                if (mode == "module_access_saved") {
                    val saved = outcome as ConfigurationApplyOutcome.Confirmed
                    assertEquals(selected(), saved.instance.swuAccess)
                    assertEquals(signaling, saved.instance.callSignaling)
                    assertEquals(ConfigurationApplyEffect.Module, saved.effect)
                } else assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                assertEquals(setOf("swuAccess", "callSignaling"), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, engine.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                assertEquals(1, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun unreadAccessCannotBeReplacedUntilAnExplicitClearIsConfirmed() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.swuAccessSupported = true; engine.swuAccessUnknown = true
            NextCallCommandShadow.reset(engine)
            S3ConfigurationIoFixture().use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(row.swuAccess)
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(row).copy(swuAccess = selected())))
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_access_saved"
                val clear = submit(fixture, overrides(row).copy(swuAccess = ConfigurationSwuAccess())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationSwuAccess(), clear.instance.swuAccess)
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["swuAccess"])
            }
        }
    }

    private fun selected() = ConfigurationSwuAccessCodec.decode(Json.parseToJsonElement(GROUP))
    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        expectedRevision = row.configRevision, callSignaling = row.callSignaling, swuAccess = row.swuAccess)

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
        const val GROUP = """{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340}"""
    }
}
