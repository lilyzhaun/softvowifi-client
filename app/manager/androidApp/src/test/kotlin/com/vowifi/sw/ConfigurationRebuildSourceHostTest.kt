@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
@Config(sdk = [33], shadows = [RebuildSourceCommandShadow::class, RootFrameworkShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class ConfigurationRebuildSourceHostTest {
    @Test fun settledWaitsForActualOperationReleaseBeforeAcceptingRebuild() = runBlocking {
        RebuildFixture().use { fixture ->
            fixture.ready()
            val gate = fixture.source.javaClass.getDeclaredField("operationGate").apply { isAccessible = true }.get(fixture.source)
            val mutex = gate.javaClass.getDeclaredField("mutex").apply { isAccessible = true }.get(gate) as Mutex
            mutex.lock()
            val settled = async(start = CoroutineStart.UNDISPATCHED) { fixture.settled() }
            try {
                assertFalse("夹具不能以空children/Ready认实际操作门已释放", settled.isCompleted)
            } finally {
                mutex.unlock()
            }
            withTimeout(5000) { settled.await() }
            assertTrue(fixture.rebuild())
            assertEquals(ConfigurationProvisionOutcome.Updated, fixture.completed().outcome)
            assertEquals(1, RebuildSourceCommandShadow.writes.size)
        }
    }

    @Test fun inactiveFileIsNotReadAndUsesTheNormalFormatGateWhenItsCardBecomesActive() = runBlocking {
        for (malformed in listOf(false, true)) {
            RebuildFixture(retained = true, malformedRetained = malformed).use { fixture ->
                fixture.ready()
                assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
                assertFalse(RebuildSourceCommandShadow.reads.contains(RETAINED_TEST_KEY))
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
                fixture.activateRetained()
                fixture.settled()
                val refreshed = fixture.source.refreshAndAwait()
                if (malformed) assertTrue(refreshed is RefreshResult.Failed)
                else assertEquals(RefreshResult.Completed, refreshed)
                assertTrue(RebuildSourceCommandShadow.reads.contains(RETAINED_TEST_KEY))
                if (malformed) assertTrue(fixture.source.state.value is ConfigurationUiState.Failure)
                else {
                    val rows = (fixture.source.state.value as ConfigurationUiState.Ready).instances
                    assertEquals(2, rows.size)
                    assertTrue(rows.all { it.formatState == ConfigurationFormatState.LEGACY })
                }
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            }
        }
    }

    @Test fun refreshNeverRebuildsButExplicitStoppedActionUsesFreshCandidateAndReadsBack() = runBlocking {
        RebuildFixture().use { fixture ->
            fixture.ready()
            assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            fixture.source.refreshIdentitiesAndAwait()
            assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            assertTrue(fixture.rebuild())
            val completion = fixture.completed()
            assertEquals(ConfigurationProvisionOutcome.Updated, completion.outcome)
            assertEquals(1, RebuildSourceCommandShadow.writes.size)
            val (command, payload) = RebuildSourceCommandShadow.writes.single()
            assertTrue(command.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
            assertFalse(payload.contains("retired.example.test"))
            assertFalse(payload.contains("retired-dns"))
            assertFalse(payload.contains("configRevision"))
            assertEquals(ConfigurationFormatState.CURRENT, fixture.instance().formatState)
        }
    }

    @Test fun lostWriteResponseReadsBackWithoutReplayingOrPretendingConfirmation() = runBlocking {
        RebuildFixture().use { fixture ->
            fixture.ready()
            RebuildSourceCommandShadow.loseResponse = true
            assertTrue(fixture.rebuild())
            assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.completed().outcome)
            assertEquals(ConfigurationFormatState.CURRENT, fixture.instance().formatState)
            fixture.source.refreshAndAwait()
            assertEquals(1, RebuildSourceCommandShadow.writes.size)
            assertFalse(fixture.rebuild())
        }
    }

    @Test fun rebuildingAStoppedCardDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertRebuildKeepsLaterRuntime(eventPublishedFirst = true)
    }

    @Test fun aLaterNoDialogObservationCanPublishAfterRebuildingTheOtherCard() = runBlocking {
        assertRebuildKeepsLaterRuntime(eventPublishedFirst = false)
    }

    @Test fun anUnconfirmedRebuildDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertRebuildKeepsLaterRuntime(eventPublishedFirst = true, loseReply = true)
    }

    @Test fun aRebuildResourceRefusalDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertRebuildRefusalKeepsRuntime(holdPreflight = false)
    }

    @Test fun showingPendingRebuildDoesNotRestoreACallEndedDuringCandidateRead() = runBlocking {
        assertRebuildRefusalKeepsRuntime(holdPreflight = true)
    }

    private suspend fun assertRebuildRefusalKeepsRuntime(holdPreflight: Boolean) {
        NextCallSocketFixture().use { peer ->
            peer.runtimeCall = buildJsonObject {
                put("configRevision", peer.revision); put("sessionSeconds", 1800); put("sessionRefresher", "local")
            }
            peer.pool.clientFor(RETAINED_TEST_KEY).request("ping")
            RebuildFixture(runningRetained = true, runtimeRead = { key ->
                assertEquals(RETAINED_TEST_KEY, key)
                peer.pool.configurationObservation(key)
            }).use { fixture ->
                fixture.ready()
                val target = fixture.instance()
                val other = fixture.retainedInstance()
                assertEquals(ConfigurationFormatState.LEGACY, target.formatState)
                assertEquals(ConfigurationLifecycle.STOPPED, target.lifecycle)
                assertEquals(ConfigurationLifecycle.RUNNING, other.lifecycle)
                assertEquals(1800L, other.runtimeFacts?.call?.sessionSeconds)
                peer.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                val refusal = fixture.holdResourceRefusal()
                val preflight = if (holdPreflight) fixture.holdCandidateRead() else null
                val published = try {
                    assertTrue(fixture.rebuild())
                    (preflight ?: refusal).awaitEntered()
                    peer.runtimeCall = null
                    peer.mediaChanged(1)
                    val ended = withTimeout(2_500) {
                        fixture.source.state.first { state ->
                            (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == other.instanceId }
                                ?.runtimeFacts?.let { it.call == null } == true
                        }
                    } as ConfigurationUiState.Ready
                    if (preflight == null) ended else {
                        assertNull(ended.applyingInstanceId)
                        assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
                        preflight.release()
                        refusal.awaitEntered()
                        fixture.source.state.value as ConfigurationUiState.Ready
                    }
                } finally {
                    preflight?.release()
                    refusal.release()
                }
                assertEquals(target.instanceId, published.applyingInstanceId)
                assertEquals(ConfigurationProvisionOutcome.Failed, fixture.completed().outcome)
                fixture.settled()
                val finished = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals("请先停止该 SIM 的引擎，再重建配置", finished.message)
                assertNull(finished.applyingInstanceId)
                assertEquals(target, fixture.instance())
                assertEquals("legacy", RebuildSourceCommandShadow.format)
                assertEquals(listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY), RebuildSourceCommandShadow.reads.toList())
                assertTrue(RebuildSourceCommandShadow.writes.single().first.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
                assertEquals(listOf("hello", "ping", "getStatus", "getStatus"), peer.methods.toList())
                assertEquals(1, peer.sockets)
                val retained = fixture.retainedInstance()
                assertEquals(other, retained.copy(runtimeFacts = other.runtimeFacts))
                assertEquals(peer.revision, retained.runtimeFacts?.loaded?.configRevision)
                assertNull("pending rebuild must not restore the other card's old call",
                    published.instances.single { it.instanceId == other.instanceId }.runtimeFacts?.call)
                assertNull("a refused rebuild must not restore the other card's old call", retained.runtimeFacts?.call)
                assertEquals(published.instances, finished.instances)
            }
        }
    }

    @Test fun aRebuildCannotWriteAfterSubscriptionChangesDuringCandidateRead() = runBlocking {
        assertRebuildDoesNotFollowRetiredAttachment(beforeWrite = true)
    }

    @Test fun aRebuildReadbackCannotConfirmTheRetiredSubscriptionGeneration() = runBlocking {
        assertRebuildDoesNotFollowRetiredAttachment(beforeWrite = false)
    }

    @Test fun aRebuildCannotSubmitAfterIdentityPreparationChangesGeneration() = runBlocking {
        assertIdentityPreparationDoesNotSubmit(rebuild = true)
    }

    @Test fun identityRefreshCannotSubmitAfterIdentityPreparationChangesGeneration() = runBlocking {
        assertIdentityPreparationDoesNotSubmit(rebuild = false)
    }

    private suspend fun assertIdentityPreparationDoesNotSubmit(rebuild: Boolean) = coroutineScope {
        RebuildFixture(format = if (rebuild) "legacy" else "current").use { fixture ->
            fixture.ready()
            val before = fixture.instance()
            RebuildSourceCommandShadow.rejectIdentityRefresh = !rebuild
            val identity = RootFrameworkShadow.holdSubscriptionExtras()
            val refresh = if (rebuild) null else async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshIdentitiesAndAwait() }
            try {
                if (rebuild) assertTrue(fixture.rebuild())
                identity.awaitEntered()
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
                fixture.rebindTarget()
                assertEquals(ConfigurationUiState.Loading, fixture.source.state.value)
            } finally {
                identity.release()
            }
            if (refresh == null) assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.completed().outcome)
            else assertEquals(RefreshResult.Cancelled, withTimeout(3_000) { refresh.await() })
            fixture.ready()
            val current = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single()
            assertNotEquals(before.instanceId, current.instanceId)
            assertEquals(before.formatState, current.formatState)
            assertEquals(before.configRevision, current.configRevision)
            assertTrue("identity preparation of a retired generation must not reach Root", RebuildSourceCommandShadow.writes.isEmpty())
        }
    }

    private suspend fun assertRebuildDoesNotFollowRetiredAttachment(beforeWrite: Boolean) {
        RebuildFixture().use { fixture ->
            fixture.ready()
            val old = fixture.instance()
            val hold = if (beforeWrite) fixture.holdCandidateRead() else fixture.holdRebuildReadback()
            try {
                assertTrue(fixture.rebuild())
                hold.awaitEntered()
                assertEquals(if (beforeWrite) "legacy" else "current", RebuildSourceCommandShadow.format)
                fixture.rebindTarget()
                assertEquals(ConfigurationUiState.Loading, fixture.source.state.value)
            } finally {
                hold.release()
            }
            assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.completed().outcome)
            fixture.ready()
            val ready = fixture.source.state.value as ConfigurationUiState.Ready
            val actual = ready.instances.single()
            assertNull(ready.applyingInstanceId)
            assertNotEquals(old.instanceId, actual.instanceId)
            assertFalse(fixture.rebuild()) // The old handle is no longer accepted, even after refresh.
            if (beforeWrite) {
                assertTrue("retired rebuild must not reach the Root write", RebuildSourceCommandShadow.writes.isEmpty())
                assertEquals(ConfigurationFormatState.LEGACY, actual.formatState)
            } else {
                assertTrue(RebuildSourceCommandShadow.writes.single().first.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
                assertEquals(ConfigurationFormatState.CURRENT, actual.formatState)
                assertEquals(REBUILT_REVISION, actual.configRevision)
            }
        }
    }

    @Test fun failedIdentityRefreshDoesNotRestoreAnEndedCallOnTheOtherCard() = runBlocking {
        assertFailedIdentityRefreshKeepsLaterRuntime(eventPublishedFirst = true)
    }

    @Test fun aLaterNoDialogObservationCanPublishAfterFailedIdentityRefresh() = runBlocking {
        assertFailedIdentityRefreshKeepsLaterRuntime(eventPublishedFirst = false)
    }

    private suspend fun assertFailedIdentityRefreshKeepsLaterRuntime(eventPublishedFirst: Boolean) = coroutineScope {
        NextCallSocketFixture().use { peer ->
            peer.runtimeCall = buildJsonObject {
                put("configRevision", peer.revision); put("sessionSeconds", 1800); put("sessionRefresher", "local")
            }
            peer.pool.clientFor(RETAINED_TEST_KEY).request("ping")
            val catalogReceived = CompletableDeferred<Unit>()
            val allowCatalogPublish = CompletableDeferred<Unit>()
            val eventReceived = CompletableDeferred<Unit>()
            val allowEventPublish = CompletableDeferred<Unit>()
            RebuildFixture(format = "current", runningRetained = true, runtimeRead = { key ->
                assertEquals(RETAINED_TEST_KEY, key)
                val observation = peer.pool.configurationObservation(key)
                val facts = observation?.facts
                if (facts != null && RebuildSourceCommandShadow.writes.isNotEmpty()) {
                    if (facts.call != null && catalogReceived.complete(Unit)) allowCatalogPublish.await()
                    if (facts.call == null && eventReceived.complete(Unit) && !eventPublishedFirst) allowEventPublish.await()
                }
                observation
            }).use { fixture ->
                fixture.ready()
                val target = fixture.instance()
                val other = fixture.retainedInstance()
                assertEquals(ConfigurationFormatState.CURRENT, target.formatState)
                assertEquals(ConfigurationLifecycle.STOPPED, target.lifecycle)
                assertEquals(1800L, other.runtimeFacts?.call?.sessionSeconds)
                peer.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                RebuildSourceCommandShadow.rejectIdentityRefresh = true
                val refresh = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshIdentitiesAndAwait() }
                try {
                    withTimeout(2_500) {
                        catalogReceived.await()
                        peer.runtimeCall = null
                        peer.mediaChanged(1)
                        eventReceived.await()
                        if (eventPublishedFirst) fixture.source.state.first { state ->
                            (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == other.instanceId }
                                ?.runtimeFacts?.let { it.call == null } == true
                        }
                        allowCatalogPublish.complete(Unit)
                        assertEquals(RefreshResult.Failed("无法读取 SIM 身份"), refresh.await())
                    }
                    if (!eventPublishedFirst) assertEquals(1800L, fixture.retainedInstance().runtimeFacts?.call?.sessionSeconds)
                } finally {
                    allowCatalogPublish.complete(Unit)
                    allowEventPublish.complete(Unit)
                }
                fixture.settled()
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                val retained = fixture.retainedInstance()
                assertEquals("无法读取 SIM 身份", ready.message)
                assertEquals(target, fixture.instance())
                assertEquals(other, retained.copy(runtimeFacts = other.runtimeFacts))
                assertEquals(peer.revision, retained.runtimeFacts?.loaded?.configRevision)
                val command = RebuildSourceCommandShadow.writes.single().first
                assertEquals(ConfigurationControlCommand.refreshIdentityLine(REBUILD_TEST_KEY, 54, 0), command)
                assertEquals(List(3) { listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY) }.flatten(), RebuildSourceCommandShadow.reads.toList())
                assertEquals(4, peer.methods.count { it == "getStatus" })
                assertTrue(peer.writeParams.isEmpty())
                assertEquals(1, peer.sockets)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                assertNull("failed identity refresh must not discard the other card's later no-dialog observation", retained.runtimeFacts?.call)
            }
        }
    }

    private suspend fun assertRebuildKeepsLaterRuntime(eventPublishedFirst: Boolean, loseReply: Boolean = false) {
        NextCallSocketFixture().use { peer ->
            val followingCall = buildJsonObject {
                put("configRevision", peer.revision); put("sessionSeconds", 1800); put("sessionRefresher", "local")
            }
            peer.pool.clientFor(RETAINED_TEST_KEY).request("ping")
            val writeObserved = CompletableDeferred<Unit>()
            val catalogReceived = CompletableDeferred<Unit>()
            val allowCatalogPublish = CompletableDeferred<Unit>()
            val eventReceived = CompletableDeferred<Unit>()
            val allowEventPublish = CompletableDeferred<Unit>()
            RebuildFixture(runningRetained = true, runtimeRead = { key ->
                assertEquals(RETAINED_TEST_KEY, key)
                // The other engine has no media at the write. Its call starts after the stopped-card transaction.
                if (RebuildSourceCommandShadow.format == "current" && writeObserved.complete(Unit)) peer.runtimeCall = followingCall
                val observation = peer.pool.configurationObservation(key)
                val facts = observation?.facts
                if (facts != null && RebuildSourceCommandShadow.format == "current") {
                    if (facts.call != null && catalogReceived.complete(Unit)) allowCatalogPublish.await()
                    if (facts.call == null && eventReceived.complete(Unit) && !eventPublishedFirst) allowEventPublish.await()
                }
                observation
            }).use { fixture ->
                fixture.ready()
                val before = fixture.retainedInstance()
                assertEquals(ConfigurationLifecycle.RUNNING, before.lifecycle)
                assertEquals(peer.revision, before.runtimeFacts?.loaded?.configRevision)
                assertNull(before.runtimeFacts?.call)
                assertEquals(ConfigurationFormatState.LEGACY, fixture.instance().formatState)
                peer.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                RebuildSourceCommandShadow.loseResponse = loseReply
                assertTrue(fixture.rebuild())
                try {
                    withTimeout(2_500) {
                        catalogReceived.await()
                        assertTrue(writeObserved.isCompleted)
                        assertEquals(ConfigurationFormatState.LEGACY, fixture.instance().formatState)
                        peer.runtimeCall = null
                        peer.mediaChanged(1)
                        eventReceived.await()
                        if (eventPublishedFirst) {
                            // Initial and final no-dialog facts may compare equal in StateFlow.
                            fixture.joinRuntimeReader()
                            val later = checkNotNull(fixture.retainedInstance().runtimeFacts)
                            assertNull(later.call)
                        }
                    }
                    allowCatalogPublish.complete(Unit)
                    val expected = if (loseReply) ConfigurationProvisionOutcome.Unconfirmed else ConfigurationProvisionOutcome.Updated
                    assertEquals(expected, fixture.completed().outcome)
                    assertEquals(ConfigurationFormatState.CURRENT, fixture.instance().formatState)
                    assertEquals(REBUILT_REVISION, fixture.instance().configRevision)
                    assertEquals(ConfigurationLifecycle.STOPPED, fixture.instance().lifecycle)
                    assertNull(fixture.instance().runtimeFacts)
                    if (!eventPublishedFirst) assertNotNull(fixture.retainedInstance().runtimeFacts?.call)
                } finally {
                    allowCatalogPublish.complete(Unit)
                    allowEventPublish.complete(Unit)
                }
                fixture.settled()
                val retained = fixture.retainedInstance()
                assertEquals(RUNNING_REVISION, retained.configRevision)
                assertEquals(peer.revision, retained.runtimeFacts?.loaded?.configRevision)
                assertEquals(before, retained.copy(runtimeFacts = before.runtimeFacts))
                assertEquals(listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY, REBUILD_TEST_KEY, RETAINED_TEST_KEY), RebuildSourceCommandShadow.reads.toList())
                assertTrue(RebuildSourceCommandShadow.writes.single().first.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
                assertEquals(listOf("hello", "ping", "getStatus", "getStatus", "getStatus"), peer.methods.toList())
                assertEquals(1, peer.sockets)
                assertNull("rebuild readback must retain the other card's later no-dialog observation", retained.runtimeFacts?.call)
            }
        }
    }

    @Test fun runningOrFutureConfigsCannotReachDestructiveRootCommand() = runBlocking {
        for ((format, lifecycle) in listOf("legacy" to "running", "unsupported" to "stopped")) {
            RebuildFixture(format, lifecycle).use { fixture ->
                fixture.ready()
                assertFalse(fixture.rebuild())
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            }
        }
    }
}

private const val REBUILD_TEST_KEY = "0000000000000000001"
private const val RETAINED_TEST_KEY = "0000000000000000002"
private const val REBUILT_REVISION = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val RUNNING_REVISION = "r1_cccccccccccccccccccccccccccccccc"

private class RebuildFixture(format: String = "legacy", lifecycle: String = "stopped",
    retained: Boolean = false, malformedRetained: Boolean = false, runningRetained: Boolean = false,
    runtimeRead: suspend (String) -> ConfigurationRuntimeObservation? = { null },
) : AutoCloseable {
    val source: AndroidConfigurationSource
    val handle: SimHandle
    private val candidateRead = AtomicReference<S3IoHold?>()
    private lateinit var subscriptionChanged: () -> Unit
    init {
        RebuildSourceCommandShadow.format = format
        RebuildSourceCommandShadow.lifecycle = lifecycle
        RebuildSourceCommandShadow.loseResponse = false
        RebuildSourceCommandShadow.rejectIdentityRefresh = false
        RebuildSourceCommandShadow.resourceRefusal.set(null)
        RebuildSourceCommandShadow.targetShow.set(null)
        RebuildSourceCommandShadow.targetSubId = 54
        RebuildSourceCommandShadow.retained = retained || runningRetained
        RebuildSourceCommandShadow.retainedLifecycle = if (runningRetained) "running" else "stopped"
        RebuildSourceCommandShadow.malformedRetained = malformedRetained
        RebuildSourceCommandShadow.reads.clear()
        RebuildSourceCommandShadow.writes.clear()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        val active = if (runningRetained) listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY) else listOf(REBUILD_TEST_KEY)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(active.mapIndexed { slot, key ->
            SubscriptionInfoBuilder.newBuilder().setId(54 + slot).setSimSlotIndex(slot).setIccId(key)
                .setCarrierName("Synthetic").setMcc("310").setMnc("260").buildSubscriptionInfo()
        })
        val handles = OpaqueIccidHandleStore()
        handle = handles.handleFor(REBUILD_TEST_KEY)
        val inventory = AndroidSubscriptionInventory(context)
        val controlledInventory = object : SubscriptionInventory by inventory {
            override fun listActive(): SubscriptionInventoryResult {
                candidateRead.getAndSet(null)?.block()
                return inventory.listActive()
            }
            override fun startWatching(onChanged: () -> Unit): SubscriptionWatch {
                subscriptionChanged = onChanged
                return inventory.startWatching(onChanged)
            }
        }
        source = AndroidConfigurationSource(context, handles, controlledInventory,
            readNetworkOutlets = { ConfigurationNetworkOutlets(supported = true) }, readRuntimeFacts = runtimeRead)
    }
    suspend fun ready() = withTimeout(5000) {
        source.state.first { it is ConfigurationUiState.Ready }
        settled()
    }
    suspend fun settled() = withTimeout(5000) {
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
    suspend fun joinRuntimeReader() {
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
        val reader = synchronized(lock) {
            source.javaClass.getDeclaredField("runtimeReader").apply { isAccessible = true }.get(source) as Job?
        }
        reader?.join()
    }
    fun activateRetained() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY).mapIndexed { slot, key ->
                SubscriptionInfoBuilder.newBuilder().setId(54 + slot).setSimSlotIndex(slot).setIccId(key)
                    .setCarrierName("Synthetic").setMcc("310").setMnc("260").buildSubscriptionInfo()
            })
        assertEquals(2, (AndroidSubscriptionInventory(context).listActive() as SubscriptionInventoryResult.Active).subscriptions.size)
    }
    fun instance() = (source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId.value == handle.value }
    fun retainedInstance() = (source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId.value != handle.value }
    fun holdCandidateRead() = S3IoHold().also { candidateRead.set(it) }
    fun holdRebuildReadback() = S3IoHold().also { RebuildSourceCommandShadow.targetShow.set(it) }
    fun holdResourceRefusal() = S3IoHold().also { RebuildSourceCommandShadow.resourceRefusal.set(it) }
    fun rebindTarget() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
        RebuildSourceCommandShadow.targetSubId = 55
        shadowOf(manager).setActiveSubscriptionInfoList(listOf(SubscriptionInfoBuilder.newBuilder().setId(55)
            .setSimSlotIndex(0).setIccId(REBUILD_TEST_KEY).setCarrierName("Synthetic").setMcc("310").setMnc("260")
            .buildSubscriptionInfo()))
        subscriptionChanged()
    }
    fun rebuild(): Boolean = source.javaClass.getMethod("rebuild", SimHandle::class.java).invoke(source, handle) as Boolean
    suspend fun completed() = withTimeout(5000) {
        source.provisioning.first { handle in it.completions }.completions.getValue(handle)
    }
    override fun close() { source.close() }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class RebuildSourceCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        if (command.contains(" config show ")) reads += command.substringAfterLast(' ')
        if (command == ConfigurationControlCommand.showLine(REBUILD_TEST_KEY)) targetShow.getAndSet(null)?.block()
        val output = when {
            command.endsWith(" list") -> """[${listed(REBUILD_TEST_KEY, lifecycle)}${if (retained) ",${listed(RETAINED_TEST_KEY, retainedLifecycle)}" else ""}]"""
            command.endsWith(" config show $RETAINED_TEST_KEY") -> if (malformedRetained) {
                """{"ok":false,"code":"CONFIG_INVALID","message":"config is invalid"}"""
            } else if (retainedLifecycle == "running") {
                shownCurrent(RUNNING_REVISION, retainedLifecycle)
            } else """{"ok":true,"formatState":"legacy","lifecycle":"stopped"}"""
            command.endsWith(" config show $REBUILD_TEST_KEY") -> if (format == "current") {
                shownCurrent(REBUILT_REVISION, lifecycle)
            } else {
                """{"ok":true,"formatState":"$format","lifecycle":"$lifecycle","epdgFqdn":"retired.example.test","dns":"retired-dns"}"""
            }
            else -> error("unexpected read command")
        }
        return AndroidSuResult(false, 0, output, false)
    }
    private fun listed(key: String, state: String) =
        """{"ok":true,"iccid":"$key","state":"$state","pid":${if (state == "running") "4242" else "null"}}"""

    private fun shownCurrent(revision: String, state: String) =
        """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"$revision","displayLabel":"0001","lifecycle":"$state","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260"}"""

    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        writes += command to stdinText
        assertEquals(ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS, timeoutSeconds)
        if (rejectIdentityRefresh) {
            assertEquals(ConfigurationControlCommand.refreshIdentityLine(REBUILD_TEST_KEY, 54, 0), command)
            return AndroidSuResult(false, 18,
                """{"ok":false,"code":"IDENTITY_UNREADABLE","message":"SIM identity could not be read"}""", false)
        }
        if (targetSubId != 54 && command.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0")) {
            return AndroidSuResult(false, 18,
                """{"ok":false,"code":"IDENTITY_UNREADABLE","message":"SIM identity could not be read"}""", false)
        }
        assertTrue(command.endsWith(" config rebuild $REBUILD_TEST_KEY $targetSubId 0"))
        resourceRefusal.getAndSet(null)?.let {
            it.block()
            return AndroidSuResult(false, 1,
                """{"ok":false,"code":"TARGET_RUNNING","message":"target resources have not retired"}""", false)
        }
        format = "current"
        return AndroidSuResult(loseResponse, 0,
            if (loseResponse) "" else """{"ok":true,"code":"APPLIED","message":"config applied"}""", false)
    }
    companion object {
        @Volatile var format = "legacy"
        @Volatile var lifecycle = "stopped"
        @Volatile var loseResponse = false
        @Volatile var rejectIdentityRefresh = false
        @Volatile var retained = false
        @Volatile var retainedLifecycle = "stopped"
        @Volatile var malformedRetained = false
        @Volatile var targetSubId = 54
        val resourceRefusal = AtomicReference<S3IoHold?>()
        val targetShow = AtomicReference<S3IoHold?>()
        val reads = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
    }
}
