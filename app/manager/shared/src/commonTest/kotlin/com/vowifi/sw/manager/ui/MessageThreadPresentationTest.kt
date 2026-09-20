package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageThreadPresentationTest {
    @Test
    fun visibleLatestItemDoesNotForceAnotherScroll() {
        assertEquals(
            MessageThreadScrollDecision.KeepCurrent,
            messageThreadInitialScrollDecision(lastItemIndex = 4, visibleItemIndices = listOf(0, 1, 2, 3, 4)),
        )
    }

    @Test
    fun missingLatestItemScrollsLongThreadToLatest() {
        assertEquals(
            MessageThreadScrollDecision.ScrollTo(8),
            messageThreadInitialScrollDecision(lastItemIndex = 8, visibleItemIndices = listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun emptyVisibleLayoutWaitsWithoutIssuingScroll() {
        assertEquals(
            MessageThreadScrollDecision.WaitForLayout,
            messageThreadInitialScrollDecision(lastItemIndex = 4, visibleItemIndices = emptyList()),
        )
    }

    @Test
    fun outgoingDeliveryMetadataUsesExactIndependentSemanticLines() {
        val lines = messageOutgoingMetadataLines(
            status = "状态报告：已送达",
            note = "不代表本机收到 MT 短信",
            timestamp = "10:42",
        )

        assertEquals(
            listOf(
                MessageMetadataLine(MessageMetadataRole.STATUS, "状态报告：已送达"),
                MessageMetadataLine(MessageMetadataRole.NOTE, "不代表本机收到 MT 短信"),
                MessageMetadataLine(MessageMetadataRole.TIMESTAMP, "10:42"),
            ),
            lines,
        )
        assertFalse(lines.any { " · " in it.text })
    }

    @Test
    fun twoHundredPercentMetadataContractKeepsCriticalPhrasesWholeAndBounded() {
        val layout = messageThreadMetadataLayoutContract(fontScale = 2f)
        val lines = messageOutgoingMetadataLines(
            status = "状态报告：已送达",
            note = "不代表本机收到 MT 短信",
            timestamp = "10:42",
        )

        assertTrue(layout.independentLines)
        assertTrue(layout.softWrap)
        assertFalse(layout.clipCriticalText)
        assertEquals(listOf("状态报告", "已送达", "本机收到 MT 短信"), layout.wholeSemanticPhrases)
        assertTrue(lines.all { it.text.length <= layout.maximumCharactersPerLine })
    }

    @Test
    fun displayFormatterProtectsCriticalPhrasesWithoutChangingPlainSemantics() {
        val lines = listOf(
            MessageMetadataLine(MessageMetadataRole.STATUS, "状态报告：已送达"),
            MessageMetadataLine(MessageMetadataRole.NOTE, "不代表本机收到 MT 短信"),
        )

        val displayed = lines.map(::messageMetadataDisplayText)

        assertTrue(displayed.all { WORD_JOINER in it })
        assertEquals(lines.map { it.text }, displayed.map(::stripMessageDisplayJoiners))
        assertEquals("状态报告：已送达", lines.first().text)
        assertEquals("不代表本机收到 MT 短信", lines.last().text)
    }
}
