package com.vowifi.sw

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallBackgroundRecoveryHostTest {
    @Test fun socketEofRecoversAndNewIncomingPostsWithoutAnyActivity() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val server = CallSocketTestServer()
        val transport = AndroidCallEventTransport("synthetic-owner", server::socket)
        var starts = 0
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) },
                { transport }, { 0 }),
            object : CallAudioController {
                override val events = MutableSharedFlow<CallAudioEvent>()
                override suspend fun start(session: CallAudioSession) { starts++ }
                override fun stop() = Unit
            }, AndroidCallControlTransport(server::socket), Dispatchers.Default,
        )
        val notifications = AndroidIncomingCallNotificationCoordinator(context, source.state,
            IncomingCallNotificationCallbacks({}, {}), IncomingCallActionReceiver::class.java, CallActivity::class.java)
        try {
            withTimeout(8_000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Ready } }
            notifications.start()
            shadowOf(Looper.getMainLooper()).idle()
            val original = server.connections.first()
            original.endInput()
            withTimeout(5_000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Recovering } }
            withTimeout(8_000) { source.state.first { it is CallUiState.Ready && it.sync == CallSyncState.Ready } }
            assertTrue(original.closed)
            val reconnected = server.connections.single { !it.closed }
            assertNotSame(original, reconnected)
            reconnected.send(buildJsonObject {
                put("event", "incomingCall")
                put("data", buildJsonObject {
                    put("callId", "next-incoming"); put("state", "RINGING")
                    put("epoch", "host-epoch"); put("revision", 1); put("peer", "+12025550123")
                })
            })
            withTimeout(5_000) { source.state.first { it is CallUiState.Ready && it.phase == CallPhase.Ringing } }
            shadowOf(Looper.getMainLooper()).idle()
            val manager = context.getSystemService(NotificationManager::class.java)
            assertEquals(1, manager.activeNotifications.count {
                it.id == AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID
            })
            assertNotNull(notifications.keyForCurrent())
            assertEquals(0, server.dials.get())
            assertEquals(0, server.cancels.get())
            assertEquals(0, server.hangups.get())
            assertEquals(0, starts)
            assertNull(shadowOf(context).nextStartedActivity)
        } finally { notifications.stop(); source.close(); server.close() }
        assertTrue(server.connections.all { it.closed })
    }
}
