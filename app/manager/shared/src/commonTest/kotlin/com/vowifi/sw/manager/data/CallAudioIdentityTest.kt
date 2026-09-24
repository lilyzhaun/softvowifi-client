@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallAudioIdentityTest {
    @Test fun foreignOwnerOrTransportEndedCannotStopCurrentAudio() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<CallAudioEvent>()
        var current: CallAudioSession? = null
        val audio = object : CallAudioController {
            override val events = events
            override suspend fun start(session: CallAudioSession) { current = session }
            override fun stop() { current = null }
        }
        val transport = FakeCallTransport()
        val actual = EngineCallSource(EngineMessageRuntime({ listOf(callRunning(CALL_ICCID_A)) }, { transport }, { 0 }),
            audio, coroutineContext, control = CallControlTransport { _, method, params, timeout -> transport.request(method, params, timeout) })
        try {
            actual.dial(CALL_NUMBER)
            val session = assertNotNull(current)
            events.emit(CallAudioEvent.Ended(session.copy(iccid = CALL_ICCID_B)))
            assertSame(session, current)
            events.emit(CallAudioEvent.Ended(session.copy(transport = FakeCallTransport())))
            assertSame(session, current)
            assertEquals(CallAudioState.Active, (actual.state.value as CallUiState.Ready).audio)
        } finally { actual.close() }
    }
}
