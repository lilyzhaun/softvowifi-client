package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageFontScaleBoundaryTest {
    private val legal = listOf(0.85f, 1f, 1.5f, 2f, 2.1f)

    @Test fun scaleAboveTwoIsNotRejectedAsOutsideAnAcceptanceMatrix() {
        assertTrue(messageTextLayoutContract(2.1f).softWrap)
        assertEquals(0.90f, messageBubbleMaximumWidth(2.1f))
        assertFalse(messageThreadMetadataLayoutContract(2.1f).clipCriticalText)
    }

    @Test fun legalSystemFontScalesKeepWrappingContract() {
        for (scale in legal) {
            val layout = messageTextLayoutContract(scale)
            assertTrue(layout.softWrap, "scale=$scale")
            assertTrue(layout.breakUnboundedText, "scale=$scale")
        }
    }

    @Test fun legalSystemFontScalesKeepOriginalBubbleWidthThreshold() {
        for (scale in legal) {
            assertEquals(if (scale >= 1.5f) 0.90f else 0.78f, messageBubbleMaximumWidth(scale), "scale=$scale")
        }
    }

    @Test fun legalSystemFontScalesDoNotClipCriticalMetadata() {
        for (scale in legal) {
            val layout = messageThreadMetadataLayoutContract(scale)
            assertTrue(layout.independentLines)
            assertTrue(layout.softWrap)
            assertFalse(layout.clipCriticalText)
            assertEquals(listOf("状态报告", "已送达", "本机收到 MT 短信"), layout.wholeSemanticPhrases)
        }
    }

    @Test fun invalidScaleDomainStillFailsInsteadOfProducingInvalidLayout() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { messageTextLayoutContract(scale) }
            assertFailsWith<IllegalArgumentException> { messageBubbleMaximumWidth(scale) }
            assertFailsWith<IllegalArgumentException> { messageThreadMetadataLayoutContract(scale) }
        }
    }
}
