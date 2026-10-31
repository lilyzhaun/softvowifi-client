@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 只补新完成契约；复用真实 AndroidConfigurationSource 与已通过的命令边界影子。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [S3ConfigurationCommandShadow::class, RootFrameworkShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationRefreshAwaitHostTest {
    @Test fun heldManualProvisionCannotBeJoinedAsEitherRefreshKind() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val hold = fixture.holdList()
            try {
                fixture.source.provision(SimHandle(fixture.targetId.value))
                hold.awaitEntered() // 已有目标的真实 provision / catalog 读回持有原业务 gate。
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshAndAwait() })
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshIdentitiesAndAwait() })
                assertEquals(before, fixture.source.state.value)
                assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
                assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
            } finally { hold.release() }
        }
    }

    @Test fun cancellingOneUserLeavesTheActualOrdinaryReadForTheOtherUser() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val hold = fixture.holdList()
            try {
                fixture.source.refresh()
                hold.awaitEntered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                val survivor = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                first.cancel()
                first.join()
                assertTrue(first.isCancelled)
                assertFalse(survivor.isCompleted)
                hold.release()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { survivor.await() })
                assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
            } finally { hold.release() }
        }
    }

    @Test fun closeCancelsAHeldOrdinaryBatchBeforeTheRealIoReturns() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            val hold = fixture.holdList()
            try {
                fixture.source.refresh()
                hold.awaitEntered()
                val user = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                assertFalse(user.isCompleted)
                fixture.source.close()
                try {
                    withTimeout(3000) { user.await() }
                    fail("关闭源必须取消在途等待")
                } catch (error: CancellationException) {
                    assertFalse(error is TimeoutCancellationException)
                }
                assertEquals(RefreshResult.Unsupported, fixture.source.refreshAndAwait())
                assertEquals(RefreshResult.Unsupported, fixture.source.refreshIdentitiesAndAwait())
                assertEquals(before, fixture.source.state.value)
            } finally { hold.release() }
        }
    }

    @Test fun ordinaryReadJoinsButExplicitIdentityIsBusyAndNeverReplayed() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val hold = fixture.holdList()
            try {
                fixture.source.refresh()
                hold.awaitEntered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
                assertFalse(first.isCompleted)
                assertFalse(duplicate.isCompleted)
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshIdentitiesAndAwait() })
                assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
                hold.release()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
                assertEquals(first.await(), withTimeout(3000) { duplicate.await() })
                assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
                assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
                assertEquals(before, fixture.source.state.value)
            } finally { hold.release() }
        }
    }

    @Test fun identityJoinsOnlyIdentityAndCompletesAfterReadback() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val identityHold = fixture.holdIdentity()
            var readbackHold: S3IoHold? = null
            try {
                fixture.source.refreshIdentities()
                identityHold.awaitEntered()
                val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshIdentitiesAndAwait() }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshIdentitiesAndAwait() }
                assertFalse(first.isCompleted)
                assertFalse(duplicate.isCompleted)
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshAndAwait() })
                val readback = fixture.holdList().also { readbackHold = it }
                S3ConfigurationCommandShadow.smsc = "+15550000003"
                identityHold.release()
                readback.awaitEntered()
                assertFalse(first.isCompleted) // identity 命令退出不等于 catalog 真实读回/发布完成。
                assertFalse(duplicate.isCompleted)
                readback.release()
                assertEquals(RefreshResult.Completed, withTimeout(3000) { first.await() })
                assertEquals(first.await(), withTimeout(3000) { duplicate.await() })
                val state = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals("+15550000003", state.instances.single { it.instanceId == fixture.targetId }.smsc)
                assertEquals(lists + 2, S3ConfigurationCommandShadow.listCalls())
                assertEquals(1, S3ConfigurationCommandShadow.identities().size)
            } finally { identityHold.release(); readbackHold?.release() }
        }
    }

    @Test fun inventoryAndIdentityFailuresCannotClaimCompletedFromPreservedReady() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            fixture.inventoryFailure = SubscriptionInventoryResult.Failure("无法读取活动订阅")
            assertTrue(withTimeout(3000) { fixture.source.refreshAndAwait() } is RefreshResult.Failed)
            assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
            assertEquals(before.instances, (fixture.source.state.value as ConfigurationUiState.Ready).instances)
            assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
        }
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            S3ConfigurationCommandShadow.identityResult = AndroidSuResult(false, 1,
                "{\"ok\":false,\"code\":\"IDENTITY_UNREADABLE\",\"message\":\"identity unreadable\"}", false)
            assertTrue(withTimeout(3000) { fixture.source.refreshIdentitiesAndAwait() } is RefreshResult.Failed)
            assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
            val after = fixture.source.state.value as ConfigurationUiState.Ready
            assertEquals(before.instances, after.instances)
            assertNotNull(after.message) // 只断言存在问题，不解析中文文案来推断结果。
            assertEquals(1, S3ConfigurationCommandShadow.identities().size)
        }
    }
}
