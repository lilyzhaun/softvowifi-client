@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class R5ConfigurationAttachmentHostTest {
    private fun observePort(port: Int) {
        // An inventory read can see the new port before the bridge's next five-second sample.
        val manager = RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java)
        val info = manager.activeSubscriptionInfoList!!.single()
        ReflectionHelpers.setField(info, "mPortIndex", port)
        assertEquals(port, manager.activeSubscriptionInfoList!!.single().portIndex)
    }

    @Test fun changingOnlyThePortDuringAReadWithdrawsTheOldEditableRow() = runBlocking {
        var move = false
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(runtimeRead = { if (move) observePort(1); null }).use { fixture ->
            fixture.ready()
            observePort(0)
            fixture.source.refreshAndAwait()
            val old = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single()
            move = true
            fixture.source.refreshAndAwait()
            assertFalse("换端口期间读回的旧行仍可编辑", fixture.source.state.value is ConfigurationUiState.Ready)
            move = false
            fixture.source.refreshAndAwait()
            val fresh = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single()
            assertNotEquals(old.instanceId, fresh.instanceId)
            assertEquals(old.draftOwnerId, fresh.draftOwnerId)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun runtimeEventsCannotPublishAcrossAnObservedPortChange() = runBlocking {
        var moveDuringRead = false
        var seconds = 10L
        var reads = 0
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(runtimeRead = {
            reads++
            if (moveDuringRead) observePort(1)
            ConfigurationRuntimeFacts(ConfigurationFacts(null, "synthetic", emptyList()), registrationSeconds = seconds)
        }).use { fixture ->
            fixture.ready()
            observePort(0)
            fixture.source.refreshAndAwait()
            fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single()
            val connection = Any()
            val sourceLock = ReflectionHelpers.getField<Any>(fixture.source, "lock")
            suspend fun event(seq: Long) {
                fixture.source.runtimeFactsChanged(S3_CONFIG_TARGET, EngineRuntimeFactsChange(seq, connection) { true })
                // Join the accepted reader even when it rightly publishes no state change.
                val job = synchronized(sourceLock) { ReflectionHelpers.getField<Job?>(fixture.source, "runtimeReader") }
                withTimeout(3_000) { job?.join() }
            }
            seconds = 20; event(1)
            assertEquals(20L, row().runtimeFacts?.registrationSeconds)
            val stable = reads
            seconds = 30; moveDuringRead = true; event(2)
            assertEquals(stable + 1, reads)
            assertEquals("端口在读回期间变化，旧事实仍被发布", 20L, row().runtimeFacts?.registrationSeconds)
            moveDuringRead = false; event(3)
            assertEquals("旧行在事件开始前已失去端口，不应再读", stable + 1, reads)
            assertEquals(20L, row().runtimeFacts?.registrationSeconds)
        }
    }

    @Test fun aSlotChangeDuringTheReadCannotPublishAnOldEditableConfiguration() = runBlocking {
        var move = false
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(runtimeRead = {
            if (move) {
                shadowOf(RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java))
                    .setActiveSubscriptionInfoList(listOf(SubscriptionInfoBuilder.newBuilder()
                        .setId(2).setSimSlotIndex(1).setIccId(S3_CONFIG_TARGET)
                        .setCarrierName("Synthetic").setMcc("001").setMnc("01").buildSubscriptionInfo()))
            }
            null
        }).use { fixture ->
            fixture.ready()
            move = true
            fixture.source.refreshAndAwait()
            assertFalse("只清运行快照仍会发布换槽前的可编辑行", fixture.source.state.value is ConfigurationUiState.Ready)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun subscriptionChangesWithdrawOldSaveActionsWhileAReadIsBlocked() = runBlocking {
        AutomaticSmscCommandShadow.running = false
        S3ConfigurationIoFixture().use { fixture ->
            val old = fixture.ready().instances.single()
            val hold = fixture.holdList()
            val reading = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            hold.awaitEntered()
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(listOf(SubscriptionInfoBuilder.newBuilder()
                    .setId(2).setSimSlotIndex(0).setIccId(S3_CONFIG_TARGET)
                    .setCarrierName("Synthetic").setMcc("001").setMnc("01").buildSubscriptionInfo()))
            fixture.notifySubscriptionsChanged()
            val overrides = ConfigurationOverrides(old.transport, old.smsc, old.epdgFqdn, old.imsRealm, old.dns,
                expectedRevision = old.configRevision)
            assertNotEquals(ConfigurationApplyAcceptance.Accepted,
                fixture.source.submit(old.instanceId, overrides) { error("不应受理旧附件保存") })
            hold.release()
            assertEquals(RefreshResult.Cancelled, reading.await())
            val fresh = withTimeout(3_000) { fixture.source.state.first { state ->
                state is ConfigurationUiState.Ready && state.instances.none { it.instanceId == old.instanceId }
            } } as ConfigurationUiState.Ready
            assertEquals(old.draftOwnerId, fresh.instances.single().draftOwnerId)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " config refresh-identity " in it })
        }
    }
}
