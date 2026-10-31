@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationUiState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// C3：second-source-integrated 的原 RED 回执保留。
// 经确认的公开 provisioning 契约替代“任意域状态变化”粗观测；原真实 IO／次数／Busy 断言不减。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ConfigurationProvisionCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationProvisionPendingCandidateHostTest {
    @Test fun acceptedCreateFromEmptyHasAnObservableStateWhileActualCreateIoIsHeld() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val before = fixture.source.state.value
            assertEquals(ConfigurationUiState.Empty, before)
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered() // 真 identityReader / provisioner / commands 已进入 create。
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshAndAwait() })
                assertEquals("写操作不伪造配置行或首次 Loading", before, fixture.source.state.value)
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                repeat(2) { fixture.source.provision(fixture.target) }
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                fixture.createHold.release()
                val completed = fixture.finished(fixture.target)
                assertTrue(completed.pending.isEmpty())
                assertEquals(ConfigurationProvisionOutcome.Created, completed.completions.getValue(fixture.target).outcome)
                assertTrue(completed.completions.getValue(fixture.target).sequence > 0)
                assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }
}
