@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigurationNetworkInterface
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationUiState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [S3ConfigurationCommandShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class NetworkOutletRefreshHostTest {
    @Test fun manualRefreshCannotJoinSubscriptionFlightWithoutAnOutletRead() = runBlocking {
        val reads = AtomicInteger()
        val next = AtomicReference(ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")), true))
        S3ConfigurationIoFixture { reads.incrementAndGet(); next.get() }.use { fixture ->
            fixture.ready()
            assertEquals(1, reads.get())
            val hold = fixture.holdList()
            fixture.notifySubscriptionsChanged()
            hold.awaitEntered()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            try {
                assertTrue("不读取目录的订阅任务不能让手动刷新挂起加入并假报完成", manual.isCompleted)
                assertEquals(RefreshResult.Busy, manual.await())
                assertEquals(1, reads.get())
            } finally {
                hold.release()
                manual.cancel()
            }
            fixture.ready() // Wait for the held real subscription producer and its completion handler.
            next.set(ConfigurationNetworkOutlets(supported = true))
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            assertEquals(2, reads.get())
            assertTrue((fixture.source.state.value as ConfigurationUiState.Ready).networkOutlets.interfaces.isEmpty())
        }
    }

    @Test fun initialAndManualRefreshReplaceInventoryWithoutTwoSecondPolling() = runBlocking {
        val reads = AtomicInteger()
        val next = AtomicReference(ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")), true))
        S3ConfigurationIoFixture(notifyOnWatch = true) { reads.incrementAndGet(); next.get() }.use { fixture ->
            val initial = fixture.ready()
            assertEquals("首次目录读取不能被先到的订阅刷新跳过", 1, reads.get())
            assertEquals(listOf("Meta"), initial.networkOutlets.interfaces.map { it.name })
            delay(2200)
            assertEquals(1, reads.get())
            next.set(ConfigurationNetworkOutlets(supported = true))
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            assertEquals(2, reads.get())
            assertTrue((fixture.source.state.value as ConfigurationUiState.Ready).networkOutlets.interfaces.isEmpty())
            assertEquals(initial.instances.map { it.transport }, (fixture.source.state.value as ConfigurationUiState.Ready).instances.map { it.transport })
        }
    }

    @Test fun failedInventoryReadKeepsLastFactsAndReportsFailureNotEmptySuccess() = runBlocking {
        var next = ConfigurationNetworkOutlets(listOf(ConfigurationNetworkInterface("Meta", true, true, "tun")), true)
        S3ConfigurationIoFixture { next }.use { fixture ->
            fixture.ready()
            next = ConfigurationNetworkOutlets(issue = "网络出口读取失败")
            assertTrue(fixture.source.refreshAndAwait() is RefreshResult.Failed)
            val outlets = (fixture.source.state.value as ConfigurationUiState.Ready).networkOutlets
            assertEquals(listOf("Meta"), outlets.interfaces.map { it.name })
            assertEquals("网络出口读取失败", outlets.issue)
        }
    }
}
