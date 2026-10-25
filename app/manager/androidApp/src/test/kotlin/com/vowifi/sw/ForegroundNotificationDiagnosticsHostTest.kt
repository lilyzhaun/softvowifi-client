@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.app.Notification
import android.content.Intent
import com.vowifi.sw.manager.data.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class ForegroundNotificationDiagnosticsHostTest {
    private val writerField get() = AndroidDiagnosticLog::class.java.getDeclaredField("writer").apply { isAccessible = true }
    private val directory = Files.createTempDirectory("fgs-diagnostic-")
    private lateinit var writer: AsyncDiagnosticWriter
    private var fixture: CallPlatformFixture? = null

    @Before fun prepareWriter() = runBlocking {
        (writerField.get(null) as? AsyncDiagnosticWriter)?.let {
            it.close(); withTimeout(5000) { it.awaitClosed() }
        }
        writer = AsyncDiagnosticWriter(directory = { directory }, pid = 1234,
            monotonic = { System.nanoTime() / 1_000_000 })
        writerField.set(null, writer)
    }

    @After fun releaseWriter() = runBlocking {
        writer.close(); withTimeout(5000) { writer.awaitClosed() }
        writerField.set(null, null)
        fixture?.let {
            val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
            app.runtime().calls.lifecycle.close(); app.runtime().notifications.stop(); it.close()
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        RuntimeApplicationShadow.callFixture = null
    }

    private fun installFixture(stage: String = "IDLE") = CallPlatformFixture(stage).also {
        fixture = it; RuntimeApplicationShadow.callFixture = it
    }

    private fun records(): List<JsonObject> = runBlocking {
        writer.close(); withTimeout(5000) { writer.awaitClosed() }
        val path = directory.resolve("app.jsonl")
        if (!Files.exists(path)) emptyList() else Files.readAllLines(path)
            .map { Json.parseToJsonElement(it).jsonObject }.filter { "notification" in it }
    }

    @Test fun realObserverPromotionAndRestoreRecordTheirOwnerWithoutChangingForegroundNotification() {
        installFixture()
        val service = Robolectric.buildService(ObserverService::class.java)
        try {
            service.create()
            service.get().restoreObserverNotification()
            val notification = shadowOf(service.get()).lastForegroundNotification
            assertEquals("ims_observer", notification.channelId)
            val entries = records()
            val starts = entries.filter { it["result"]?.jsonPrimitive?.content == "started" }
            assertEquals("原始Observer及恢复的真实startForeground均须记录", 2, starts.size)
            starts.forEach {
                val metadata = it.getValue("notification").jsonObject
                assertEquals("observer", metadata.getValue("owner").jsonPrimitive.content)
                assertEquals(1, metadata.getValue("id").jsonPrimitive.int)
                assertEquals("observer", metadata.getValue("channel").jsonPrimitive.content)
                assertTrue(metadata.getValue("small_icon").jsonPrimitive.boolean)
            }
        } finally { service.destroy() }
    }

    @Test fun staleMicrophonePromotionAndRetirementAreOrderedAndNeverAuthorizeCapture() {
        val fixture = installFixture()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        try {
            service.get().onStartCommand(Intent().putExtra(AndroidCallLifecycle.EXTRA_REQUEST, "expired-private-key"), 0, 7)
            assertEquals(7, shadowOf(service.get()).stopSelfId)
            assertFalse(fixture.microphone.foreground)
            assertTrue(fixture.commands.isEmpty())
            val entries = records()
            val starts = entries.filter { it["result"]?.jsonPrimitive?.content == "started" }
            assertEquals(listOf("promote", "retire"), starts.map {
                it.getValue("notification").jsonObject.getValue("action").jsonPrimitive.content
            })
            assertTrue(starts.all { it.getValue("notification").jsonObject.getValue("owner").jsonPrimitive.content == "microphone" })
            assertTrue(starts.all { it.getValue("notification").jsonObject.getValue("id").jsonPrimitive.int == 101 })
            assertFalse(entries.toString().contains("expired-private-key"))
        } finally { service.destroy() }
    }

    @Test fun realCoordinatorUpdatesPreserveCallStyleAndRecordNoPeerOrPendingIntentContents() {
        val fixture = installFixture("IN_CALL")
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        val coordinator = app.runtime().notifications
        try {
            coordinator.start()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            coordinator.setMicrophoneRunning(true)
            coordinator.setAppForeground(false)
            coordinator.setMicrophoneRunning(false)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val entries = records()
            assertTrue("无麦克风时的独立普通通知须保留诊断", entries.any {
                val metadata = it.getValue("notification").jsonObject
                metadata["owner"]?.jsonPrimitive?.content == "coordinator" &&
                    metadata["id"]?.jsonPrimitive?.intOrNull == CallOngoingNotification.WITHOUT_MICROPHONE_ID &&
                    metadata["action"]?.jsonPrimitive?.content == "notify" &&
                    metadata["call_style"]?.jsonPrimitive?.booleanOrNull == false
            })
            assertTrue("真实通知更新须记录来源及模板状态", entries.any {
                val metadata = it.getValue("notification").jsonObject
                metadata["owner"]?.jsonPrimitive?.content == "coordinator" &&
                    metadata["call_style"]?.jsonPrimitive?.booleanOrNull == true
            })
            val encoded = entries.toString()
            listOf("+12025550123", fixture.token, fixture.epoch, "softvowifi-call:", "未知号码").forEach {
                assertFalse("通知正文、号码及Intent不得进入诊断", encoded.contains(it))
            }
            assertFalse(entries.any { it["result"]?.jsonPrimitive?.content == "success" })
        } finally { coordinator.stop() }
    }

    @Test fun synchronousPublishFailureIsRecordedAndRethrownUnchangedWithoutMutatingTheChannel() {
        val app = RuntimeEnvironment.getApplication()
        val notification = CallOngoingNotification.build(app, null, null)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val before = manager.getNotificationChannel(CallOngoingNotification.CHANNEL)
        val failure = IllegalArgumentException("private-notification-error")
        var calls = 0
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ForegroundNotificationDiagnostics.trace(app, NotificationDiagnosticOwner.MICROPHONE,
                NotificationDiagnosticAction.PROMOTE, CallOngoingNotification.ID, notification) {
                calls++; throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(1, calls)
        assertEquals(before, manager.getNotificationChannel(CallOngoingNotification.CHANNEL))
        val entries = records()
        assertEquals(listOf("started", "failure"), entries.map { it.getValue("result").jsonPrimitive.content })
        assertEquals(1, entries.map { it.getValue("attempt").jsonPrimitive.int }.distinct().size)
        assertFalse(entries.toString().contains("private-notification-error"))
    }

    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, NotificationDiagnosticReadFailureShadow::class])
    fun diagnosticMetadataReadFailureCannotPreventTheOriginalOperation() {
        val app = RuntimeEnvironment.getApplication()
        val notification = Notification.Builder(app, "ongoing_calls")
            .setSmallIcon(android.R.drawable.ic_menu_call).build()
        var calls = 0
        ForegroundNotificationDiagnostics.trace(app, NotificationDiagnosticOwner.MICROPHONE,
            NotificationDiagnosticAction.PROMOTE, 101, notification) { calls++ }
        assertEquals(1, calls)
        val entries = records()
        assertEquals(listOf("started", "observed"), entries.map { it.getValue("result").jsonPrimitive.content })
        val metadata = entries.first().getValue("notification").jsonObject
        assertFalse("读取失败不能伪造channel不存在", "channel_exists" in metadata)
        assertFalse("读取失败不能伪造importance", "importance" in metadata)
        assertTrue(metadata.getValue("small_icon").jsonPrimitive.boolean)
    }
}

@Implements(android.app.NotificationManager::class)
class NotificationDiagnosticReadFailureShadow : ShadowNotificationManager() {
    @Implementation override fun getNotificationChannel(channelId: String): android.app.NotificationChannel? {
        throw IllegalStateException("synthetic diagnostic read unavailable")
    }
}
