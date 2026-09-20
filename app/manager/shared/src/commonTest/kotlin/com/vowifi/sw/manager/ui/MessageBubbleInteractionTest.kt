package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageBubbleInteractionTest {
    @Test
    fun messageBubbleInteractionSpecEnforcesBodyOnlyExternalMetadataAndLongPressCopy() {
        val spec = messageBubbleInteractionContract()
        assertEquals(MessageBubbleSurfaceMode.BODY_ONLY_WRAP_CONTENT_WITH_MAX, spec.surfaceMode)
        assertEquals(MessageBubbleMetadataPlacement.OUTSIDE, spec.metadataPlacement)
        assertEquals(MessageBubbleCopyGesture.LONG_PRESS, spec.copyGesture)
        assertEquals("已复制", MESSAGE_COPIED_FEEDBACK)
        assertEquals("内容不可显示", MESSAGE_UNCOPYABLE_BODY)
    }

    @Test
    fun messageCopyableBodyAcceptsValidBodyOnlyAndRejectsBlanksOrPlaceholder() {
        assertEquals("hello", messageCopyableBody("hello"))
        assertEquals("测试短信 123", messageCopyableBody("测试短信 123"))
        assertNull(messageCopyableBody(""))
        assertNull(messageCopyableBody("   "))
        assertNull(messageCopyableBody("内容不可显示"))
    }
}
