package com.vowifi.sw

import android.content.Context
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.AndroidEngineClientPool
import com.vowifi.sw.manager.data.EngineMessageTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class AndroidMessageSessionBoundaryTest {
    @Test fun callAndMessageRostersUsePhysicalSlotOrderAndSlotLabel() {
        val context = RuntimeEnvironment.getApplication()
        val first = SubscriptionInfoBuilder.newBuilder().setId(8).setSimSlotIndex(0)
            .setIccId("8900000000000000001").buildSubscriptionInfo()
        val second = SubscriptionInfoBuilder.newBuilder().setId(3).setSimSlotIndex(1)
            .setIccId("8900000000000000002").buildSubscriptionInfo()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(second, first))
        listOf("AndroidMessageSourceKt" to "activeMessageIccids", "AndroidCallSubscriptionsKt" to "activeCallIccids").forEach { (type, method) ->
            val roster = Class.forName("com.vowifi.sw.manager.data.$type").getDeclaredMethod(method, Context::class.java)
                .invoke(null, context) as List<*>
            assertEquals(listOf("8900000000000000001", "8900000000000000002"), roster)
        }
        val label = Class.forName("com.vowifi.sw.manager.data.AndroidCallLineLabelKt")
            .getDeclaredMethod("callLineLabel", Context::class.java, String::class.java)
            .invoke(null, context, "8900000000000000002") as String
        assertTrue(label.startsWith("SIM 2"))
    }

    @Test fun readonlyActiveInventoryRetainsUnknownSlotRatherThanPretendingThereIsOneSim() {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(SubscriptionManager::class.java))
        val first = SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0)
            .setIccId("8900000000000000001F").buildSubscriptionInfo()
        val unreadable = SubscriptionInfoBuilder.newBuilder().setId(2).setSimSlotIndex(1)
            .setIccId("").buildSubscriptionInfo()
        val method = Class.forName("com.vowifi.sw.manager.data.AndroidMessageSourceKt")
            .getDeclaredMethod("activeMessageIccids", Context::class.java)
        manager.setActiveSubscriptionInfoList(listOf(first, unreadable))
        val owners = method.invoke(null, context) as List<*>
        assertEquals(2, owners.size)
        assertEquals("8900000000000000001", owners.first())
        assertNull(owners.last())
        manager.setActiveSubscriptionInfoList(emptyList())
        assertEquals(emptyList<String>(), method.invoke(null, context))
    }

    @Test fun transportKeepsItsOriginalClientAfterPoolReplacementAndCannotSendViaReplacement() = runBlocking {
        val pool = AndroidEngineClientPool()
        val constructor = Class.forName("com.vowifi.sw.manager.data.AndroidEngineMessageTransport")
            .getConstructor(AndroidEngineClientPool::class.java, String::class.java)
        fun transport() = constructor.newInstance(pool, "synthetic") as EngineMessageTransport
        val old = transport()
        val oldEvents = old.events
        val remove = pool.javaClass.declaredMethods.single { it.name.startsWith("remove") }
        val removed = remove.invoke(pool, "synthetic") as AutoCloseable
        removed.close()
        try {
            val replacement = transport()
            assertSame(oldEvents, old.events)
            assertNotSame(old.events, replacement.events)
            val failure = runCatching { old.request("getStatus") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        } finally { pool.close() }
    }
}
