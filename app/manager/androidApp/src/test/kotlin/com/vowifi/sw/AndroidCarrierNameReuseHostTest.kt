@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.app.Application
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [HomeCarrierTelephonyShadow::class])
class AndroidCarrierNameReuseHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val key = "89" + "0".repeat(15) + "22"

    @Test fun configurationDiscoveryUsesHomeSpnInsteadOfNetworkOrUserLabel() {
        subscription("T-Mobile")
        val discovered = AndroidSubscriptionDiscovery(context).discover(listOf(key)).single() as SubscriptionDiscovery.Matched
        assertEquals("T-Mobile", discovered.carrierLabel)
        assertEquals("SIM 2 · 🇺🇸 T-Mobile", callLineLabel(context, key))
    }

    @Test fun unreadableSpnKeepsSubscriptionAndUsesSameUnknownAsHome() {
        val manager = subscription("T-Mobile")
        Shadow.extract<HomeCarrierTelephonyShadow>(manager).failure = SecurityException()
        val discovered = AndroidSubscriptionDiscovery(context).discover(listOf(key)).single() as SubscriptionDiscovery.Matched
        assertEquals("归属运营商未知", discovered.carrierLabel)
        assertEquals("SIM 2 · 🇺🇸 归属运营商未知", callLineLabel(context, key))
    }

    private fun subscription(name: String): TelephonyManager {
        val info = SubscriptionInfoBuilder.newBuilder().setId(22).setSimSlotIndex(1).setIccId(key)
            .setCarrierName("Roaming network").setDisplayName("Personal SIM label").setMcc("310").setMnc("260").buildSubscriptionInfo()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(info))
        val manager = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(manager).setSimOperatorName(name)
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setTelephonyManagerForSubscriptionId(22, manager)
        return manager
    }
}
