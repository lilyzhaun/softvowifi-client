@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import android.app.Application
import android.content.Context
import com.vowifi.sw.manager.data.AndroidObserverNotificationDefaults
import com.vowifi.sw.manager.data.AndroidSuResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// U3 reopen: a v1 success did not survive real foreground notification reposting.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserverNotificationRepairMigrationHostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("observer_notification_defaults", Context.MODE_PRIVATE)
    private val repaired = "channel_default_repair_v2"
    @Before fun clear() { assertTrue(prefs.edit().clear().commit()) }
    @After fun cleanup() { prefs.edit().clear().commit() }

    @Test fun oldSuccessDoesNotSkipTheExplicitOneTimeRepairAndLaterUserChoiceIsNotOverwritten() {
        assertTrue(prefs.edit().putBoolean("channel_default_v1", true).commit())
        Fixture().use { f ->
            val commands = mutableListOf<String>()
            val defaults = f.owner { command ->
                commands += command
                assertFalse("真实修复回执前不能写v2成功", prefs.getBoolean(repaired, false))
                ready()
            }
            repeat(3) { defaults.applyDefault() }; f.drain()
            assertEquals("v1不是持续隐藏证据，不能永久跳过修补", 1, commands.size)
            assertTrue(commands.single().endsWith(" ${app.applicationInfo.uid} repair-default-v2"))
            assertTrue(prefs.getBoolean(repaired, false))
            f.owner { commands += it; ready() }.applyDefault(); f.drain()
            assertEquals("修补成功后用户再开渠道不能被下个进程反复覆盖", 1, commands.size)
        }
    }

    @Test fun aFailedRepairPreservesTheOldReceiptButDoesNotClaimTheNewPolicyOrLoop() {
        assertTrue(prefs.edit().putBoolean("channel_default_v1", true).commit())
        Fixture().use { f ->
            var calls = 0
            val defaults = f.owner { calls++; AndroidSuResult(false, 1, "OBSERVER_CHANNEL_DEFAULT_UNAVAILABLE", false) }
            repeat(3) { defaults.applyDefault() }; f.drain()
            assertEquals(1, calls)
            assertTrue(prefs.getBoolean("channel_default_v1", false))
            assertFalse("失败不能记新策略已修", prefs.getBoolean(repaired, false))
            defaults.applyDefault(); f.drain(); assertEquals(1, calls)
            f.owner { calls++; ready() }.applyDefault(); f.drain()
            assertEquals(2, calls); assertTrue(prefs.getBoolean(repaired, false))
        }
    }

    @Test fun freshDefaultsKeepThePreferencePreservingModeAndDoNotBecomeARepairOnNextProcess() {
        Fixture().use { f ->
            val commands = mutableListOf<String>()
            f.owner { commands += it; ready() }.applyDefault(); f.drain()
            assertEquals(1, commands.size)
            assertFalse("新装默认路径不强制覆盖既有显式选择", commands.single().contains("repair-default-v2"))
            assertTrue(prefs.getBoolean(repaired, false))
            f.owner { commands += it; ready() }.applyDefault(); f.drain()
            assertEquals(1, commands.size)
        }
    }

    private fun ready() = AndroidSuResult(false, 0, "OBSERVER_CHANNEL_DEFAULT_READY", false)
    private inner class Fixture : AutoCloseable {
        private val dispatcher = HomeControlDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        fun owner(command: (String) -> AndroidSuResult) = AndroidObserverNotificationDefaults(app, scope, command)
        fun drain() = dispatcher.runCurrent()
        override fun close() { scope.cancel(); drain() }
    }
}
