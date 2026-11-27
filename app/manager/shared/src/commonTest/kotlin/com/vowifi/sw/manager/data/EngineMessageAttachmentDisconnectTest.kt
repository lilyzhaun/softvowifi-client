@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EngineMessageAttachmentDisconnectTest {
    @Test
    fun disconnectObserverSubscribesBeforeEventJobStarts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val childScope = CoroutineScope(dispatcher + SupervisorJob())
        val repository = FakeMessageRepository()
        val wire = DualSmsWire()
        val instance = EngineInstanceState(
            "8986001",
            EngineLifecycle.RUNNING,
            pid = 7,
            runtime = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = "IDLE", transport = "UDP", pcscf = emptyList()),
        )
        val session = EngineMessageSession(
            runtime = EngineMessageRuntime(
                listInstances = { listOf(instance) },
                transportFor = { wire },
                clock = { 100L },
                activeIccids = { listOf("8986001") },
            ),
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
            scope = childScope,
        )
        try {
            session.selection.refreshInbox()
            runCurrent()
            refreshMessageAttachment(session)
            assertEquals(
                1,
                wire.messageDisconnects.subscriptionCount.value,
                "disconnectJob must subscribe eagerly (UNDISPATCHED) before eventJob can connect or drop",
            )
            wire.messageDisconnects.tryEmit(Unit)
            runCurrent()
            val line = session.sessions[ownerFor("8986001")]
            assertFalse(
                line?.attached == true,
                "disconnect emitted immediately after session creation must retire the line",
            )
        } finally {
            session.closed = true
            session.selection.close()
            childScope.cancel()
            runCurrent()
        }
    }
}
