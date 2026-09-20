package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.STORAGE_NOTICE
import com.vowifi.sw.manager.data.STORAGE_SYNC_NOTICE
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageRootChromeTest {
    @Test
    fun readyWithOrdinaryAttachmentNoticeReturnsCompactNoticeText() {
        val ready = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            attachmentNotice = "消息只读：当前没有运行中的引擎",
        )

        assertEquals("消息只读：当前没有运行中的引擎", messageRootCompactNotice(ready))
    }

    @Test
    fun readyWithStorageAttachmentNoticeReturnsNullForFullStatePanel() {
        val storageFailure = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            attachmentNotice = STORAGE_NOTICE,
        )
        val syncFailure = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            attachmentNotice = STORAGE_SYNC_NOTICE,
        )

        assertNull(messageRootCompactNotice(storageFailure))
        assertNull(messageRootCompactNotice(syncFailure))
    }

    @Test
    fun nonNoticeStatesReturnNull() {
        val readyNoNotice = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            attachmentNotice = null,
        )

        assertNull(messageRootCompactNotice(readyNoNotice))
        assertNull(messageRootCompactNotice(MessageUiState.Loading))
        assertNull(messageRootCompactNotice(MessageUiState.Empty))
        assertNull(messageRootCompactNotice(MessageUiState.Failure("failure")))
        assertNull(messageRootCompactNotice(MessageUiState.PermissionDenied("denied")))
        assertNull(messageRootCompactNotice(MessageUiState.Unavailable("unavailable")))
    }
}
