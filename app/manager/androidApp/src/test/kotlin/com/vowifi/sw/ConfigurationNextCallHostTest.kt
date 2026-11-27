@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.os.Looper
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.configurationApplicationSummary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NextCallCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data", "com.vowifi.sw.root"])
class ConfigurationNextCallHostTest {
    @Test fun catalogTunnelAndDiagnosticInheritanceSurviveCallOnlySaveAndUserClear() = runBlocking {
        for (diagnostic in listOf(false, true)) NextCallSocketFixture().use { engine ->
            engine.tunnelSupported = true
            engine.catalogTunnel = Json.parseToJsonElement(TUNNEL_GROUP).jsonObject
            engine.tunnelDiagnosticLayout = diagnostic
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val source = if (diagnostic) "diagnostic" else "catalog"
                assertEquals(ConfigurationTunnelSecurity(), row.tunnelSecurity)
                assertEquals(source, row.facts!!.tunnelSecurity!!.source)
                assertNull(row.facts!!.tunnelSecurity!!.overrideValue)
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_tunnel_saved"
                val manual = ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(TUNNEL_GROUP))
                val saved = submit(fixture, overrides(callOnly.instance).copy(tunnelSecurity = manual)) as ConfigurationApplyOutcome.Confirmed
                assertEquals(manual, saved.instance.tunnelSecurity)
                assertEquals(if (diagnostic) "diagnostic" else "user", saved.instance.facts!!.tunnelSecurity!!.source)
                val cleared = submit(fixture, overrides(saved.instance).copy(tunnelSecurity = ConfigurationTunnelSecurity())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationTunnelSecurity(), cleared.instance.tunnelSecurity)
                assertEquals(source, cleared.instance.facts!!.tunnelSecurity!!.source)
                assertNull(cleared.instance.facts!!.tunnelSecurity!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelSecurity"])
            }
        }
    }

    @Test fun catalogTimingSurvivesCallOnlySaveAndReturnsAfterUserClear() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.timingSupported = true
            engine.catalogTiming = Json.parseToJsonElement(TIMING_GROUP).jsonObject
            NextCallCommandShadow.reset(engine); engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val row = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationTunnelTiming(), row.tunnelTiming)
                assertEquals("catalog", row.facts!!.tunnelTiming!!.source)
                assertNull(row.facts!!.tunnelTiming!!.overrideValue)
                val callOnly = submit(fixture, overrides(row).copy(callSignaling = ConfigurationCallSignaling())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(setOf("callSignaling"), engine.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                engine.mode = "module_timing_saved"
                val manual = ConfigurationTunnelTimingCodec.decode(Json.parseToJsonElement(TIMING_GROUP))
                val saved = submit(fixture, overrides(callOnly.instance).copy(tunnelTiming = manual)) as ConfigurationApplyOutcome.Confirmed
                assertEquals("user", saved.instance.facts!!.tunnelTiming!!.source)
                val cleared = submit(fixture, overrides(saved.instance).copy(tunnelTiming = ConfigurationTunnelTiming())) as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationTunnelTiming(), cleared.instance.tunnelTiming)
                assertEquals("catalog", cleared.instance.facts!!.tunnelTiming!!.source)
                assertEquals(manual, cleared.instance.facts!!.tunnelTiming!!.value)
                assertNull(cleared.instance.facts!!.tunnelTiming!!.overrideValue)
                assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelTiming"])
            }
        }
    }

    @Test fun timingUsesNextStartAndOnlyMatchingAuthoritativeReadbackConfirms() = runBlocking {
        for (mode in listOf("module_timing_saved", "module_timing_mismatch", "normal")) NextCallSocketFixture().use { f ->
            f.timingSupported = true
            f.mode = mode
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val timing = ConfigurationTunnelTimingCodec.decode(Json.parseToJsonElement(TIMING_GROUP))
                val outcome = submit(fixture, overrides(previous).copy(tunnelTiming = timing))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                val groups = NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject
                assertEquals(setOf("tunnelTiming"), groups.keys)
                assertEquals(Json.parseToJsonElement(TIMING_GROUP), groups["tunnelTiming"])
                assertEquals(0, f.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                when (mode) {
                    "module_timing_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals(timing, confirmed.instance.tunnelTiming)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val clear = submit(fixture, overrides(confirmed.instance).copy(tunnelTiming = ConfigurationTunnelTiming()))
                        assertTrue(clear is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelTiming"])
                        assertEquals(ConfigurationTunnelTiming(), (clear as ConfigurationApplyOutcome.Confirmed).instance.tunnelTiming)
                    }
                    "module_timing_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun unchangedTimingStaysOutOfCallOnlyWritesAndCannotUseHotUpdate() = runBlocking {
        NextCallSocketFixture().use { f ->
            f.timingSupported = true
            f.timing = Json.parseToJsonElement(TIMING_GROUP).jsonObject
            NextCallCommandShadow.reset(f)
            f.connect()
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, f.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"$INITIAL_REVISION","overrides":{"tunnelTiming":$TIMING_GROUP}}""") { true }.result)
            assertEquals(0, f.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals(previous.tunnelTiming, (outcome as ConfigurationApplyOutcome.Confirmed).instance.tunnelTiming)
                assertEquals(setOf("callSignaling"), f.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun tunnelPolicyUsesTheModuleRouteAndRequiresMatchingReadback() = runBlocking {
        for (mode in listOf("module_tunnel_saved", "module_tunnel_mismatch", "normal")) NextCallSocketFixture().use { f ->
            f.tunnelSupported = true
            f.mode = mode
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val policy = ConfigurationTunnelSecurityCodec.decode(Json.parseToJsonElement(TUNNEL_GROUP))
                val outcome = submit(fixture, overrides(previous).copy(tunnelSecurity = policy))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(Json.parseToJsonElement(TUNNEL_GROUP), NextCallCommandShadow.modulePayloads.single().getValue("overrides").jsonObject["tunnelSecurity"])
                assertEquals(0, f.methods.count { it == "setConfig" || it == "getClientCapabilities" })
                when (mode) {
                    "module_tunnel_saved" -> {
                        assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                        val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                        assertEquals(policy, confirmed.instance.tunnelSecurity)
                        assertEquals(ConfigurationApplyEffect.Module, confirmed.effect)
                        val cleared = submit(fixture, overrides(confirmed.instance).copy(tunnelSecurity = ConfigurationTunnelSecurity()))
                        assertTrue(cleared is ConfigurationApplyOutcome.Confirmed)
                        assertEquals(JsonNull, NextCallCommandShadow.modulePayloads.last().getValue("overrides").jsonObject["tunnelSecurity"])
                        assertEquals(ConfigurationTunnelSecurity(), (cleared as ConfigurationApplyOutcome.Confirmed).instance.tunnelSecurity)
                    }
                    "module_tunnel_mismatch" -> assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                    else -> assertSame(ConfigurationApplyOutcome.Failed, outcome)
                }
            }
        }
    }

    @Test fun unchangedTunnelPolicyDoesNotContaminateCallOnlyWritesAndThePoolRejectsTunnelHotUpdates() = runBlocking {
        NextCallSocketFixture().use { f ->
            f.tunnelSupported = true
            f.tunnel = Json.parseToJsonElement(TUNNEL_GROUP).jsonObject
            NextCallCommandShadow.reset(f)
            f.connect()
            // An unsupported local payload has no acknowledged remote outcome;
            // the existing pool contract returns Unconfirmed without any RPC.
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, f.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"$INITIAL_REVISION","overrides":{"tunnelSecurity":$TUNNEL_GROUP}}""") { true }.result)
            assertEquals(0, f.methods.count { it == "setConfig" || it == "getClientCapabilities" })
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                assertEquals(previous.tunnelSecurity, (outcome as ConfigurationApplyOutcome.Confirmed).instance.tunnelSecurity)
                assertEquals(setOf("callSignaling"), f.writeParams.single().getValue("overrides").jsonObject.keys)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun sdesUsesItsOwnBoundCapabilityAndOnlyOneNonSecretWriteAndReadback() = runBlocking {
        for (supported in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.sdesSupported = supported
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val raw = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-wb","framing":"oa"}],"security":{"keying":"sdes","suites":["AEAD_AES_256_GCM","AES_CM_128_HMAC_SHA1_80"]}}""")
                val media = ConfigurationCallMediaCodec.decode(raw)
                val outcome = submit(fixture, overrides(previous).copy(callMedia = media))
                val required = f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue("config.call-media.sdes.v1" in required)
                if (supported) {
                    assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                    val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                    assertEquals(media, confirmed.instance.callMedia)
                    assertEquals(ConfigurationApplyEffect.NextCall, confirmed.effect)
                    assertEquals(1, f.methods.count { it == "setConfig" })
                    assertEquals(raw, f.writeParams.single().getValue("overrides").jsonObject["callMedia"])
                } else {
                    assertSame(ConfigurationApplyOutcome.Failed, outcome)
                    assertEquals(0, f.methods.count { it == "setConfig" })
                }
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    @Test fun theSpecificWriteRequiresAnExistingConnectionAndKeepsTheCapabilityAndWriteOnIt() = runBlocking {
        NextCallSocketFixture().use { f ->
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, f.apply().result)
            assertEquals(0, f.sockets)
            f.connect()
            assertEquals(ConfigurationNextCallResult(ConfigurationCommandApplyResult.Applied, NEXT_REVISION), f.apply())
            assertEquals(listOf("hello", "ping", "getClientCapabilities", "setConfig"), f.methods.toList())
            assertEquals(1, f.sockets)
            val params = f.writeParams.single()
            assertEquals(JsonPrimitive(INITIAL_REVISION), params["expectedRevision"])
            assertEquals(Json.parseToJsonElement("""{"callSignaling":{"includePANI":false}}"""), params["overrides"])
            assertEquals(1, f.capabilityParams.size)
            assertEquals(setOf("config.metadata.v1", "config.call-signaling.next-call.v1", "rpc.setConfig"),
                f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }

    @Test fun unsupportedUnsortedOrRetiredAuthorityNeverReachesTheWrite() = runBlocking {
        for (mode in listOf("legacy", "unsorted", "retired", "owner_changed")) NextCallSocketFixture().use { f ->
            f.mode = mode
            f.connect()
            val result = f.apply()
            assertEquals(mode, 1, f.methods.count { it == "getClientCapabilities" })
            assertEquals(mode, 0, f.methods.count { it == "setConfig" })
            assertEquals(mode, if (mode == "legacy") ConfigurationCommandApplyResult.Failed else ConfigurationCommandApplyResult.Unconfirmed, result.result)
            assertEquals(mode, 1, f.sockets)
        }
    }

    @Test fun malformedMismatchedAndLostRepliesRemainUnconfirmedAndAreNotReplayed() = runBlocking {
        for (mode in listOf("wrong_effect", "wrong_revision", "wrong_group", "lost_reply")) NextCallSocketFixture().use { f ->
            f.mode = mode
            f.connect()
            assertEquals(mode, ConfigurationCommandApplyResult.Unconfirmed, f.apply().result)
            assertEquals(mode, 1, f.methods.count { it == "setConfig" })
            assertEquals(mode, 1, f.sockets)
        }
    }

    @Test fun explicitConflictAndNoopAreTypedAndCancellationDoesNotResumeTheWrite() = runBlocking {
        for (mode in listOf("conflict", "unchanged")) NextCallSocketFixture().use { f ->
            f.mode = mode
            f.connect()
            val result = f.apply()
            assertEquals(mode, if (mode == "conflict") ConfigurationCommandApplyResult.RevisionConflict else ConfigurationCommandApplyResult.Unchanged, result.result)
            if (mode == "unchanged") assertEquals(INITIAL_REVISION, result.configRevision)
            assertEquals(1, f.methods.count { it == "setConfig" })
        }
        NextCallSocketFixture().use { f ->
            f.mode = "hold_reply"
            f.connect()
            var delivered = false
            val writing = launch { f.apply(); delivered = true }
            withTimeout(3_000) { f.writeStarted.await() }
            writing.cancelAndJoin()
            assertFalse(delivered)
            assertEquals(1, f.methods.count { it == "setConfig" })
        }
    }

    @Test fun runningOnlySignalingUsesTheActualPoolAndReadbackWithoutCallingTheModuleApply() = runBlocking {
        for (mode in listOf("normal", "catalog_inheritance")) NextCallSocketFixture().use { f ->
            NextCallCommandShadow.reset(f)
            f.mode = mode
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNotNull(previous.callSignaling)
                val desired = overrides(previous).copy(callSignaling = ConfigurationCallSignaling())
                val outcome = submit(fixture, desired)
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationApplyEffect.NextCall, confirmed.effect)
                val saved = confirmed.instance
                assertEquals(NEXT_REVISION, saved.configRevision)
                assertEquals(ConfigurationCallSignaling(), saved.callSignaling)
                if (mode == "catalog_inheritance") {
                    val inherited = saved.facts!!.fields.single { it.field == "callSignaling.includePANI" }
                    assertEquals("catalog", inherited.source)
                    assertEquals("false", inherited.value)
                    assertNull(inherited.overrideValue)
                }
                assertEquals(JsonNull, f.writeParams.single().getValue("overrides").jsonObject["callSignaling"])
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.methods.count { it == "setConfig" })
            }
        }
    }

    @Test fun lostReplyMayBeReconciledReadOnlyButCannotBeReportedAsAnAcknowledgedSave() = runBlocking {
        for (mode in listOf("lost_reply", "readback_mismatch")) NextCallSocketFixture().use { f ->
            NextCallCommandShadow.reset(f)
            f.mode = mode
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                val readback = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                assertEquals(if (mode == "lost_reply") NEXT_REVISION else INITIAL_REVISION, readback.configRevision)
                assertEquals(1, f.methods.count { it == "setConfig" })
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun lifecycleLockRejectionReachesTheAppWithoutChangingRevisionOrReplaying() = runBlocking {
        NextCallSocketFixture().use { engine ->
            engine.writeErrorReply = busyReply("configuration lifecycle busy")
            NextCallCommandShadow.reset(engine)
            engine.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertSame(ConfigurationApplyOutcome.Busy, outcome)
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals("设备正忙或无法确认空闲，配置未保存", ready.message)
                val readback = ready.instances.single { it.instanceId == fixture.targetId }
                assertEquals(previous.configRevision, readback.configRevision)
                assertEquals(previous.callSignaling, readback.callSignaling)
                assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                assertEquals(1, engine.writeParams.size)
                assertEquals(JsonNull, engine.writeParams.single().getValue("overrides").jsonObject["callSignaling"])
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, engine.sockets)
            }
        }
    }

    @Test fun lifecycleRefusalDoesNotRevertNewerRuntimeFacts() = runBlocking {
        assertRefusalKeepsRuntimeFacts(busyReply("configuration lifecycle busy"), ConfigurationApplyOutcome.Busy,
            "设备正忙或无法确认空闲，配置未保存")
    }

    @Test fun explicitFailureDoesNotRevertNewerRuntimeFacts() = runBlocking {
        val reply = buildJsonObject { put("error", buildJsonObject { put("code", 1001); put("message", "IO_FAILED") }) }
        assertRefusalKeepsRuntimeFacts(reply, ConfigurationApplyOutcome.Failed, ConfigurationEvaluator.failure().message)
    }

    private suspend fun assertRefusalKeepsRuntimeFacts(
        reply: JsonObject, expected: ConfigurationApplyOutcome, message: String,
    ) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.writeErrorReply = reply
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            S3ConfigurationIoFixture(runtimeRead = { key -> engine.pool.configurationFacts(key) },
                nextCallApply = { key, payload, allowed ->
                    captured.complete(Unit)
                    release.await()
                    engine.pool.applyCallPolicy(key, payload, allowed)
                }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(2L, previous.runtimeFacts?.call?.media?.quality?.revision)
                val commands = S3ConfigurationCommandShadow.commands.toList()
                engine.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val completed = async { submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling())) }
                val published = try {
                    withTimeout(2_500) {
                        captured.await()
                        assertTrue(engine.writeParams.isEmpty())
                        engine.runtimeCall = callWithQualityRevision(9)
                        engine.mediaChanged(1)
                        fixture.source.state.first { state ->
                            (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                                ?.runtimeFacts?.call?.media?.quality?.revision == 9L
                        }
                    } as ConfigurationUiState.Ready
                } finally {
                    release.complete(Unit)
                }
                assertEquals(fixture.targetId, published.applyingInstanceId)
                assertSame(expected, completed.await())
                val finished = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals(message, finished.message)
                assertNull(finished.applyingInstanceId)
                val row = finished.instances.single { it.instanceId == fixture.targetId }
                assertEquals(previous.configRevision, row.configRevision)
                assertEquals(previous.callSignaling, row.callSignaling)
                assertEquals(INITIAL_REVISION, engine.revision)
                assertEquals(1, engine.writeParams.size)
                assertEquals(1, engine.capabilityParams.size)
                assertEquals(2, engine.methods.count { it == "getStatus" })
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, engine.sockets)
                assertEquals(commands, S3ConfigurationCommandShadow.commands.toList())
                assertEquals("refusal must not revert a newer media fact", 9L, row.runtimeFacts?.call?.media?.quality?.revision)
                assertEquals(published.instances, finished.instances)
            }
        }
    }

    private fun callWithQualityRevision(quality: Int) = Json.parseToJsonElement("""{
        "configRevision":"$INITIAL_REVISION",
        "media":{"epoch":"media-epoch","revision":3,"codec":"amr-wb","framing":"oa","clockRate":16000,
          "payloadType":96,"receivePayloadType":112,"sendPtimeMs":60,"receiveMaxPtimeMs":80,
          "direction":"sendrecv","inactivityTimeoutMs":60000,"quality":{"revision":$quality,
            "send":{"fractionLost":64,"jitterMs":20,"totalLost":-2}}}
    }""").jsonObject

    @Test fun committedReadbackDoesNotReplaceALaterQualityObservation() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.QualityAdvanced)
    }

    @Test fun committedReadbackDoesNotResurrectAnEndedDialog() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.DialogEnded)
    }

    @Test fun committedReadbackUsesTheCatalogFactsWhenTheyArriveLast() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.CatalogLast)
    }

    @Test fun committedReadbackCannotCarryMediaFactsOverToAReplacementSocket() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.SocketRetired)
    }

    @Test fun unconfirmedReadbackDoesNotRestoreAnOlderQualityObservation() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.QualityAdvanced, ReadbackReply.Unknown)
    }

    @Test fun conflictReadbackDoesNotResurrectAnEndedDialog() = runBlocking {
        assertReadbackKeepsLaterRuntime(ReadbackOrder.DialogEnded, ReadbackReply.Conflict)
    }

    private enum class ReadbackOrder { QualityAdvanced, DialogEnded, CatalogLast, SocketRetired }
    private enum class ReadbackReply { Saved, Unknown, Conflict }

    private fun NextCallSocketFixture.stageReply(reply: ReadbackReply) {
        when (reply) {
            ReadbackReply.Saved -> Unit
            ReadbackReply.Unknown -> mode = "wrong_effect" // Commits, but the acknowledgement cannot be trusted.
            ReadbackReply.Conflict -> {
                revision = NEXT_REVISION // Another writer advanced the saved revision before this submission.
                mode = "conflict"
            }
        }
    }

    private fun assertReadbackOutcome(reply: ReadbackReply, outcome: ConfigurationApplyOutcome, state: ConfigurationUiState.Ready) {
        when (reply) {
            ReadbackReply.Saved -> assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
            ReadbackReply.Unknown -> {
                assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                assertEquals("保存结果未确认，请刷新核对；不会自动重试", state.message)
            }
            ReadbackReply.Conflict -> {
                assertSame(ConfigurationApplyOutcome.Failed, outcome)
                assertEquals("配置已变化，请刷新核对并处理原草稿", state.message)
            }
        }
    }

    private suspend fun assertReadbackKeepsLaterRuntime(order: ReadbackOrder, reply: ReadbackReply = ReadbackReply.Saved) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            val sampled = CompletableDeferred<Unit>()
            val publish = CompletableDeferred<Unit>()
            S3ConfigurationIoFixture(
                runtimeObservationRead = { key ->
                    if (order == ReadbackOrder.CatalogLast && engine.revision == NEXT_REVISION && sampled.complete(Unit)) publish.await()
                    val observation = engine.pool.configurationObservation(key)
                    // Wait only after the real socket response has been completely read.
                    if (observation?.facts?.loaded?.configRevision == NEXT_REVISION && sampled.complete(Unit)) publish.await()
                    observation
                },
                nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) },
            ).use { fixture ->
                val old = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(2L, old.runtimeFacts?.call?.media?.quality?.revision)
                engine.stageReply(reply)
                engine.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val completed = async { submit(fixture, overrides(old).copy(callSignaling = ConfigurationCallSignaling())) }
                val hintedQuality = if (order == ReadbackOrder.DialogEnded) null else 9L
                try {
                    withTimeout(2_500) {
                        sampled.await()
                        assertEquals(NEXT_REVISION, engine.revision)
                        engine.runtimeCall = if (hintedQuality == null) null else callWithQualityRevision(9)
                        engine.mediaChanged(1)
                        fixture.source.state.first { state ->
                            val row = (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            row?.configRevision == INITIAL_REVISION && row.runtimeFacts?.loaded?.configRevision == NEXT_REVISION &&
                                row.runtimeFacts?.call?.media?.quality?.revision == hintedQuality
                        }
                        if (order == ReadbackOrder.CatalogLast) engine.runtimeCall = callWithQualityRevision(10)
                        if (order == ReadbackOrder.SocketRetired) {
                            engine.pool.remove(S3_CONFIG_TARGET)?.close()
                            engine.connect()
                        }
                    }
                } finally {
                    publish.complete(Unit)
                }
                val outcome = completed.await()
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                assertReadbackOutcome(reply, outcome, ready)
                val row = ready.instances.single { it.instanceId == fixture.targetId }
                assertNull(ready.applyingInstanceId)
                assertEquals(NEXT_REVISION, row.configRevision)
                assertEquals(NEXT_REVISION.takeUnless { order == ReadbackOrder.SocketRetired }, row.runtimeFacts?.loaded?.configRevision)
                assertEquals(if (reply == ReadbackReply.Conflict) old.callSignaling else ConfigurationCallSignaling(), row.callSignaling)
                assertEquals(3, engine.methods.count { it == "getStatus" })
                assertEquals(1, engine.writeParams.size)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(if (order == ReadbackOrder.SocketRetired) 2 else 1, engine.sockets)
                val expectedQuality = when (order) {
                    ReadbackOrder.CatalogLast -> 10L
                    ReadbackOrder.SocketRetired -> null
                    else -> hintedQuality
                }
                assertEquals("later status must not regress after disk confirmation", expectedQuality, row.runtimeFacts?.call?.media?.quality?.revision)
            }
        }
    }

    @Test fun newerQualityObservationCanPublishAfterTheSameOwnersSave() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.QualityAdvanced)
    }

    @Test fun newerNoDialogObservationCanPublishAfterTheSameOwnersSave() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.DialogEnded)
    }

    @Test fun olderEventCannotOverwriteTheLaterSavedReadback() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.CatalogNewer)
    }

    @Test fun aHeldEventCannotPublishAfterItsSocketIsReplaced() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.SocketRetired)
    }

    @Test fun newerQualityObservationCanPublishAfterAnUnconfirmedReadback() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.QualityAdvanced, ReadbackReply.Unknown)
    }

    @Test fun newerNoDialogObservationCanPublishAfterAConflictReadback() = runBlocking {
        assertHeldEventAfterSave(HeldEvent.DialogEnded, ReadbackReply.Conflict)
    }

    private enum class HeldEvent { QualityAdvanced, DialogEnded, CatalogNewer, SocketRetired }

    private suspend fun assertHeldEventAfterSave(event: HeldEvent, reply: ReadbackReply = ReadbackReply.Saved) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            val startCatalogStatus = CompletableDeferred<Unit>()
            val allowCatalogStatus = CompletableDeferred<Unit>()
            val catalogReceived = CompletableDeferred<Unit>()
            val allowCatalogPublish = CompletableDeferred<Unit>()
            val eventReceived = CompletableDeferred<Unit>()
            val allowEventPublish = CompletableDeferred<Unit>()
            val eventQuality = if (event == HeldEvent.DialogEnded) null else 9L
            val catalogQuality = if (event == HeldEvent.CatalogNewer) 10L else 2L
            S3ConfigurationIoFixture(
                runtimeObservationRead = { key ->
                    if (event == HeldEvent.CatalogNewer && engine.revision == NEXT_REVISION && startCatalogStatus.complete(Unit)) {
                        allowCatalogStatus.await()
                    }
                    val observation = engine.pool.configurationObservation(key)
                    val facts = observation?.facts
                    if (facts?.loaded?.configRevision == NEXT_REVISION) {
                        // Both replies are actually received before their publication gates.
                        if (facts.call?.media?.quality?.revision == eventQuality) {
                            if (eventReceived.complete(Unit)) allowEventPublish.await()
                        } else if (catalogReceived.complete(Unit)) allowCatalogPublish.await()
                    }
                    observation
                },
                nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) },
            ).use { fixture ->
                val original = fixture.ready()
                val old = original.instances.single { it.instanceId == fixture.targetId }
                assertEquals(2L, old.runtimeFacts?.call?.media?.quality?.revision)
                engine.stageReply(reply)
                engine.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val completed = async { submit(fixture, overrides(old).copy(callSignaling = ConfigurationCallSignaling())) }
                fun target(state: ConfigurationUiState) = (state as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                try {
                    withTimeout(2_500) {
                        if (event == HeldEvent.CatalogNewer) startCatalogStatus.await() else catalogReceived.await()
                        engine.runtimeCall = eventQuality?.let { callWithQualityRevision(it.toInt()) }
                        engine.mediaChanged(1)
                        eventReceived.await()
                        if (event == HeldEvent.CatalogNewer) {
                            engine.runtimeCall = callWithQualityRevision(catalogQuality.toInt())
                            allowCatalogStatus.complete(Unit)
                            catalogReceived.await()
                        }
                        assertEquals(INITIAL_REVISION, target(fixture.source.state.value).configRevision)
                    }
                    allowCatalogPublish.complete(Unit)
                    val outcome = completed.await()
                    val reconciled = fixture.source.state.value as ConfigurationUiState.Ready
                    assertReadbackOutcome(reply, outcome, reconciled)
                    val saved = target(reconciled)
                    assertEquals(NEXT_REVISION, saved.configRevision)
                    assertEquals(catalogQuality, saved.runtimeFacts?.call?.media?.quality?.revision)
                    if (event == HeldEvent.SocketRetired) {
                        engine.pool.remove(S3_CONFIG_TARGET)?.close()
                        engine.connect()
                    }
                } finally {
                    allowCatalogStatus.complete(Unit)
                    allowCatalogPublish.complete(Unit)
                    allowEventPublish.complete(Unit)
                }
                fixture.ready() // Join the actual event reader before asserting that it published or dropped its sample.
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                val row = target(ready)
                assertNull(ready.applyingInstanceId)
                assertEquals(NEXT_REVISION, row.configRevision)
                assertEquals(NEXT_REVISION, row.runtimeFacts?.loaded?.configRevision)
                assertEquals(if (reply == ReadbackReply.Conflict) old.callSignaling else ConfigurationCallSignaling(), row.callSignaling)
                assertEquals(original.instances.filter { it.instanceId != fixture.targetId }, ready.instances.filter { it.instanceId != fixture.targetId })
                assertEquals(3, engine.methods.count { it == "getStatus" })
                assertEquals(1, engine.writeParams.size)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(if (event == HeldEvent.SocketRetired) 2 else 1, engine.sockets)
                val expected = when (event) {
                    HeldEvent.CatalogNewer, HeldEvent.SocketRetired -> catalogQuality
                    else -> eventQuality
                }
                assertEquals("in-flight event must respect the same socket's receipt order", expected, row.runtimeFacts?.call?.media?.quality?.revision)
            }
        }
    }

    @Test fun manualRefreshDoesNotRestoreACallClearedByALaterObservation() = runBlocking {
        assertRefreshKeepsLaterRuntime(eventPublishedFirst = true)
    }

    @Test fun aLaterNoDialogObservationCanPublishAfterManualRefresh() = runBlocking {
        assertRefreshKeepsLaterRuntime(eventPublishedFirst = false)
    }

    @Test fun candidateInventoryFailureDoesNotRestoreACallClearedByALaterObservation() = runBlocking {
        assertRefreshKeepsLaterRuntime(eventPublishedFirst = true,
            inventoryFailure = SubscriptionInventoryResult.Failure("无法读取 SIM 控制状态"))
    }

    @Test fun aLaterNoDialogObservationCanPublishAfterCandidateInventoryRecovers() = runBlocking {
        assertRefreshKeepsLaterRuntime(eventPublishedFirst = false,
            inventoryFailure = SubscriptionInventoryResult.PermissionDenied("配置读取权限不足"))
    }

    @Test fun aHeldRuntimeObservationCannotPublishWhileCandidateInventoryIsDenied() = runBlocking {
        assertRefreshKeepsLaterRuntime(eventPublishedFirst = false,
            inventoryFailure = SubscriptionInventoryResult.PermissionDenied("配置读取权限不足"), keepInventoryFailure = true)
    }

    private suspend fun assertRefreshKeepsLaterRuntime(
        eventPublishedFirst: Boolean,
        inventoryFailure: SubscriptionInventoryResult? = null,
        keepInventoryFailure: Boolean = false,
    ) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            val catalogReceived = CompletableDeferred<Unit>()
            val allowCatalogPublish = CompletableDeferred<Unit>()
            val eventReceived = CompletableDeferred<Unit>()
            val allowEventPublish = CompletableDeferred<Unit>()
            S3ConfigurationIoFixture(runtimeObservationRead = { key ->
                val observation = engine.pool.configurationObservation(key)
                val facts = observation?.facts
                if (facts?.call?.media?.quality?.revision == 3L && catalogReceived.complete(Unit)) {
                    allowCatalogPublish.await()
                } else if (facts != null && facts.call == null && eventReceived.complete(Unit) && !eventPublishedFirst) {
                    allowEventPublish.await()
                }
                observation
            }).use { fixture ->
                val old = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(2L, old.runtimeFacts?.call?.media?.quality?.revision)
                val commandCount = S3ConfigurationCommandShadow.commands.size
                engine.pool.onRuntimeFactsChange = { key, hint -> fixture.source.runtimeFactsChanged(key, hint) }
                engine.runtimeCall = callWithQualityRevision(3)
                val refresh = async { fixture.source.refreshAndAwait() }
                fun target(state: ConfigurationUiState) = (state as? ConfigurationUiState.Ready)?.instances
                    ?.singleOrNull { it.instanceId == fixture.targetId }
                try {
                    withTimeout(2_500) {
                        catalogReceived.await()
                        engine.runtimeCall = null
                        engine.mediaChanged(1)
                        eventReceived.await()
                        if (eventPublishedFirst) fixture.source.state.first { state ->
                            target(state)?.runtimeFacts?.let { it.call == null } == true
                        }
                        // Let the catalog finish its own discovery, then fail candidate selection.
                        fixture.inventoryFailure = inventoryFailure
                        allowCatalogPublish.complete(Unit)
                        val expected = if (inventoryFailure == null) RefreshResult.Completed else RefreshResult.Failed("无法读取活动订阅")
                        assertEquals(expected, refresh.await())
                    }
                    if (!eventPublishedFirst) assertEquals(3L, target(fixture.source.state.value)?.runtimeFacts?.call?.media?.quality?.revision)
                } finally {
                    if (!keepInventoryFailure) fixture.inventoryFailure = null
                    allowCatalogPublish.complete(Unit)
                    allowEventPublish.complete(Unit)
                }
                fixture.ready()
                val row = checkNotNull(target(fixture.source.state.value))
                assertEquals(old.configRevision, row.configRevision)
                assertEquals(old.callSignaling, row.callSignaling)
                assertEquals(old.systemSubscription, row.systemSubscription)
                assertEquals(listOf(ConfigurationControlCommand.listLine(), ConfigurationControlCommand.showLine(S3_CONFIG_TARGET)),
                    S3ConfigurationCommandShadow.commands.drop(commandCount))
                assertEquals(INITIAL_REVISION, row.runtimeFacts?.loaded?.configRevision)
                assertEquals(3, engine.methods.count { it == "getStatus" })
                assertTrue(engine.writeParams.isEmpty())
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, engine.sockets)
                if (keepInventoryFailure) assertEquals("unverified attachment must not publish", 3L, row.runtimeFacts?.call?.media?.quality?.revision)
                else assertNull("manual refresh must retain the later no-dialog observation", row.runtimeFacts?.call)
            }
        }
    }

    @Test fun unconfirmedWriteWithAnUnreadableListDoesNotRestoreTheOldCall() = runBlocking {
        assertUnavailableReadbackKeepsPublishedRuntime(ReadbackReply.Unknown,
            AndroidSuResult(false, 1, """{"ok":false,"code":"IO_FAILED","message":"list failed"}""", false))
    }

    @Test fun conflictWithAListPermissionFailureDoesNotRestoreTheOldCall() = runBlocking {
        assertUnavailableReadbackKeepsPublishedRuntime(ReadbackReply.Conflict,
            AndroidSuResult(false, 1, "Permission denied", false))
    }

    private suspend fun assertUnavailableReadbackKeepsPublishedRuntime(reply: ReadbackReply, listFailure: AndroidSuResult) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            S3ConfigurationIoFixture(runtimeObservationRead = { key -> engine.pool.configurationObservation(key) },
                nextCallApply = { key, payload, allowed -> engine.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(2L, previous.runtimeFacts?.call?.media?.quality?.revision)
                engine.stageReply(reply)
                engine.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val list = fixture.holdList()
                val completed = async(start = CoroutineStart.UNDISPATCHED) {
                    submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                }
                val published = try {
                    list.awaitEntered()
                    engine.runtimeCall = null
                    engine.mediaChanged(1)
                    withTimeout(2_500) {
                        fixture.source.state.first { state ->
                            val row = (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            row?.runtimeFacts?.let { it.loaded.configRevision == NEXT_REVISION && it.call == null } == true
                        }
                    } as ConfigurationUiState.Ready
                } finally {
                    NextCallCommandShadow.listReadbackFailure.set(listFailure)
                    list.release()
                }
                assertEquals(fixture.targetId, published.applyingInstanceId)
                val outcome = completed.await()
                val finished = fixture.source.state.value as ConfigurationUiState.Ready
                assertReadbackOutcome(reply, outcome, finished)
                assertNull(finished.applyingInstanceId)
                val row = finished.instances.single { it.instanceId == fixture.targetId }
                assertEquals(previous.configRevision, row.configRevision)
                assertEquals(previous.callSignaling, row.callSignaling)
                assertEquals(ConfigurationApplyAcceptance.Unavailable,
                    fixture.source.submit(fixture.targetId, overrides(previous)) { error("An unreadable list cannot authorize a new write") })
                assertEquals(2, S3ConfigurationCommandShadow.listCalls())
                assertEquals(1, S3ConfigurationCommandShadow.commands.count { it == ConfigurationControlCommand.showLine(S3_CONFIG_TARGET) })
                assertEquals(2, engine.methods.count { it == "getStatus" })
                assertEquals(1, engine.writeParams.size)
                assertEquals(1, engine.sockets)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertNull("an unreadable catalog must not resurrect an already cleared call", row.runtimeFacts?.call)
                assertEquals(published.instances, finished.instances)
            }
        }
    }

    @Test fun unknownOrContradictoryBusyRepliesDoNotProveTheWriteWasRejected() = runBlocking {
        val replies = listOf(busyReply("busy"), busyReply("configuration lifecycle busy", resultAlsoPresent = true))
        val reported = replies.map { reply ->
            NextCallSocketFixture().use { engine ->
                engine.writeErrorReply = reply
                engine.connect()
                val outcome = engine.apply().result
                assertEquals(1, engine.writeParams.size)
                outcome
            }
        }
        assertEquals(List(2) { ConfigurationCommandApplyResult.Unconfirmed }, reported)
    }

    @Test fun catalogDoesNotCallAnEarlierDiskSampleAnUnappliedSave() = runBlocking {
        NextCallSocketFixture().use { engine ->
            NextCallCommandShadow.reset(engine)
            engine.connect()
            var committed = false
            S3ConfigurationIoFixture(runtimeRead = { key ->
                if (!committed) {
                    assertTrue(S3ConfigurationCommandShadow.commands.contains(ConfigurationControlCommand.showLine(key)))
                    // A separate management action commits after the catalog's persisted read.
                    assertEquals(ConfigurationCommandApplyResult.Applied, engine.apply().result)
                    committed = true
                }
                engine.pool.configurationFacts(key)
            }).use { fixture ->
                val earlier = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(INITIAL_REVISION, earlier.facts?.configRevision)
                assertEquals(NEXT_REVISION, earlier.runtimeFacts?.loaded?.configRevision)
                assertEquals(NEXT_REVISION, engine.revision)
                val mixedMessage = configurationApplicationSummary(earlier)
                assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                val refreshed = ready.instances.single { it.instanceId == fixture.targetId }
                assertEquals(NEXT_REVISION, refreshed.facts?.configRevision)
                assertEquals(NEXT_REVISION, refreshed.runtimeFacts?.loaded?.configRevision)
                assertTrue(configurationApplicationSummary(refreshed).contains("已加载"))
                assertEquals(1, engine.writeParams.size)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertFalse("an earlier read is not an unapplied save", mixedMessage.contains("尚未用于"))
                assertTrue(mixedMessage.contains("不同"))
            }
        }
    }

    private fun busyReply(message: String, resultAlsoPresent: Boolean = false) = buildJsonObject {
        put("error", buildJsonObject { put("code", 2001); put("message", message) })
        if (resultAlsoPresent) put("result", buildJsonObject { put("state", "Saved") })
    }

    @Test fun subscriptionRetiredDuringTheCapabilityReadRevokesTheWriteBeforeItStarts() = runBlocking {
        NextCallSocketFixture().use { f ->
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                f.onCapabilities = fixture::notifySubscriptionsChanged
                val outcome = submit(fixture, overrides(previous).copy(callSignaling = ConfigurationCallSignaling()))
                assertSame(ConfigurationApplyOutcome.Unconfirmed, outcome)
                assertEquals(1, f.methods.count { it == "getClientCapabilities" })
                assertEquals(0, f.methods.count { it == "setConfig" })
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun stoppedOrMixedChangesKeepTheExistingModuleRoute() = runBlocking {
        for (stopped in listOf(false, true)) NextCallSocketFixture().use { f ->
            NextCallCommandShadow.reset(f)
            NextCallCommandShadow.running = !stopped
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val desired = if (stopped) overrides(previous).copy(callSignaling = ConfigurationCallSignaling())
                    else overrides(previous).copy(epdgFqdn = "new.epdg.example", callSignaling = ConfigurationCallSignaling())
                // This fixture rejects the module write; it proves routing only,
                // not a successful disk/module transaction or a real restart.
                assertSame(ConfigurationApplyOutcome.Failed, submit(fixture, desired))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(0, f.methods.count { it == "setConfig" })
            }
        }
    }

    @Test fun runningMediaOrMediaAndSignalingUseTheExistingConnectionAndReadBackBothGroups() = runBlocking {
        for (withSignaling in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertEquals(ConfigurationCallMedia(), previous.callMedia)
                val media = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.OA,
                    modeSet = listOf(0, 2, 4), initialMode = 0, payloadType = 110, receiveModeChangePeriod = 2, receiveModeChangeNeighbor = false)))
                val desired = overrides(previous).copy(callMedia = media,
                    callSignaling = if (withSignaling) ConfigurationCallSignaling() else previous.callSignaling)
                val outcome = submit(fixture, desired)
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                val confirmed = outcome as ConfigurationApplyOutcome.Confirmed
                assertEquals(ConfigurationApplyEffect.NextCall, confirmed.effect)
                assertEquals(media, confirmed.instance.callMedia)
                assertEquals(NEXT_REVISION, confirmed.instance.configRevision)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.methods.count { it == "setConfig" })
                assertEquals(1, f.sockets)
                val required = f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue("config.call-media.next-call.v1" in required)
                assertFalse("config.call-media.packetization.v1" in required)
                assertFalse("config.call-media.inactivity.v1" in required)
                assertFalse("config.call-media.pcmu.v1" in required)
                assertFalse("config.call-media.sdes.v1" in required)
                assertEquals(withSignaling, "config.call-signaling.next-call.v1" in required)
                assertEquals(if (withSignaling) setOf("callMedia", "callSignaling") else setOf("callMedia"),
                    f.writeParams.single().getValue("overrides").jsonObject.keys)
            }
        }
    }

    @Test fun missingMediaCapabilityAndMismatchedMediaReplyNeverFallbackToRestartOrReplay() = runBlocking {
        for (mode in listOf("media_unsupported", "wrong_media", "wrong_disk_media", "lost_reply")) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.mode = mode
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val media = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.BE)))
                val outcome = submit(fixture, overrides(previous).copy(callMedia = media))
                assertSame(if (mode == "media_unsupported") ConfigurationApplyOutcome.Failed else ConfigurationApplyOutcome.Unconfirmed, outcome)
                assertEquals(if (mode == "media_unsupported") 0 else 1, f.methods.count { it == "setConfig" })
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    @Test fun receivePacketPairRequiresItsOwnBoundCapabilityBeforeOneWriteAndReadback() = runBlocking {
        for (supported in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.packetizationSupported = supported
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val media = ConfigurationCallMedia(
                    listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_WB, ConfigurationSpeechFraming.OA)),
                    ConfigurationCallPacketization(60, 80))
                val outcome = submit(fixture, overrides(previous).copy(callMedia = media))
                val required = f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue(required.containsAll(setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.packetization.v1")))
                if (supported) {
                    assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                    assertEquals(media, (outcome as ConfigurationApplyOutcome.Confirmed).instance.callMedia)
                    assertEquals(ConfigurationApplyEffect.NextCall, outcome.effect)
                    assertEquals(1, f.methods.count { it == "setConfig" })
                    assertEquals(JsonPrimitive(80), f.writeParams.single().getValue("overrides").jsonObject.getValue("callMedia")
                        .jsonObject.getValue("packetization").jsonObject["receiveMaxPtimeMs"])
                } else {
                    assertSame(ConfigurationApplyOutcome.Failed, outcome)
                    assertEquals(0, f.methods.count { it == "setConfig" })
                }
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    @Test fun pcmuRequiresItsBoundCapabilityAndReadsBackExactlyOneStaticPT0Write() = runBlocking {
        for (supported in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.pcmuSupported = supported
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val media = ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(
                    """{"speechFormats":[{"codec":"pcmu","framing":"sample","payloadType":0}]}"""))
                val outcome = submit(fixture, overrides(previous).copy(callMedia = media))
                val required = f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue("config.call-media.pcmu.v1" in required)
                if (supported) {
                    assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                    assertEquals(media, (outcome as ConfigurationApplyOutcome.Confirmed).instance.callMedia)
                    assertEquals(ConfigurationApplyEffect.NextCall, outcome.effect)
                    assertEquals(1, f.methods.count { it == "setConfig" })
                    assertEquals(JsonPrimitive(0), f.writeParams.single().getValue("overrides").jsonObject.getValue("callMedia")
                        .jsonObject.getValue("speechFormats").jsonArray.single().jsonObject["payloadType"])
                } else {
                    assertSame(ConfigurationApplyOutcome.Failed, outcome)
                    assertEquals(0, f.methods.count { it == "setConfig" })
                }
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    @Test fun inactivityPolicyAndExplicitZeroRequireTheirOwnCapabilityBeforeOneWriteAndReadback() = runBlocking {
        for (timeout in listOf(0, 160)) for (supported in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.inactivitySupported = supported
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val media = ConfigurationCallMedia(
                    listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_WB, ConfigurationSpeechFraming.OA)),
                    inactivity = ConfigurationCallInactivity(timeout))
                val outcome = submit(fixture, overrides(previous).copy(callMedia = media))
                val required = f.capabilityParams.single().getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue(required.containsAll(setOf("rpc.setConfig", "config.call-media.next-call.v1", "config.call-media.inactivity.v1")))
                assertFalse("config.call-media.packetization.v1" in required)
                if (supported) {
                    assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                    assertEquals(media, (outcome as ConfigurationApplyOutcome.Confirmed).instance.callMedia)
                    assertEquals(ConfigurationApplyEffect.NextCall, outcome.effect)
                    assertEquals(1, f.methods.count { it == "setConfig" })
                    assertEquals(JsonPrimitive(timeout), f.writeParams.single().getValue("overrides").jsonObject.getValue("callMedia")
                        .jsonObject.getValue("inactivity").jsonObject["timeoutMs"])
                } else {
                    assertSame(ConfigurationApplyOutcome.Failed, outcome)
                    assertEquals(0, f.methods.count { it == "setConfig" })
                }
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    @Test fun mediaClearAndSameValueAreAcknowledgedWithoutRestartButUnknownMediaNeedsExplicitClear() = runBlocking {
        NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.media = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-nb","framing":"be"}]}""").jsonObject
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val cleared = submit(fixture, overrides(previous).copy(callMedia = ConfigurationCallMedia()))
                assertTrue(cleared is ConfigurationApplyOutcome.Confirmed)
                assertEquals(ConfigurationCallMedia(), (cleared as ConfigurationApplyOutcome.Confirmed).instance.callMedia)
                assertEquals(JsonNull, f.writeParams.single().getValue("overrides").jsonObject["callMedia"])
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                f.mode = "unchanged"
                val same = f.pool.applyCallPolicy(S3_CONFIG_TARGET,
                    """{"expectedRevision":"$NEXT_REVISION","overrides":{"callMedia":null}}""") { true }
                assertEquals(ConfigurationCommandApplyResult.Unchanged, same.result)
                assertEquals(1, f.sockets)
            }
        }
        NextCallSocketFixture().use { f ->
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                assertNull(previous.callMedia)
                val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.BE)))
                assertSame(ConfigurationApplyOutcome.Unconfirmed, submit(fixture, overrides(previous).copy(callMedia = selected)))
                assertEquals(0, f.methods.count { it in setOf("getClientCapabilities", "setConfig") })
                assertEquals(0, NextCallCommandShadow.moduleWrites)
            }
        }
    }

    @Test fun mediaCapabilityRevocationAndStoppedOrMixedMediaChangesKeepTheOriginalOwnershipAndModuleRoute() = runBlocking {
        NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.onCapabilities = { f.allowed = false }
            f.connect()
            val result = f.pool.applyCallPolicy(S3_CONFIG_TARGET,
                """{"expectedRevision":"$INITIAL_REVISION","overrides":{"callMedia":null}}""") { f.allowed }
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, result.result)
            assertEquals(0, f.methods.count { it == "setConfig" })
        }
        for (running in listOf(false, true)) NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            NextCallCommandShadow.reset(f)
            NextCallCommandShadow.running = running
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.BE)))
                submit(fixture, overrides(previous).copy(callMedia = selected, epdgFqdn = if (running) "new.example.test" else previous.epdgFqdn))
                assertEquals(1, NextCallCommandShadow.moduleWrites)
                assertEquals(0, f.methods.count { it in setOf("setConfig", "getClientCapabilities") })
            }
        }
    }

    @Test fun localSaveValidationDoesNotRestoreACallThatEndedWhileEncoding() = runBlocking {
        assertInvalidSaveKeepsCurrentState(rebind = false)
    }

    @Test fun localSaveValidationCannotRestoreARetiredEditableRow() = runBlocking {
        assertInvalidSaveKeepsCurrentState(rebind = true)
    }

    private suspend fun assertInvalidSaveKeepsCurrentState(rebind: Boolean) = coroutineScope {
        NextCallSocketFixture().use { engine ->
            engine.runtimeCall = callWithQualityRevision(2)
            NextCallCommandShadow.reset(engine)
            engine.connect()
            S3ConfigurationIoFixture(runtimeObservationRead = { key -> engine.pool.configurationObservation(key) }).use { fixture ->
                val old = fixture.ready().instances.single()
                assertNull(old.callMedia)
                assertEquals(2L, old.runtimeFacts?.call?.media?.quality?.revision)
                val hold = S3IoHold()
                val dns = old.dnsOverrides
                val heldDns = object : Map<String, String> by dns {
                    override val entries: Set<Map.Entry<String, String>>
                        get() { hold.block(); return dns.entries }
                }
                val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.BE)))
                val desired = overrides(old).copy(dnsOverrides = heldDns, callMedia = selected)
                engine.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val completed = async(start = CoroutineStart.UNDISPATCHED) { submit(fixture, desired) }
                var pendingRefresh: S3IoHold? = null
                try {
                    hold.awaitEntered()
                    assertEquals(old.instanceId, (fixture.source.state.value as ConfigurationUiState.Ready).applyingInstanceId)
                    if (rebind) {
                        val manager = RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
                        shadowOf(manager).setActiveSubscriptionInfoList(listOf(SubscriptionInfoBuilder.newBuilder().setId(2)
                            .setSimSlotIndex(0).setIccId(S3_CONFIG_TARGET).setCarrierName("Synthetic").setMcc("001").setMnc("01")
                            .buildSubscriptionInfo()))
                        pendingRefresh = fixture.holdList()
                        fixture.notifySubscriptionsChanged()
                        assertEquals(ConfigurationUiState.Loading, fixture.source.state.value)
                        hold.release()
                        pendingRefresh.awaitEntered()
                        assertEquals(0, NextCallCommandShadow.moduleWrites)
                        assertTrue(engine.writeParams.isEmpty())
                        assertEquals("local validation must not republish a retired editable row", ConfigurationUiState.Loading, fixture.source.state.value)
                    } else {
                        engine.runtimeCall = null
                        engine.mediaChanged(1)
                        withTimeout(2_500) { fixture.source.state.first { state ->
                            (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull()?.runtimeFacts?.let { it.call == null } == true
                        } }
                    }
                } finally {
                    hold.release()
                    pendingRefresh?.release()
                }
                assertSame(ConfigurationApplyOutcome.Unconfirmed, completed.await())
                val ready = fixture.ready()
                val row = ready.instances.single()
                assertNull(ready.applyingInstanceId)
                assertEquals(old.configRevision, row.configRevision)
                assertTrue(engine.writeParams.isEmpty())
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, engine.sockets)
                if (rebind) {
                    assertNotEquals(old.instanceId, row.instanceId)
                    assertEquals(ConfigurationApplyAcceptance.Unavailable,
                        fixture.source.submit(old.instanceId, desired) { error("retired row accepted") })
                } else {
                    assertEquals(2, engine.methods.count { it == "getStatus" })
                    assertNotNull(ready.message)
                    assertEquals(old, row.copy(runtimeFacts = old.runtimeFacts))
                    assertNull("local validation must retain the later no-dialog observation", row.runtimeFacts?.call)
                }
            }
        }
    }

    private fun overrides(row: ConfigurationInstance) = ConfigurationOverrides(
        row.transport, row.smsc, row.epdgFqdn, row.imsRealm, row.dns,
        row.imsPolicy.securityOverride, row.imsPolicy.initialOverride, row.imsPolicy.countryOverride,
        row.imsPolicy.digestRealmOverride, row.dnsOverrides, row.configRevision, row.callSignaling, row.callMedia, row.tunnelSecurity, row.tunnelTiming, row.imsProtection,
    )

    @Test fun clearingMediaRestoresCatalogFactsWithoutPersistingThemOrRestarting() = runBlocking {
        NextCallSocketFixture().use { f ->
            f.mediaSupported = true
            f.media = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-wb","framing":"be"}]}""").jsonObject
            f.catalogMedia = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-nb","framing":"oa","initialMode":0,"receiveModeChangeNeighbor":false}],"packetization":{"receivePtimeMs":60,"receiveMaxPtimeMs":80}}""").jsonObject
            NextCallCommandShadow.reset(f)
            f.connect()
            S3ConfigurationIoFixture(nextCallApply = { key, payload, allowed -> f.pool.applyCallPolicy(key, payload, allowed) }).use { fixture ->
                val previous = fixture.ready().instances.single { it.instanceId == fixture.targetId }
                val outcome = submit(fixture, overrides(previous).copy(callMedia = ConfigurationCallMedia()))
                assertTrue(outcome is ConfigurationApplyOutcome.Confirmed)
                val row = (outcome as ConfigurationApplyOutcome.Confirmed).instance
                assertEquals(ConfigurationCallMedia(), row.callMedia)
                assertEquals("catalog", row.facts!!.callMedia!!.source)
                assertEquals(0, row.facts!!.callMedia!!.value!!.speechFormats.first().initialMode)
                assertEquals(ConfigurationCallPacketization(60, 80), row.facts!!.callMedia!!.value!!.packetization)
                assertNull(row.facts!!.callMedia!!.overrideValue)
                assertEquals(JsonNull, f.writeParams.single().getValue("overrides").jsonObject["callMedia"])
                assertNull(f.media)
                assertEquals(0, NextCallCommandShadow.moduleWrites)
                assertEquals(1, f.sockets)
            }
        }
    }

    private suspend fun submit(fixture: S3ConfigurationIoFixture, desired: ConfigurationOverrides): ConfigurationApplyOutcome {
        val completed = CompletableDeferred<ConfigurationApplyOutcome>()
        assertEquals(ConfigurationApplyAcceptance.Accepted, fixture.source.submit(fixture.targetId, desired) { completed.complete(it) })
        val scope = fixture.source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(fixture.source) as CoroutineScope
        withTimeout(4_000) { awaitS3ConfigurationProducer(scope, null) }
        return withTimeout(1_000) {
            // The IO job can detach before its completion handler posts to the
            // paused main looper. Drain that real callback within the same budget.
            while (!completed.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
            completed.await()
        }
    }
}

internal class NextCallSocketFixture : AutoCloseable {
    val methods = CopyOnWriteArrayList<String>()
    val writeParams = CopyOnWriteArrayList<JsonObject>()
    val capabilityParams = CopyOnWriteArrayList<JsonObject>()
    val writeStarted = CompletableDeferred<Unit>()
    var sockets = 0
    var mode = "normal"
    var writeErrorReply: JsonObject? = null
    @Volatile var runtimeCall: JsonObject? = null
    private lateinit var liveSocket: CallTestSocket
    var revision = INITIAL_REVISION
    var explicit: JsonObject = buildJsonObject { put("includePANI", true) }
    var mediaSupported = false
    var packetizationSupported = false
    var inactivitySupported = false
    var pcmuSupported = false
    var sdesSupported = false
    var media: JsonObject? = null
    var catalogMedia: JsonObject? = null
    var tunnelSupported = false
    var tunnel: JsonObject? = null
    var catalogTunnel: JsonObject? = null
    var tunnelDiagnosticLayout = false
    var timingSupported = false
    var timing: JsonObject? = null
    var catalogTiming: JsonObject? = null
    var protectionSupported = false
    var protection: JsonObject? = null
    var catalogProtection: JsonObject? = null
    var addressFamilySupported = false
    var addressFamily: String? = null
    var selectionSupported = false
    var selection: JsonObject? = null
    var catalogSelection: JsonObject? = null
    var identitySupported = false
    var identityPolicy: JsonObject? = null
    var catalogIdentityPolicy: JsonObject? = null
    var imsTimingSupported = false
    var imsTiming: JsonObject? = null
    var catalogImsTiming: JsonObject? = null
    var swuAccessSupported = false
    var swuAccessUnknown = false
    var swuAccess: JsonObject? = null
    var allowed = true
    var onCapabilities: () -> Unit = {}
    val pool: AndroidEngineClientPool = AndroidEngineClientPool {
        sockets++
        CallTestSocket { socket, line ->
            val request = Json.parseToJsonElement(line).jsonObject
            val id = request.getValue("id").jsonPrimitive.long
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            when (method) {
                "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                "getStatus" -> socket.respond(id, buildJsonObject {
                    put("configuration", buildJsonObject {
                        put("schema", 1); put("loaded", facts()); put("negotiated", buildJsonObject {})
                        runtimeCall?.let { put("call", it) }
                    })
                })
                "getClientCapabilities" -> {
                    capabilityParams += request.getValue("params").jsonObject
                    onCapabilities()
                    if (mode == "retired") pool.remove(S3_CONFIG_TARGET)?.close()
                    else {
                        if (mode == "owner_changed") allowed = false
                        socket.respond(id, capabilities())
                    }
                }
                "setConfig" -> {
                    val params = request.getValue("params").jsonObject
                    writeParams += params
                    writeStarted.complete(Unit)
                    val reply = writeErrorReply
                    if (reply != null) socket.send(JsonObject(reply + ("id" to JsonPrimitive(id))))
                    else if (mode == "conflict") socket.send(buildJsonObject {
                        put("id", id); put("error", buildJsonObject { put("code", 1001); put("message", "CONFIG_REVISION_CONFLICT") })
                    }) else if (mode != "hold_reply") {
                        val groups = params.getValue("overrides").jsonObject
                        if ("callSignaling" in groups) explicit = groups["callSignaling"] as? JsonObject ?: JsonObject(emptyMap())
                        if ("callMedia" in groups) media = groups["callMedia"] as? JsonObject
                        if (mode != "unchanged") revision = NEXT_REVISION
                        if (mode == "lost_reply") socket.close() else socket.respond(id, buildJsonObject {
                            put("state", if (mode == "unchanged") "Unchanged" else "Saved")
                            put("effect", if (mode == "wrong_effect") "restart" else "next-call")
                            put("configRevision", if (mode == "wrong_revision") INITIAL_REVISION else revision)
                            put("configuration", facts(if (mode == "wrong_group") buildJsonObject { put("includePANI", true) } else explicit))
                        })
                    }
                }
                else -> error("Unexpected request")
            }
        }.also { liveSocket = it }
    }
    suspend fun connect() { pool.clientFor(S3_CONFIG_TARGET).request("ping") }
    fun mediaChanged(sequence: Long) = liveSocket.send(buildJsonObject {
        put("event", "callMediaChanged"); put("data", buildJsonObject { put("sequence", sequence) })
    })
    suspend fun apply() = pool.applyCallPolicy(S3_CONFIG_TARGET,
        """{"expectedRevision":"$INITIAL_REVISION","overrides":{"callSignaling":{"includePANI":false}}}""") { allowed }
    override fun close() { pool.close() }

    fun facts(choices: JsonObject = explicit, mediaValue: JsonObject? = media) = buildJsonObject {
        put("schema", 1); put("configRevision", revision); put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray { DEFAULTS.forEach { (leaf, default) ->
            val parts = leaf.split('.', limit = 2)
            val override = if (parts.size == 1) choices[leaf] else (choices[parts[0]] as? JsonObject)?.get(parts[1])
            val catalogValue = if (mode == "catalog_inheritance") when (leaf) {
                "sessionTimer.expiresSeconds" -> "1200"
                "reliableProvisional.uasMode" -> "all-18x"
                "includePANI" -> "false"
                else -> null
            } else null
            add(buildJsonObject {
                put("field", "callSignaling.$leaf"); put("state", "known")
                put("source", when { override != null -> "user"; catalogValue != null -> "catalog"; else -> "default" })
                put("value", override?.jsonPrimitive?.content ?: catalogValue ?: default)
                override?.let { put("override", it.jsonPrimitive.content) }
            })
        }
            if (addressFamilySupported) add(buildJsonObject {
                put("field", "epdgAddressFamily"); put("state", "known")
                put("source", if (addressFamily == null) "default" else "user")
                put("value", addressFamily ?: "compatible")
                addressFamily?.let { put("override", it) }
            })
        })
        if (mediaSupported) put("callMedia", buildJsonObject {
            put("state", "known")
            val value = mediaValue.takeUnless { mode == "wrong_media" }
            if (value == null) {
                if (catalogMedia != null) {
                    put("source", "catalog"); put("value", catalogMedia!!)
                } else {
                    put("source", "default"); put("baseline", "amr-compatible-v1")
                }
            } else {
                put("source", "user"); put("value", value); put("override", value)
            }
        })
        if (tunnelSupported) put("tunnelSecurity", buildJsonObject {
            put("state", "known")
            val policy = tunnel ?: catalogTunnel
            if (policy == null) {
                put("source", "default"); put("baseline", "swu-compatible-v1")
            } else {
                put("source", if (tunnelDiagnosticLayout) "diagnostic" else if (tunnel != null) "user" else "catalog")
                put("value", if (tunnelDiagnosticLayout) JsonObject(policy + ("layout" to JsonPrimitive("single-combined"))) else policy)
                if (tunnel != null) put("override", tunnel!!)
            }
        })
        if (timingSupported) put("tunnelTiming", buildJsonObject {
            put("state", "known")
            if (timing == null) {
                if (catalogTiming != null) {
                    put("source", "catalog"); put("value", catalogTiming!!)
                } else {
                    put("source", "default"); put("baseline", "swu-renewal-compatible-v1")
                }
            } else {
                put("source", "user"); put("value", timing!!); put("override", timing!!)
            }
        })
        if (protectionSupported) put("imsProtection", buildJsonObject {
            put("state", "known")
            if (protection == null) {
                if (catalogProtection != null) {
                    put("source", "catalog"); put("value", catalogProtection!!)
                } else {
                    put("source", "default"); put("baseline", "ims-esp-compatible-v1")
                }
            } else {
                put("source", "user"); put("value", protection!!); put("override", protection!!)
            }
        })
        if (selectionSupported) put("epdgSelection", buildJsonObject {
            put("state", "known")
            if (selection == null) {
                if (catalogSelection != null) {
                    put("source", "catalog"); put("value", catalogSelection!!)
                } else {
                    put("source", "default"); put("baseline", "single-epdg-compatible-v1")
                }
            } else {
                put("source", "user"); put("value", selection!!); put("override", selection!!)
            }
        })
        if (imsTimingSupported) put("imsTiming", buildJsonObject {
            put("state", "known")
            if (imsTiming == null) {
                if (catalogImsTiming != null) {
                    put("source", "catalog"); put("value", catalogImsTiming!!)
                } else {
                    put("source", "default"); put("baseline", "ims-timing-compatible-v1")
                }
            } else {
                put("source", "user"); put("value", imsTiming!!); put("override", imsTiming!!)
            }
        })
        if (swuAccessSupported) put("swuAccess", buildJsonObject {
            put("state", if (swuAccessUnknown) "unknown" else "known")
            if (swuAccessUnknown) put("source", "user")
            else if (swuAccess == null) {
                put("source", "default"); put("baseline", "swu-ims-access-compatible-v1")
            } else {
                put("source", "user"); put("value", swuAccess!!); put("override", swuAccess!!)
            }
        })
        if (identitySupported) put("tunnelIdentity", buildJsonObject {
            put("state", "known")
            val policy = identityPolicy ?: catalogIdentityPolicy
            if (policy == null) {
                put("source", "default"); put("baseline", "swu-identity-compatible-v1")
            } else {
                val types = buildJsonObject {
                    put("localType", policy.getValue("localType")); put("requestType", policy.getValue("requestType"))
                    put("peerType", (policy["peer"] as? JsonObject)?.get("type")?.jsonPrimitive?.content.orEmpty())
                }
                put("source", if (identityPolicy != null) "user" else "catalog"); put("value", types)
                if (identityPolicy != null) put("override", types)
            }
        })
    }
    private fun capabilities(): JsonObject {
        val ids = (listOf("config.call-signaling.next-call.v1", "config.metadata.v1", "rpc.getConfig", "rpc.getStatus", "rpc.ping", "rpc.setConfig", "rpc.stop") +
            (if (mediaSupported && mode != "media_unsupported") listOf("config.call-media.next-call.v1") else emptyList()) +
            (if (packetizationSupported) listOf("config.call-media.packetization.v1") else emptyList()) +
            (if (inactivitySupported) listOf("config.call-media.inactivity.v1") else emptyList()) +
            (if (pcmuSupported) listOf("config.call-media.pcmu.v1") else emptyList()) +
            (if (sdesSupported) listOf("config.call-media.sdes.v1") else emptyList())).sorted()
            .let { if (mode == "legacy") it.filterNot { value -> value in setOf("config.call-signaling.next-call.v1", "rpc.setConfig") } else it }
            .let { if (mode == "unsorted") it.reversed() else it }
        return buildJsonObject {
            put("schema", 1); put("protocol", buildJsonObject { put("version", 1); put("engineVersion", "0.1.0") })
            put("instance", buildJsonObject { put("key", "00647499b77a2163531b4fa05e1014fb") })
            put("capabilities", JsonArray(ids.map(::JsonPrimitive)))
            put("configMetadata", Json.parseToJsonElement("""[{"field":"imsSipSecurity","value":"tcp_ipsec_3gpp","source":"default","decision":"inherited"},{"field":"paniInitial","value":"absent","source":"default","decision":"inherited"},{"field":"paniCountry","value":"","source":"home_mcc","decision":"inherited"},{"field":"imsDigestRealm","value":"","source":"identity","decision":"inherited"}]"""))
        }
    }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class NextCallCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        val original: AndroidSuResult = S3ConfigurationCommandShadow().run(command)
        if (command == ConfigurationControlCommand.listLine()) return listReadbackFailure.getAndSet(null) ?: original
        if (command != ConfigurationControlCommand.showLine(S3_CONFIG_TARGET)) return original
        val root = Json.parseToJsonElement(original.output).jsonObject
        val shownRevision = if (engine.mode == "readback_mismatch") INITIAL_REVISION else engine.revision
        val facts = JsonObject(engine.facts(mediaValue = engine.media.takeUnless { engine.mode == "wrong_disk_media" }) +
            ("configRevision" to JsonPrimitive(shownRevision)))
        return original.copy(output = JsonObject(root + engine.identityPolicy?.let { mapOf("tunnelIdentityOverride" to it) }.orEmpty() + mapOf(
            "lifecycle" to JsonPrimitive(if (running) "running" else "stopped"),
            "configRevision" to JsonPrimitive(shownRevision), "configuration" to facts,
        )).toString())
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertEquals(ConfigurationControlCommand.applyLine(S3_CONFIG_TARGET), command)
        moduleWrites++
        val payload = Json.parseToJsonElement(stdinText).jsonObject
        modulePayloads += payload
        if (engine.mode in setOf("module_access_saved", "module_access_mismatch", "module_access_partial_signaling")) {
            val groups = payload.getValue("overrides").jsonObject
            if (engine.mode != "module_access_mismatch") {
                engine.swuAccess = groups["swuAccess"] as? JsonObject
                engine.swuAccessUnknown = false
            }
            if (engine.mode == "module_access_saved" && "callSignaling" in groups)
                engine.explicit = groups["callSignaling"] as? JsonObject ?: buildJsonObject {}
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_ims_timing_saved", "module_ims_timing_mismatch")) {
            if (engine.mode == "module_ims_timing_saved") engine.imsTiming = payload.getValue("overrides").jsonObject["imsTiming"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_identity_saved", "module_identity_mismatch")) {
            if (engine.mode == "module_identity_saved") engine.identityPolicy = payload.getValue("overrides").jsonObject["tunnelIdentity"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_selection_saved", "module_selection_mismatch")) {
            if (engine.mode == "module_selection_saved") engine.selection = payload.getValue("overrides").jsonObject["epdgSelection"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_family_saved", "module_family_mismatch")) {
            if (engine.mode == "module_family_saved") engine.addressFamily =
                payload.getValue("overrides").jsonObject["epdgAddressFamily"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_protection_saved", "module_protection_mismatch")) {
            if (engine.mode == "module_protection_saved") engine.protection = payload.getValue("overrides").jsonObject["imsProtection"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_timing_saved", "module_timing_mismatch")) {
            if (engine.mode == "module_timing_saved") engine.timing = payload.getValue("overrides").jsonObject["tunnelTiming"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        if (engine.mode in setOf("module_tunnel_saved", "module_tunnel_mismatch")) {
            if (engine.mode == "module_tunnel_saved") engine.tunnel = payload.getValue("overrides").jsonObject["tunnelSecurity"] as? JsonObject
            engine.revision = if (engine.revision == INITIAL_REVISION) NEXT_REVISION else INITIAL_REVISION
            return AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"saved"}""", false)
        }
        return AndroidSuResult(false, 1, """{"ok":false,"code":"IO_FAILED","message":"config transaction failed"}""", false)
    }
    companion object {
        lateinit var engine: NextCallSocketFixture
        var moduleWrites = 0
        val modulePayloads = CopyOnWriteArrayList<JsonObject>()
        val listReadbackFailure = AtomicReference<AndroidSuResult?>()
        var running = true
        fun reset(value: NextCallSocketFixture) {
            engine = value; moduleWrites = 0; modulePayloads.clear(); listReadbackFailure.set(null); running = true
        }
    }
}

private const val INITIAL_REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
private const val NEXT_REVISION = "r1_ponmlkjihgfedcbaponmlkjihgfedcba"
private const val TIMING_GROUP = """{"ike":{"softSeconds":1200,"hardSeconds":1800,"jitterSeconds":30},"child":{"softSeconds":1500,"hardSeconds":2100,"jitterSeconds":30},"reauthSeconds":0,"dpdSeconds":20,"natKeepaliveSeconds":20,"natDpdMarginSeconds":150,"retransmit":{"retries":5,"initialMillis":4000,"maximumMillis":0,"backoffFactor":1.8}}"""
private const val TUNNEL_GROUP = """{"ikeSuites":["ike-cbc256-sha256-prfsha256-dh14"],"childSuites":["esp-gcm128-noesn"],"layout":"multiple-complete"}"""
private val DEFAULTS = linkedMapOf(
    "sessionTimer.enabled" to "true", "sessionTimer.expiresSeconds" to "1800", "sessionTimer.minimumSeconds" to "90",
    "sessionTimer.refresher" to "uac", "sessionTimer.refreshMethod" to "update", "sessionTimer.requirePeer" to "false",
    "reliableProvisional.uacEnabled" to "true", "reliableProvisional.requirePeer" to "false", "reliableProvisional.uasMode" to "disabled",
    "preconditions.uacEnabled" to "true", "preconditions.uasEnabled" to "false", "preconditions.requirePeer" to "false", "preconditions.wifiAllowed" to "true",
    "addressing.requestURI" to "preserve", "addressing.phoneContext" to "home-domain", "addressing.contactTransport" to "omit", "includePANI" to "true",
)
