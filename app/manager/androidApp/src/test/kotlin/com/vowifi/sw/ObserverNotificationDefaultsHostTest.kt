@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import android.app.Application
import android.content.Context
import com.vowifi.sw.manager.data.AndroidObserverNotificationDefaults
import com.vowifi.sw.manager.data.AndroidSuResult
import java.io.IOException
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
import org.robolectric.shadows.ShadowLog

// 只证明调用端命令/成功flag边界，不冒充设备Root Binder或系统渠道隐藏成功。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserverNotificationDefaultsHostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences("observer_notification_defaults", Context.MODE_PRIVATE)
    private val key = "channel_default_v1"
    private val ready = "OBSERVER_CHANNEL_DEFAULT_READY"

    @Before fun prepare() {
        assertFalse("前提：迁移成功flag必须在CE上下文", app.isDeviceProtectedStorage)
        assertTrue(preferences.edit().clear().commit())
    }
    @After fun cleanup() { preferences.edit().clear().commit() }

    @Test fun canonicalSuccessRunsOnceAndTheNextOwnerPreservesThePersistedUserChoice() {
        Fixture().use { f ->
            var calls = 0
            val defaults = f.create {
                calls++
                assertFalse("真实命令确认前不能预写成功", preferences.getBoolean(key, false))
                result(ready)
            }
            repeat(4) { defaults.applyDefault() }
            f.drain()
            assertEquals("重复就绪回调只允许一个真实命令", 1, calls)
            assertTrue(preferences.getBoolean(key, false))
            defaults.applyDefault(); f.drain(); assertEquals(1, calls)
            f.create { calls++; result(ready) }.applyDefault(); f.drain()
            assertEquals("持久成功后不再覆盖用户后续自行开启渠道的选择", 1, calls)
        }
    }

    @Test fun failedOutcomesNeverPersistOrLoopButTheNextOwnerCanRetry() {
        val failures = listOf(result(ready, exit = 1), result(ready, timeout = true), result(ready, overflow = true),
            result(ready, write = true), result("UNAVAILABLE"), result(""), result(" $ready"),
            result("$ready\nextra synthetic output"))
        for (failure in failures) {
            preferences.edit().clear().commit()
            Fixture().use { f ->
                var calls = 0
                val defaults = f.create { calls++; failure }
                repeat(4) { defaults.applyDefault() }; f.drain()
                assertEquals("失败也须锁住当前owner的重复回调", 1, calls)
                assertFalse("未获精确成功回执不得持久化", preferences.getBoolean(key, false))
                defaults.applyDefault(); f.drain(); assertEquals(1, calls)
                f.create { calls++; result(ready) }.applyDefault(); f.drain()
                assertEquals("下一owner可重试，不把失败永久记为成功", 2, calls)
                assertTrue(preferences.getBoolean(key, false))
            }
        }
    }

    @Test fun commandQuotesTheActualApkPathAndUsesOnlyTheFixedRootEntryAndActualUid() {
        val info = app.applicationInfo
        val original = info.sourceDir
        info.sourceDir = "/data/app/synthetic'one;echo unsafe/base.apk"
        try {
            assertEquals(info.sourceDir, app.applicationInfo.sourceDir)
            Fixture().use { f ->
                val commands = mutableListOf<String>()
                f.create { commands += it; result(ready) }.applyDefault(); f.drain()
                assertEquals(1, commands.size)
                assertEquals("CLASSPATH='/data/app/synthetic'\\''one;echo unsafe/base.apk' /system/bin/app_process /system/bin " +
                    "com.vowifi.sw.ObserverNotificationChannelMain ${app.applicationInfo.uid}", commands.single())
                assertFalse("不得扩大为任意渠道/包参数", commands.single().contains("--channel"))
            }
        } finally { info.sourceDir = original }
    }

    @Test fun commandIoFailureDoesNotEscapeOrExposeRawDiagnostics() {
        Fixture().use { f ->
            var calls = 0
            val marker = "synthetic-private-diagnostic-must-not-log"
            val defaults = f.create { calls++; throw IOException(marker) }
            defaults.applyDefault(); f.drain()
            defaults.applyDefault(); f.drain()
            assertEquals(1, calls); assertFalse(preferences.getBoolean(key, false))
            val warnings = ShadowLog.getLogs().filter { it.type == android.util.Log.WARN }
            assertTrue("失败应有固定安全警告，不静默记成功", warnings.isNotEmpty())
            assertFalse("原始异常诊断不得进入日志", warnings.any { it.msg.contains(marker) || it.throwable?.message?.contains(marker) == true })
        }
    }

    private fun result(output: String, exit: Int = 0, timeout: Boolean = false, overflow: Boolean = false, write: Boolean = false) =
        AndroidSuResult(timeout, exit, output, overflow, write)

    private inner class Fixture : AutoCloseable {
        private val dispatcher = HomeControlDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        fun create(command: (String) -> AndroidSuResult) = AndroidObserverNotificationDefaults(app, scope, command)
        fun drain() = dispatcher.runCurrent()
        override fun close() { scope.cancel(); drain() }
    }
}
