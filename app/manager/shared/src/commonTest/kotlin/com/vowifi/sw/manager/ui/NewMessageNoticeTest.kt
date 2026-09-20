package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewMessageNoticeTest {
    @Test
    fun repeatedCauseHasOneVisibleNoticeWithoutChangingInputGates() {
        MessageComposeMode.entries.forEach { mode ->
            val compose = MessageComposeSpec(mode, "same-cause")
            val result = newMessageComposerPresentation(compose, compose.notice)
            assertNull(result.notice)
            assertEquals(compose.mode, result.mode)
            assertEquals(compose.editingEnabled, result.editingEnabled)
            assertEquals(compose.sendingEnabled, result.sendingEnabled)
        }
    }

    @Test
    fun distinctStorageFailureAndUndisplayedPermissionNoticeRemainVisible() {
        val storage = MessageComposeSpec(MessageComposeMode.STORAGE_BLOCKED, "storage-failure")
        assertEquals(storage, newMessageComposerPresentation(storage, "engine-unavailable"))
        val permission = MessageComposeSpec(MessageComposeMode.NOT_READY, "permission-recovery")
        assertEquals(permission, newMessageComposerPresentation(permission, null))
        val ready = MessageComposeSpec(MessageComposeMode.ENABLED, null)
        assertEquals(ready, newMessageComposerPresentation(ready, null))
    }
}
