@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.os.Looper
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
@Config(
    sdk = [33],
    shadows = [R6SyncCommandShadow::class, RootFrameworkShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"],
)
class R6ConfigurationSourceSyncHostTest {
    @Test fun refreshWithEmptyRuntimeObservationsClearsStaleRuntimePublications() = runBlocking {
        val connection = Any()
        val sampleFacts = ConfigurationRuntimeFacts(
            loaded = ConfigurationFacts(
                configRevision = R6_SYNC_REV,
                resolutionRevision = "rf1_" + "a".repeat(64),
                fields = emptyList(),
            ),
            registrationSeconds = 3600L,
        )
        var emitObservation = true
        R6SyncFixture(
            format = "current",
            lifecycle = "running",
            runtimeRead = {
                if (emitObservation) ConfigurationRuntimeObservation(sampleFacts, connection, 1L) { true }
                else null
            },
        ).use { fixture ->
            fixture.ready()
            assertEquals(1, fixture.runtimePublicationCount())

            emitObservation = false
            R6SyncCommandShadow.lifecycle = "stopped"
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            assertEquals(0, fixture.runtimePublicationCount())
        }
    }

    @Test fun provisionDuringSubscriptionChangeDoesNotSwallowNewlyInsertedAutoProvisionCandidate() = runBlocking {
        R6SyncFixture(format = "legacy", lifecycle = "stopped").use { fixture ->
            fixture.ready()
            val hold = fixture.holdInventoryList()
            assertTrue(fixture.source.rebuild(fixture.sim1Handle))
            hold.awaitEntered()

            // A second auto-provisionable SIM arrives while rebuild's listActive() is in flight.
            fixture.activateTwoSims()
            fixture.notifySubscriptionsChanged()
            hold.release()

            val completion = fixture.awaitProvisionCompletion(fixture.sim1Handle)
            assertEquals(ConfigurationProvisionOutcome.Unconfirmed, completion.outcome)
            val postSwap = fixture.ready()
            assertEquals(
                "SIM2 must still be auto-created after stale rebuild saw subscription change; writes=${R6SyncCommandShadow.writes}",
                2,
                postSwap.instances.size,
            )
            assertTrue(
                "expected config create for SIM2 (subId=62, slot=1); writes=${R6SyncCommandShadow.writes}",
                R6SyncCommandShadow.writes.contains(ConfigurationControlCommand.createLine(62, 1)),
            )
        }
    }

    @Test fun submitRacingSubscriptionChangeNeverRevivesStaleReadyOverLoading() = runBlocking {
        R6SyncFixture(format = "current", lifecycle = "stopped").use { fixture ->
            repeat(40) { round ->
                val ready = fixture.ready()
                val row = ready.instances.single { it.instanceId == fixture.sim1Id }
                val listHold = fixture.holdRefreshList()
                try {
                    val outcome = CompletableDeferred<ConfigurationApplyOutcome>()
                    val nextSmsc = "+155500001${round.toString().padStart(2, '0')}"
                    val overrides = ConfigurationOverrides(
                        transport = row.transport,
                        smsc = nextSmsc,
                        epdgFqdn = row.epdgFqdn,
                        imsRealm = row.imsRealm,
                        dns = row.dns,
                        expectedRevision = row.configRevision,
                    )
                    val startGate = CountDownLatch(1)
                    val notifyDone = CountDownLatch(1)
                    val notifier = Thread {
                        startGate.await(2, TimeUnit.SECONDS)
                        fixture.notifySubscriptionsChanged()
                        notifyDone.countDown()
                    }.apply { start() }

                    startGate.countDown()
                    val accepted = fixture.source.submit(fixture.sim1Id, overrides) { outcome.complete(it) }
                    assertTrue(notifyDone.await(3, TimeUnit.SECONDS))
                    notifier.join(3000)

                    if (accepted == ConfigurationApplyAcceptance.Accepted) {
                        // Wait for the apply coroutine to finish and post onComplete to main looper.
                        withTimeout(5000) {
                            while (!outcome.isCompleted) {
                                shadowOf(Looper.getMainLooper()).idle()
                                delay(2)
                            }
                        }
                        val result = outcome.await()
                        val stateWhilePostSwapBlocked = fixture.source.state.value
                        assertNotEquals(
                            "round $round: valid submit racing subscription change must never fail as Failed",
                            ConfigurationApplyOutcome.Failed,
                            result,
                        )
                        assertTrue(
                            "round $round: stale apply revived $stateWhilePostSwapBlocked over Loading (outcome=$result)",
                            stateWhilePostSwapBlocked is ConfigurationUiState.Loading,
                        )
                    }
                } finally {
                    fixture.releaseListHold(listHold)
                }
                fixture.ready()
            }
        }
    }

    @Test fun submitReadbackRacingSubscriptionChangeNeverRevivesStaleReadyOverLoading() = runBlocking {
        R6SyncFixture(format = "current", lifecycle = "stopped").use { fixture ->
            val ready = fixture.ready()
            val row = ready.instances.single { it.instanceId == fixture.sim1Id }
            val applyReadbackHold = fixture.holdApplyList()
            val outcome = CompletableDeferred<ConfigurationApplyOutcome>()
            val overrides = ConfigurationOverrides(
                transport = row.transport,
                smsc = "+15550000999",
                epdgFqdn = row.epdgFqdn,
                imsRealm = row.imsRealm,
                dns = row.dns,
                expectedRevision = row.configRevision,
            )
            assertEquals(
                ConfigurationApplyAcceptance.Accepted,
                fixture.source.submit(fixture.sim1Id, overrides) { outcome.complete(it) },
            )
            applyReadbackHold.awaitEntered()

            val postSwapHold = fixture.holdRefreshList()
            try {
                fixture.notifySubscriptionsChanged()
                fixture.releaseListHold(applyReadbackHold)

                withTimeout(5000) {
                    while (!outcome.isCompleted) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(2)
                    }
                }
                assertEquals(ConfigurationApplyOutcome.Unconfirmed, outcome.await())
                assertTrue(
                    "stale apply readback revived ${fixture.source.state.value} over Loading",
                    fixture.source.state.value is ConfigurationUiState.Loading,
                )
            } finally {
                fixture.releaseListHold(applyReadbackHold)
                fixture.releaseListHold(postSwapHold)
            }
            fixture.ready()
            Unit
        }
    }
}

private const val R6_SYNC_SIM1 = "0000000000000000001"
private const val R6_SYNC_SIM2 = "0000000000000000002"
private const val R6_SYNC_REV = "r1_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val R6_SYNC_REV2 = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

private class R6SyncFixture(
    format: String,
    lifecycle: String,
    runtimeRead: suspend (String) -> ConfigurationRuntimeObservation? = { null },
) : AutoCloseable {
    val source: AndroidConfigurationSource
    val sim1Handle: SimHandle
    val sim1Id: ConfigurationInstanceId
    private val inventoryHold = AtomicReference<S3IoHold?>()
    private val holds = mutableListOf<S3IoHold>()
    private lateinit var subscriptionChanged: () -> Unit

    init {
        R6SyncCommandShadow.reset(format, lifecycle)
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(
            listOf(
                SubscriptionInfoBuilder.newBuilder().setId(61).setSimSlotIndex(0).setIccId(R6_SYNC_SIM1)
                    .setCarrierName("Synthetic1").setMcc("310").setMnc("260").buildSubscriptionInfo(),
            ),
        )
        val handles = OpaqueIccidHandleStore()
        sim1Handle = handles.handleFor(R6_SYNC_SIM1)
        sim1Id = ConfigurationInstanceId(sim1Handle.value)
        val inventory = AndroidSubscriptionInventory(context)
        val controlledInventory = object : SubscriptionInventory by inventory {
            override fun listActive(): SubscriptionInventoryResult {
                inventoryHold.getAndSet(null)?.block()
                return inventory.listActive()
            }
            override fun startWatching(onChanged: () -> Unit): SubscriptionWatch {
                subscriptionChanged = onChanged
                return inventory.startWatching(onChanged)
            }
        }
        source = AndroidConfigurationSource(
            context,
            handles,
            controlledInventory,
            readNetworkOutlets = { ConfigurationNetworkOutlets(supported = true) },
            readRuntimeFacts = runtimeRead,
        )
    }

    suspend fun ready(): ConfigurationUiState.Ready = withTimeout(5000) {
        source.state.first { it is ConfigurationUiState.Ready }
        settled()
        source.state.value as ConfigurationUiState.Ready
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

    fun runtimePublicationCount(): Int {
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
        val map = source.javaClass.getDeclaredField("runtimePublications").apply { isAccessible = true }.get(source) as Map<*, *>
        return synchronized(lock) { map.size }
    }

    fun hasRefreshInFlight(): Boolean {
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
        return synchronized(lock) {
            source.javaClass.getDeclaredField("refreshInFlight").apply { isAccessible = true }.get(source) != null
        }
    }

    fun holdInventoryList(): S3IoHold = S3IoHold().also { holds += it; inventoryHold.set(it) }
    fun holdRefreshList(): S3IoHold = S3IoHold().also {
        holds += it
        R6SyncCommandShadow.nextList.set(R6SyncCommandShadow.ListHold(it) { hasRefreshInFlight() })
    }
    fun holdApplyList(): S3IoHold = S3IoHold().also {
        holds += it
        R6SyncCommandShadow.nextList.set(R6SyncCommandShadow.ListHold(it) { !hasRefreshInFlight() })
    }
    fun releaseListHold(hold: S3IoHold) {
        val current = R6SyncCommandShadow.nextList.get()
        if (current != null && current.hold === hold) {
            R6SyncCommandShadow.nextList.compareAndSet(current, null)
        }
        hold.release()
    }
    fun notifySubscriptionsChanged() = subscriptionChanged()

    fun activateTwoSims() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(
            listOf(
                SubscriptionInfoBuilder.newBuilder().setId(61).setSimSlotIndex(0).setIccId(R6_SYNC_SIM1)
                    .setCarrierName("Synthetic1").setMcc("310").setMnc("260").buildSubscriptionInfo(),
                SubscriptionInfoBuilder.newBuilder().setId(62).setSimSlotIndex(1).setIccId(R6_SYNC_SIM2)
                    .setCarrierName("Synthetic2").setMcc("310").setMnc("260").buildSubscriptionInfo(),
            ),
        )
    }

    suspend fun awaitProvisionCompletion(handle: SimHandle): ConfigurationProvisionCompletion = withTimeout(5000) {
        source.provisioning.first { handle in it.completions }.completions.getValue(handle)
    }

    override fun close() {
        holds.forEach { it.release() }
        source.close()
    }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class R6SyncCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        if (command == ConfigurationControlCommand.listLine()) {
            val pending = nextList.get()
            if (pending != null && pending.predicate() && nextList.compareAndSet(pending, null)) {
                pending.hold.block()
            }
        }
        val output = when {
            command.endsWith(" list") -> {
                val items = mutableListOf(listed(R6_SYNC_SIM1, lifecycle))
                if (sim2Created) items += listed(R6_SYNC_SIM2, "stopped")
                "[${items.joinToString(",")}]"
            }
            command.endsWith(" config show $R6_SYNC_SIM1") -> if (format == "current") {
                shownCurrent(R6_SYNC_REV, lifecycle, "0001", sim1Smsc)
            } else {
                """{"ok":true,"formatState":"legacy","lifecycle":"$lifecycle","epdgFqdn":"retired.example.test"}"""
            }
            command.endsWith(" config show $R6_SYNC_SIM2") -> shownCurrent(R6_SYNC_REV2, "stopped", "0002", "")
            else -> error("unexpected read command: $command")
        }
        return AndroidSuResult(false, 0, output, false)
    }

    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        writes += command
        return when (command) {
            ConfigurationControlCommand.createLine(62, 1) -> {
                sim2Created = true
                AndroidSuResult(false, 0, """{"ok":true,"code":"CREATED","message":"config created"}""", false)
            }
            ConfigurationControlCommand.applyLine(R6_SYNC_SIM1) -> {
                Regex("\"smsc\":\"([^\"]*)\"").find(stdinText)?.groupValues?.get(1)?.let { sim1Smsc = it }
                AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"config applied"}""", false)
            }
            else -> AndroidSuResult(false, 0, """{"ok":true,"code":"APPLIED","message":"config applied"}""", false)
        }
    }

    private fun listed(key: String, state: String) =
        """{"ok":true,"iccid":"$key","state":"$state","pid":${if (state == "running") "4242" else "null"}}"""

    private fun shownCurrent(revision: String, state: String, lastFour: String, smsc: String) =
        """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"$revision","displayLabel":"$lastFour","lifecycle":"$state","transport":"wifi","smsc":"$smsc","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260"}"""

    class ListHold(val hold: S3IoHold, val predicate: () -> Boolean)

    companion object {
        @Volatile var format = "current"
        @Volatile var lifecycle = "stopped"
        @Volatile var sim2Created = false
        @Volatile var sim1Smsc = ""
        val nextList = AtomicReference<ListHold?>()
        val writes = CopyOnWriteArrayList<String>()

        fun reset(fmt: String, life: String) {
            format = fmt
            lifecycle = life
            sim2Created = false
            sim1Smsc = ""
            nextList.set(null)
            writes.clear()
        }
    }
}
