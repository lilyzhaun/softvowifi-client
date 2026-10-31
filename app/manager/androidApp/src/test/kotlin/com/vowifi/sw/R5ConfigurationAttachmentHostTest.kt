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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class R5ConfigurationAttachmentHostTest {
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
