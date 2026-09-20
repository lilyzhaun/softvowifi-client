@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.vowifi.sw.manager.data.AndroidSubscriptionInventory
import com.vowifi.sw.manager.data.OpaqueIccidHandleStore
import com.vowifi.sw.manager.data.SimControlEvaluator
import com.vowifi.sw.manager.data.SubscriptionInventoryResult
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.ui.homeDashboardPresentation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.shadows.ShadowTelephonyManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [HomeCarrierTelephonyShadow::class])
class AndroidHomeCarrierSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun sevenSupplementaryAndMixedDecimalSpnsFallBackThroughInventoryAndEvaluator() {
        subscriptions()
        val supplementaryZero = "\uD835\uDFCE"
        scoped(11, supplementaryZero.repeat(7))
        scoped(22, "3" + supplementaryZero.repeat(5) + "\u0660")
        assertEquals(listOf("SIM 1 · 🇰🇭 归属运营商未知", "SIM 2 · 🇺🇸 归属运营商未知"), titles())
    }

    @Test fun sixSupplementaryDecimalSpnAndShortBrandSurviveInventoryAndEvaluator() {
        subscriptions()
        val shortDecimal = "\uD835\uDFCE".repeat(6)
        scoped(11, shortDecimal)
        scoped(22, "3")
        assertEquals(listOf("SIM 1 · 🇰🇭 $shortDecimal", "SIM 2 · 🇺🇸 3"), titles())
    }

    @Test fun spnBoundaryPreservesSupplementaryCharactersAndStripsFormatControls() {
        subscriptions()
        scoped(11, "甲".repeat(31) + "😀尾")
        scoped(22, "\u202E示例\u202C运营商\u2028\u2029")
        assertEquals(listOf("SIM 1 · 🇰🇭 " + "甲".repeat(31) + "😀", "SIM 2 · 🇺🇸 示例运营商"), titles())
    }

    @Test fun twoSubscriptionsUseTheirOwnSpnNotNetworkOrCustomLabels() {
        subscriptions()
        scoped(11, "Cellcard")
        val second = scoped(22, "T-Mobile")
        assertEquals(listOf("SIM 1 · 🇰🇭 Cellcard", "SIM 2 · 🇺🇸 T-Mobile"), titles())
        shadowOf(second).setNetworkOperatorName("Another roaming carrier")
        shadowOf(second).setNetworkOperator("46001")
        assertEquals(listOf("SIM 1 · 🇰🇭 Cellcard", "SIM 2 · 🇺🇸 T-Mobile"), titles())
    }

    @Test fun missingUnsafeDeniedAndUnavailableSpnKeepBothRows() {
        subscriptions()
        scoped(11, "Cellcard")
        val second = scoped(22, "")
        val shadow = Shadow.extract<HomeCarrierTelephonyShadow>(second)
        for (value in listOf("", " ", "1".repeat(15), "imsi=private", "/data/private")) {
            shadow.setSimOperatorName(value)
            assertEquals(listOf("SIM 1 · 🇰🇭 Cellcard", "SIM 2 · 🇺🇸 归属运营商未知"), titles())
        }
        for (failure in listOf(SecurityException(), IllegalStateException(), UnsupportedOperationException())) {
            shadow.failure = failure
            assertEquals(listOf("SIM 1 · 🇰🇭 Cellcard", "SIM 2 · 🇺🇸 归属运营商未知"), titles())
        }
    }

    @Test fun absentTelephonyServiceDoesNotDiscardSubscriptions() {
        subscriptions()
        val withoutTelephony = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.TELEPHONY_SERVICE) null else super.getSystemService(name)
        }
        assertEquals(listOf("SIM 1 · 🇰🇭 归属运营商未知", "SIM 2 · 🇺🇸 归属运营商未知"), titles(withoutTelephony))
    }

    private fun subscriptions() {
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(
            listOf(subscription(11, 0, "456", "01"), subscription(22, 1, "310", "260")),
        )
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimOperatorName("Wrong default SIM")
    }

    private fun subscription(id: Int, slot: Int, mcc: String, mnc: String): SubscriptionInfo =
        SubscriptionInfoBuilder.newBuilder().setId(id).setSimSlotIndex(slot)
            .setIccId("89" + "0".repeat(15) + id)
            .setCarrierName("Roaming carrier").setDisplayName("Custom label")
            .setMcc(mcc).setMnc(mnc).buildSubscriptionInfo()

    private fun scoped(id: Int, spn: String): TelephonyManager {
        val manager = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(manager).setSimOperatorName(spn)
        shadowOf(manager).setNetworkOperatorName("Roaming carrier")
        shadowOf(context.getSystemService(TelephonyManager::class.java))
            .setTelephonyManagerForSubscriptionId(id, manager)
        return manager
    }

    private fun titles(sourceContext: Context = context): List<String> {
        val result = AndroidSubscriptionInventory(sourceContext).listActive() as SubscriptionInventoryResult.Active
        val state = SimControlEvaluator.join(result.subscriptions, emptyList(), OpaqueIccidHandleStore())
        return homeDashboardPresentation(
            RegistrationUiState.Empty, state, EnvironmentDiagnosticsUiState.Loading, 0,
        ).actions.map { it.title }
    }
}

@Implements(TelephonyManager::class)
class HomeCarrierTelephonyShadow : ShadowTelephonyManager() {
    var failure: RuntimeException? = null

    @Implementation
    public override fun getSimOperatorName(): String? {
        failure?.let { throw it }
        return super.getSimOperatorName()
    }
}
