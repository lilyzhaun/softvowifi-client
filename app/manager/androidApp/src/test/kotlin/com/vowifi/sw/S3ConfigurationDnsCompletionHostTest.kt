@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationUiState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [S3NoApplyCommandShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationDnsCompletionHostTest {
    @Test fun refreshLeavesBlankDnsForTheLiveNetworkWithoutApplying() = runBlocking {
        S3NoApplyCommandShadow.applies.set(0)
        S3ConfigurationIoFixture().use { fixture ->
            fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val result = withTimeout(3000) { fixture.source.refreshAndAwait() }
            assertEquals(RefreshResult.Completed, result)
            val target = (fixture.source.state.value as ConfigurationUiState.Ready).instances
                .single { it.instanceId == fixture.targetId }
            assertEquals("", target.dns)
            assertEquals("刷新不得把系统 DNS 写入配置", 0, S3NoApplyCommandShadow.applies.get())
            assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
            assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
        }
    }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class S3NoApplyCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult = S3ConfigurationCommandShadow().run(command)
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        applies.incrementAndGet()
        return AndroidSuResult(false, 1, "{\"ok\":false,\"code\":\"APPLY_FAILED\",\"message\":\"config apply failed\"}", false)
    }
    companion object { val applies = AtomicInteger() }
}
