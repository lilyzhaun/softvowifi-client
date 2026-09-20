package com.vowifi.sw.manager

import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.RefreshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoftVoWiFiAppTest {
    @Test
    fun homeAndSettingsRefreshTargetsAreExact() {
        assertEquals(
            setOf(
                RefreshTarget.OBSERVER,
                RefreshTarget.ENGINE,
                RefreshTarget.SIM_CONTROL,
                RefreshTarget.CONFIGURATION,
                RefreshTarget.ENVIRONMENT_DIAGNOSTICS,
            ),
            AppDestination.HOME.refreshTargets,
        )
        assertEquals(
            setOf(
                RefreshTarget.CONFIGURATION,
                RefreshTarget.SIM_CONTROL,
                RefreshTarget.ENVIRONMENT_DIAGNOSTICS,
                RefreshTarget.OPERATIONAL_LOGS,
            ),
            AppDestination.SETTINGS.refreshTargets,
        )
        assertFalse(AppDestination.MESSAGES.refreshTargets.contains(RefreshTarget.SIM_CONTROL))
        assertFalse(AppDestination.CALLS.refreshTargets.contains(RefreshTarget.SIM_CONTROL))
        assertEquals(setOf(RefreshTarget.MESSAGES), AppDestination.MESSAGES.refreshTargets)
        assertEquals(setOf(RefreshTarget.CALLS), AppDestination.CALLS.refreshTargets)
    }

    @Test
    fun callAttachmentSignatureChangesOnlyWithRelevantRunningAvailability() {
        val stopped = engineState(EngineLifecycle.STOPPED)
        val starting = engineState(EngineLifecycle.STARTING)
        val running = engineState(EngineLifecycle.RUNNING)
        val stopping = engineState(EngineLifecycle.STOPPING)

        assertEquals("fixture-a", engineCallAttachmentSignature(running)?.singleInstanceKey)
        assertEquals(engineCallAttachmentSignature(stopped), engineCallAttachmentSignature(starting))
        assertEquals(engineCallAttachmentSignature(running), engineCallAttachmentSignature(stopping))
        assertEquals(
            engineCallAttachmentSignature(running),
            engineCallAttachmentSignature(
                running.copy(instances = running.instances.map { it.copy(message = "bounded status update") }),
            ),
        )
        assertNotEquals(engineCallAttachmentSignature(stopped), engineCallAttachmentSignature(running))
        assertNotEquals(
            engineCallAttachmentSignature(running),
            engineCallAttachmentSignature(engineState(EngineLifecycle.RUNNING, instanceKey = "fixture-b")),
        )
        assertNull(engineCallAttachmentSignature(EngineControlUiState.Loading))
        assertNull(engineCallAttachmentSignature(EngineControlUiState.PermissionDenied("bounded")))
        assertNull(engineCallAttachmentSignature(EngineControlUiState.Failure("bounded")))
    }

    @Test
    fun callsRefreshOnlyWhenAResolvedAttachmentSignatureChanges() {
        val none = engineCallAttachmentSignature(engineState(EngineLifecycle.STOPPED))
        val one = engineCallAttachmentSignature(engineState(EngineLifecycle.RUNNING))

        assertTrue(shouldRefreshCallsForEngineAvailability(previous = null, current = none))
        assertTrue(shouldRefreshCallsForEngineAvailability(previous = none, current = one))
        assertTrue(shouldRefreshCallsForEngineAvailability(previous = one, current = none))
        assertFalse(shouldRefreshCallsForEngineAvailability(previous = one, current = one))
        assertFalse(shouldRefreshCallsForEngineAvailability(previous = one, current = null))
    }

    private fun engineState(
        lifecycle: EngineLifecycle,
        instanceKey: String = "fixture-a",
    ) = EngineControlUiState.Ready(
        listOf(EngineInstanceState(instanceKey, lifecycle)),
    )
}
