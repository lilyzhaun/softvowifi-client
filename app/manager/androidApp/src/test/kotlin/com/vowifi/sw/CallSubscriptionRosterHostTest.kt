package com.vowifi.sw

import android.content.Context
import android.telephony.SubscriptionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [RootFrameworkShadow::class])
class CallSubscriptionRosterHostTest {
    @Test fun androidActiveRosterPreservesUnidentifiableSecondSubscription() {
        val context = RuntimeEnvironment.getApplication()
        val first = SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0)
            .setIccId("8900000000000000001F").buildSubscriptionInfo()
        val unknown = SubscriptionInfoBuilder.newBuilder().setId(2).setSimSlotIndex(1)
            .setIccId("").buildSubscriptionInfo()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(first, unknown))
        val roster = Class.forName("com.vowifi.sw.manager.data.AndroidCallSubscriptionsKt")
            .getMethod("activeCallIccids", Context::class.java).invoke(null, context)
        assertEquals(listOf("8900000000000000001", null), roster)
    }
}
