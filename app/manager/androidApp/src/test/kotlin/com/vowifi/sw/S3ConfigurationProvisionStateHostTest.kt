@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.model.ConfigurationFormatState
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [33], shadows = [ConfigurationProvisionCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationProvisionStateHostTest {
    @Test fun fixtureWaitsForOperationReleaseAfterTheProducerHasDetached() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val source = fixture.source
            val gate = source.javaClass.getDeclaredField("operationGate").apply { isAccessible = true }.get(source)
            val mutex = gate.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(gate) as Mutex
            val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
            mutex.lock()
            var held = true
            try {
                assertTrue("只模拟已离开 parent tree 的完成尾段", scope.coroutineContext[Job]!!.children.none())
                val awaiting = async(start = CoroutineStart.UNDISPATCHED) { fixture.joinProducer() }
                assertFalse("夹具不得在真实操作门仍被占用时声称完成", awaiting.isCompleted)
                mutex.unlock()
                held = false
                withTimeout(3000) { awaiting.await() }
            } finally { if (held) mutex.unlock() }
        }
    }

    @Test fun createdCompletionLeavesDnsBlankForTheLiveNetworkWithoutApplying() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                fixture.createHold.release()
                assertEquals(ConfigurationProvisionOutcome.Created, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals("", ready.instances.single().dns)
                assertTrue(ready.instances.single().dnsOverrides.isEmpty())
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                assertEquals("新建配置不得写入 DNS 覆盖", 0, ConfigurationProvisionCommandShadow.applies.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun existingConfiguredTargetCompletesUnchangedWithoutRecreatingIt() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            fixture.source.provision(fixture.target)
            fixture.createHold.awaitEntered()
            fixture.createHold.release()
            val created = fixture.finished(fixture.target).completions.getValue(fixture.target)
            val readback = fixture.holdReadback()
            try {
                fixture.source.provision(fixture.target)
                readback.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertEquals(created, fixture.source.provisioning.value.completions.getValue(fixture.target))
                readback.release()
                val completed = fixture.finished(fixture.target).completions.getValue(fixture.target)
                assertEquals(ConfigurationProvisionOutcome.Unchanged, completed.outcome)
                assertTrue(completed.sequence > created.sequence)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { readback.release() }
        }
    }

    @Test fun pendingSurvivesCreateAcknowledgementUntilActualCatalogReadback() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val readback = fixture.holdReadback()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.createHold.release()
                readback.awaitEntered()
                assertEquals(ConfigurationUiState.Empty, fixture.source.state.value)
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshIdentitiesAndAwait() })
                readback.release()
                assertEquals(ConfigurationProvisionOutcome.Created, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release(); readback.release() }
        }
    }

    @Test fun knownRootFailureAndUnconfirmedWriteHaveDistinctTypedTerminalsWithoutReplay() = runBlocking {
        for ((result, expected) in listOf(
            AndroidSuResult(false, 1, "{\"ok\":false,\"code\":\"IDENTITY_MISMATCH\",\"message\":\"identity mismatch\"}", false) to ConfigurationProvisionOutcome.Failed,
            AndroidSuResult(true, -1, "", false) to ConfigurationProvisionOutcome.Unconfirmed,
            AndroidSuResult(false, 1, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false) to ConfigurationProvisionOutcome.Unconfirmed,
            AndroidSuResult(false, 1, "not a command receipt", false) to ConfigurationProvisionOutcome.Unconfirmed,
        )) {
            ConfigurationProvisionHostFixture().use { fixture ->
                fixture.ready()
                ConfigurationProvisionCommandShadow.createResult = result
                try {
                    fixture.source.provision(fixture.target)
                    fixture.createHold.awaitEntered()
                    assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                    fixture.createHold.release()
                    val completed = fixture.finished(fixture.target)
                    assertTrue(completed.pending.isEmpty())
                    assertEquals(expected, completed.completions.getValue(fixture.target).outcome)
                    assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                } finally { fixture.createHold.release() }
            }
        }
    }

    @Test fun missingTargetInReadbackCannotClaimCreatedEvenIfOtherRowsAreReady() = runBlocking {
        ConfigurationProvisionHostFixture(includeOther = true).use { fixture ->
            fixture.ready()
            ConfigurationProvisionCommandShadow.created.add(CONFIGURATION_PROVISION_OTHER)
            ConfigurationProvisionCommandShadow.hideTarget = true
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.createHold.release()
                assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun anotherHandlesLastCompletionSurvivesPendingAndAutomaticWorkPublishesNoUserCompletion() = runBlocking {
        ConfigurationProvisionHostFixture(includeOther = true).use { fixture ->
            fixture.ready()
            fixture.source.provision(fixture.target)
            fixture.createHold.awaitEntered()
            fixture.createHold.release()
            val first = fixture.finished(fixture.target).completions.getValue(fixture.target)
            val secondHold = fixture.holdCreate()
            ConfigurationProvisionCommandShadow.createResult = AndroidSuResult(false, 1,
                "{\"ok\":false,\"code\":\"IDENTITY_MISMATCH\",\"message\":\"identity mismatch\"}", false)
            try {
                fixture.source.provision(fixture.other)
                secondHold.awaitEntered()
                assertEquals(setOf(fixture.other), fixture.source.provisioning.value.pending)
                assertEquals(first, fixture.source.provisioning.value.completions.getValue(fixture.target))
                fixture.source.provision(fixture.target)
                assertEquals(2, ConfigurationProvisionCommandShadow.creates.get())
                secondHold.release()
                val completed = fixture.finished(fixture.other)
                assertEquals(first, completed.completions.getValue(fixture.target))
                assertEquals(ConfigurationProvisionOutcome.Failed, completed.completions.getValue(fixture.other).outcome)
                assertTrue(completed.completions.getValue(fixture.other).sequence > first.sequence)
                withTimeout(3000) { fixture.source.refreshAndAwait() }
                assertEquals(completed.completions, fixture.source.provisioning.value.completions)
                assertTrue(fixture.source.provisioning.value.pending.isEmpty())
            } finally { secondHold.release() }
        }
    }

    @Test fun automaticBatchReportsRealPendingWithoutAUserCompletionEvent() = runBlocking {
        ConfigurationProvisionHostFixture(automatic = true).use { fixture ->
            val readback = fixture.holdReadback()
            try {
                fixture.createHold.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                fixture.createHold.release()
                readback.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                readback.release()
                // Ready is published before the refresh completion callback clears pending.
                // Await the public terminal, not a snapshot of internal scope children.
                withTimeout(3000) {
                    fixture.source.state.first { it is ConfigurationUiState.Ready }
                    fixture.source.provisioning.first { it.pending.isEmpty() }
                }
                assertTrue(fixture.source.provisioning.value.pending.isEmpty())
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release(); readback.release() }
        }
    }

    @Test fun automaticCreatingStateDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertAutomaticCreateKeepsLaterRuntime(AutomaticReadbackOrder.Pending)
    }

    @Test fun automaticCreateReadbackDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertAutomaticCreateKeepsLaterRuntime(AutomaticReadbackOrder.EventFirst)
    }

    @Test fun aLaterNoDialogObservationCanPublishAfterAutomaticCreateReadback() = runBlocking {
        assertAutomaticCreateKeepsLaterRuntime(AutomaticReadbackOrder.CatalogFirst)
    }

    private enum class AutomaticReadbackOrder { Pending, EventFirst, CatalogFirst }

    private suspend fun assertAutomaticCreateKeepsLaterRuntime(order: AutomaticReadbackOrder) = coroutineScope {
        NextCallSocketFixture().use { peer ->
            peer.runtimeCall = buildJsonObject {
                put("configRevision", peer.revision); put("sessionSeconds", 1800); put("sessionRefresher", "local")
            }
            peer.pool.clientFor(CONFIGURATION_PROVISION_OTHER).request("ping")
            val armed = AtomicBoolean(false)
            val catalogReceived = CompletableDeferred<Unit>()
            val allowCatalogPublish = CompletableDeferred<Unit>()
            val eventReceived = CompletableDeferred<Unit>()
            val allowEventPublish = CompletableDeferred<Unit>()
            val pending = order == AutomaticReadbackOrder.Pending
            ConfigurationProvisionHostFixture(includeOther = true, runningOther = true, runtimeRead = { key ->
                assertEquals(CONFIGURATION_PROVISION_OTHER, key)
                val observation = peer.pool.configurationObservation(key)
                val facts = observation?.facts
                if (armed.get() && facts != null) {
                    val created = S3_CONFIG_TARGET in ConfigurationProvisionCommandShadow.created
                    // Gate App publication only after the same socket's complete response.
                    if (facts.call != null && created != pending && catalogReceived.complete(Unit)) allowCatalogPublish.await()
                    if (facts.call == null && eventReceived.complete(Unit) && order == AutomaticReadbackOrder.CatalogFirst) allowEventPublish.await()
                }
                observation
            }).use { fixture ->
                fixture.ready()
                fun other() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId.value == fixture.other.value }
                val before = other()
                assertEquals(ConfigurationLifecycle.RUNNING, before.lifecycle)
                assertEquals(1800L, before.runtimeFacts?.call?.sessionSeconds)
                assertEquals(setOf(CONFIGURATION_PROVISION_OTHER), ConfigurationProvisionCommandShadow.created.toSet())
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                peer.pool.onRuntimeFactsChange = { key, hint -> fixture.source.runtimeFactsChanged(key, hint) }
                armed.set(true)
                val refresh = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                var pendingFacts: ConfigurationRuntimeFacts? = null
                try {
                    if (!pending) {
                        fixture.createHold.awaitEntered()
                        assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                        fixture.createHold.release()
                    }
                    withTimeout(2_500) {
                        catalogReceived.await()
                        assertEquals(!pending, S3_CONFIG_TARGET in ConfigurationProvisionCommandShadow.created)
                        peer.runtimeCall = null
                        peer.mediaChanged(1)
                        eventReceived.await()
                        if (order != AutomaticReadbackOrder.CatalogFirst) fixture.source.state.first { state ->
                            (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == before.instanceId }
                                ?.runtimeFacts?.let { it.call == null } == true
                        }
                    }
                    allowCatalogPublish.complete(Unit)
                    if (pending) {
                        fixture.createHold.awaitEntered()
                        assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                        assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                        assertEquals("正在自动创建配置", (fixture.source.state.value as ConfigurationUiState.Ready).message)
                        pendingFacts = checkNotNull(other().runtimeFacts)
                        fixture.createHold.release()
                    }
                    assertEquals(RefreshResult.Completed, withTimeout(3_000) { refresh.await() })
                    if (order == AutomaticReadbackOrder.CatalogFirst) assertNotNull(other().runtimeFacts?.call)
                } finally {
                    allowCatalogPublish.complete(Unit)
                    allowEventPublish.complete(Unit)
                    fixture.createHold.release()
                }
                fixture.joinProducer()
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                val target = ready.instances.single { it.instanceId.value == fixture.target.value }
                assertEquals("配置已自动创建", ready.message)
                assertEquals(ConfigurationFormatState.CURRENT, target.formatState)
                assertEquals(ConfigurationLifecycle.STOPPED, target.lifecycle)
                assertTrue(validConfigRevision(target.configRevision))
                assertNull(target.runtimeFacts)
                assertEquals(setOf(S3_CONFIG_TARGET, CONFIGURATION_PROVISION_OTHER), ConfigurationProvisionCommandShadow.created.toSet())
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                assertEquals(0, ConfigurationProvisionCommandShadow.applies.get())
                assertTrue(fixture.source.provisioning.value.pending.isEmpty())
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                val unchanged = other()
                assertEquals(before, unchanged.copy(runtimeFacts = before.runtimeFacts))
                assertEquals(peer.revision, unchanged.runtimeFacts?.loaded?.configRevision)
                assertEquals(4, peer.methods.count { it == "getStatus" })
                assertTrue(peer.writeParams.isEmpty())
                assertEquals(1, peer.sockets)
                val observed = checkNotNull(if (pending) pendingFacts else unchanged.runtimeFacts)
                assertNull("automatic creation must not overwrite the other card's later no-dialog observation", observed.call)
                assertNull(unchanged.runtimeFacts?.call)
            }
        }
    }

    @Test fun closeCancelsAcceptedProvisionAndLateIoCannotPublishCreated() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.source.close()
                val cancelled = fixture.source.provisioning.value
                assertTrue(cancelled.pending.isEmpty())
                assertEquals(ConfigurationProvisionOutcome.Cancelled, cancelled.completions.getValue(fixture.target).outcome)
                fixture.createHold.release()
                fixture.joinProducer()
                assertEquals(cancelled, fixture.source.provisioning.value)
                assertEquals(ConfigurationUiState.Empty, fixture.source.state.value)
                fixture.source.provision(fixture.target)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun aRetiredAutomaticBatchCannotSubmitTheSecondCard() = runBlocking {
        assertAutomaticBatchUsesTheNewGeneration(firstUnknown = false)
    }

    @Test fun theNextGenerationCanStartAnUnsentCardWithoutReplayingAnUnknownCreate() = runBlocking {
        assertAutomaticBatchUsesTheNewGeneration(firstUnknown = true)
    }

    @Test fun automaticCandidatePreparedByTheOldGenerationIsOnlySentByTheNewOne() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            fixture.createHold.release()
            val identity = RootFrameworkShadow.holdSubscriptionExtras()
            val old = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            var nextCandidate: S3IoHold? = null
            val startedByOld: Int
            try {
                identity.awaitEntered()
                assertEquals(0, ConfigurationProvisionCommandShadow.creates.get())
                nextCandidate = fixture.holdCandidateRead()
                fixture.rebindTarget()
                identity.release()
                assertEquals(RefreshResult.Cancelled, withTimeout(3_000) { old.await() })
                nextCandidate.awaitEntered()
                startedByOld = ConfigurationProvisionCommandShadow.creates.get()
                assertTrue(ConfigurationProvisionCommandShadow.created.isEmpty())
            } finally {
                identity.release()
                nextCandidate?.release()
            }
            fixture.ready()
            assertEquals("identity preparation of a retired generation must not reach Root", 0, startedByOld)
            assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            assertEquals(setOf(S3_CONFIG_TARGET), ConfigurationProvisionCommandShadow.created.toSet())
            assertEquals(ConfigurationProvisionState(), fixture.source.provisioning.value)
        }
    }

    private suspend fun assertAutomaticBatchUsesTheNewGeneration(firstUnknown: Boolean) = coroutineScope {
        ConfigurationProvisionHostFixture(includeOther = true).use { fixture ->
            fixture.ready()
            if (firstUnknown) ConfigurationProvisionCommandShadow.targetCreateResult = AndroidSuResult(true, -1, "", false)
            val old = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            fixture.createHold.awaitEntered()
            val nextCandidate = fixture.holdCandidateRead()
            val startedByOld: Int
            val savedByOld: Set<String>
            try {
                assertEquals(setOf(fixture.target, fixture.other), fixture.source.provisioning.value.pending)
                fixture.rebindTarget()
                assertEquals(ConfigurationUiState.Loading, fixture.source.state.value)
                fixture.createHold.release()
                assertEquals(RefreshResult.Cancelled, withTimeout(3_000) { old.await() })
                nextCandidate.awaitEntered()
                startedByOld = ConfigurationProvisionCommandShadow.creates.get()
                savedByOld = ConfigurationProvisionCommandShadow.created.toSet()
                assertEquals(ConfigurationUiState.Loading, fixture.source.state.value)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
            } finally {
                fixture.createHold.release()
                nextCandidate.release()
            }
            fixture.ready()
            val expectedOld = if (firstUnknown) emptySet() else setOf(S3_CONFIG_TARGET)
            assertEquals("a retired automatic batch must not submit its second card", 1, startedByOld)
            assertEquals(expectedOld, savedByOld)
            assertEquals(expectedOld + CONFIGURATION_PROVISION_OTHER, ConfigurationProvisionCommandShadow.created.toSet())
            assertEquals(2, ConfigurationProvisionCommandShadow.creates.get())
            assertEquals(0, ConfigurationProvisionCommandShadow.applies.get())
            assertEquals(ConfigurationProvisionState(), fixture.source.provisioning.value)
        }
    }

    @Test fun unknownHandleCannotInventPendingCompletionOrExecuteCreate() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val before = fixture.source.provisioning.value
            fixture.source.provision(SimHandle("not-in-handle-store"))
            fixture.joinProducer()
            assertEquals(before, fixture.source.provisioning.value)
            assertEquals(0, ConfigurationProvisionCommandShadow.creates.get())
        }
    }
}

internal const val CONFIGURATION_PROVISION_OTHER = "0000000000000000002"

internal class ConfigurationProvisionHostFixture(
    includeOther: Boolean = false,
    automatic: Boolean = false,
    runningOther: Boolean = false,
    runtimeRead: suspend (String) -> ConfigurationRuntimeObservation? = { null },
) : AutoCloseable {
    val handles = OpaqueIccidHandleStore()
    val target = handles.handleFor(S3_CONFIG_TARGET)
    val other = handles.handleFor(CONFIGURATION_PROVISION_OTHER)
    private val suppressAutomatic = AtomicBoolean(!automatic)
    private val nextCandidateRead = AtomicReference<S3IoHold?>()
    private lateinit var subscriptionsChanged: () -> Unit
    private val holds = mutableListOf<S3IoHold>()
    val createHold: S3IoHold
    val source: AndroidConfigurationSource
    init {
        ConfigurationProvisionCommandShadow.reset()
        if (runningOther) {
            ConfigurationProvisionCommandShadow.runningOther = true
            ConfigurationProvisionCommandShadow.created.add(CONFIGURATION_PROVISION_OTHER)
        }
        createHold = holdCreate()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        val keys = if (includeOther) listOf(S3_CONFIG_TARGET, CONFIGURATION_PROVISION_OTHER) else listOf(S3_CONFIG_TARGET)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(keys.mapIndexed { index, key ->
            SubscriptionInfoBuilder.newBuilder().setId(index + 1).setSimSlotIndex(index).setIccId(key)
                .setMcc("001").setMnc("01").buildSubscriptionInfo()
        })
        val real = AndroidSubscriptionInventory(context)
        val inventory = object : SubscriptionInventory by real {
            override fun listActive(): SubscriptionInventoryResult {
                nextCandidateRead.getAndSet(null)?.block()
                return if (suppressAutomatic.get()) SubscriptionInventoryResult.Failure("无法读取活动订阅") else real.listActive()
            }
            override fun startWatching(onChanged: () -> Unit): SubscriptionWatch {
                subscriptionsChanged = onChanged
                return real.startWatching(onChanged)
            }
        }
        source = AndroidConfigurationSource(context, handles, inventory,
            readNetworkOutlets = { com.vowifi.sw.manager.model.ConfigurationNetworkOutlets(supported = true) },
            readRuntimeFacts = runtimeRead)
    }
    suspend fun ready() {
        withTimeout(3000) { source.state.first { it != ConfigurationUiState.Loading } }
        joinProducer()
        suppressAutomatic.set(false)
    }
    suspend fun joinProducer() = withTimeout(3000) {
        val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
        val completion = synchronized(lock) {
            val flight = source.javaClass.getDeclaredField("refreshInFlight").apply { isAccessible = true }.get(source)
            flight?.let { it.javaClass.getDeclaredField("completion").apply { isAccessible = true }.get(it) as Deferred<*> }
        }
        val gate = source.javaClass.getDeclaredField("operationGate").apply { isAccessible = true }.get(source)
        val mutex = gate.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(gate) as Mutex
        awaitS3ConfigurationProducer(scope, completion, mutex)
    }
    suspend fun finished(handle: SimHandle): ConfigurationProvisionState = withTimeout(3000) {
        source.provisioning.first { handle !in it.pending && handle in it.completions }
    }
    fun holdCreate() = S3IoHold().also { holds += it; ConfigurationProvisionCommandShadow.createHold.set(it) }
    fun holdCandidateRead() = S3IoHold().also { holds += it; nextCandidateRead.set(it) }
    fun rebindTarget() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
        val target = SubscriptionInfoBuilder.newBuilder().setId(3).setSimSlotIndex(0).setIccId(S3_CONFIG_TARGET)
            .setMcc("001").setMnc("01").buildSubscriptionInfo()
        ConfigurationProvisionCommandShadow.targetSubId = 3
        shadowOf(manager).setActiveSubscriptionInfoList(listOf(target) + manager.activeSubscriptionInfoList.orEmpty().filter { it.simSlotIndex == 1 })
        subscriptionsChanged()
    }
    fun holdReadback() = S3IoHold().also { holds += it; ConfigurationProvisionCommandShadow.readbackHold.set(it) }
    override fun close() { holds.forEach { it.release() }; source.close() }
}

// 系统命令边界提供受控回执；source / identityReader / provisioner / catalog 均为生产实现。
@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class ConfigurationProvisionCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        if (command == ConfigurationControlCommand.listLine()) {
            if (created.isNotEmpty()) readbackHold.getAndSet(null)?.block()
            val keys = created.toList().sorted().filterNot { hideTarget && it == S3_CONFIG_TARGET }
            return success(buildJsonArray { keys.forEach { key -> add(buildJsonObject {
                val running = runningOther && key == CONFIGURATION_PROVISION_OTHER
                put("ok", true); put("iccid", key); put("state", if (running) "running" else "stopped")
                if (running) put("pid", 4242)
            }) } }.toString())
        }
        val result: AndroidSuResult = TwoCardConfigurationCommandShadow().run(command)
        return if (runningOther && command == ConfigurationControlCommand.showLine(CONFIGURATION_PROVISION_OTHER))
            result.copy(output = result.output.replace("\"lifecycle\":\"stopped\"", "\"lifecycle\":\"running\"")) else result
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertTrue(stdinText.isNotBlank())
        if (command == ConfigurationControlCommand.applyLine(S3_CONFIG_TARGET)) {
            assertEquals(ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS, timeoutSeconds)
            applies.incrementAndGet()
            return success("{\"ok\":true}")
        }
        val key = when (command) {
            ConfigurationControlCommand.createLine(1, 0), ConfigurationControlCommand.createLine(3, 0) -> S3_CONFIG_TARGET
            ConfigurationControlCommand.createLine(2, 1) -> CONFIGURATION_PROVISION_OTHER
            else -> error("Unexpected provisioning command")
        }
        assertEquals(ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS, timeoutSeconds)
        creates.incrementAndGet()
        if (key == S3_CONFIG_TARGET && command != ConfigurationControlCommand.createLine(targetSubId, 0)) {
            return AndroidSuResult(false, 18,
                """{"ok":false,"code":"IDENTITY_UNREADABLE","message":"SIM identity could not be read"}""", false)
        }
        createHold.getAndSet(null)?.block()
        val result = targetCreateResult?.takeIf { key == S3_CONFIG_TARGET } ?: createResult
        if (!result.timedOut && result.exitCode == 0 && !result.writeFailed && !result.overflowed) created.add(key)
        return result
    }
    private fun success(text: String) = AndroidSuResult(false, 0, text, false)
    companion object {
        val created = ConcurrentHashMap.newKeySet<String>()
        val creates = AtomicInteger()
        val applies = AtomicInteger()
        val createHold = AtomicReference<S3IoHold?>()
        val readbackHold = AtomicReference<S3IoHold?>()
        @Volatile var runningOther = false
        @Volatile var hideTarget = false
        @Volatile var targetSubId = 1
        @Volatile var targetCreateResult: AndroidSuResult? = null
        @Volatile var createResult = AndroidSuResult(false, 0, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false)
        fun reset() {
            created.clear(); creates.set(0); applies.set(0)
            createHold.set(null); readbackHold.set(null)
            runningOther = false
            hideTarget = false
            targetSubId = 1
            targetCreateResult = null
            createResult = AndroidSuResult(false, 0, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false)
        }
    }
}
