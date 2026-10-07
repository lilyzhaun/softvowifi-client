package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConversationGroup
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunicationPresentationTest {
    private val now = 2_000_000_000_000L
    private val localDate: (Long) -> String = { "8月24日" }

    @Test
    fun everyToneHasTextIconAndExplicitLightDarkColorRoles() {
        SemanticTone.entries.forEach { tone ->
            val visual = toneVisual(tone)
            assertTrue(visual.semanticLabel.isNotBlank())
            assertTrue(visual.icon.name.isNotBlank())
            assertEquals(visual.lightColorRole, visual.darkColorRole)
        }
    }

    @Test
    fun longCjkSpecsWrapAndActionsRemainAtLeast48Dp() {
        val longText = "这是用于验证百分之二百字体下不会被强制为单行的很长中文内容"
        val hero = OverallStatusHeroSpec(SemanticTone.WARNING, longText, longText, longText)
        val notice = NoticeCardSpec(SemanticTone.ERROR, longText, longText)
        val action = ActionPresentation(longText, enabled = false, busy = true)

        listOf(hero.titleFlow, hero.summaryFlow, hero.statusFlow, notice.titleFlow, notice.summaryFlow, action.labelFlow).forEach { flow ->
            assertTrue(flow.softWrap)
            assertNull(flow.maxLines)
        }
        assertEquals(48, action.minimumTouchDp)
        assertFalse(action.enabled)
        assertTrue(action.busy)
    }

    @Test
    fun conversationRowBoundsPreviewStatusAndPrivateMetadata() {
        val privateId = "owner=sim-1 correlation=corr-1 token=token-1"
        val group = ConversationGroup(
            conversationKey = "private-key",
            displayLabel = "sip:user@example.invalid",
            records = listOf(
                MessageRecord.Outgoing(
                    id = privateId,
                    peerLabel = "sip:user@example.invalid",
                    preview = privateId + " " + "长".repeat(120),
                    timestampMs = now - 60_000L,
                    status = OutgoingMessageStatus.UNKNOWN,
                    engineSmsId = "token-1",
                ),
            ),
        )

        val presentation = conversationRowPresentation(group, now, localDate)

        assertEquals("未知号码", presentation.peerDisplay)
        assertEquals("内容不可显示", presentation.preview)
        assertEquals("发送结果未知", presentation.statusText)
        assertEquals("1 分钟前", presentation.timeText)
        assertEquals("ConversationRowPresentation", presentation.toString())
    }

    @Test
    fun conversationRowOmitsIncomingStatusWhilePreservingOutgoingStatus() {
        val incomingGroup = ConversationGroup(
            conversationKey = "in-key",
            displayLabel = "+155501",
            records = listOf(
                MessageRecord.Incoming(
                    id = "msg-in",
                    peerLabel = "+155501",
                    preview = "Incoming message body",
                    timestampMs = now - 60_000L,
                ),
            ),
        )
        val outgoingGroup = ConversationGroup(
            conversationKey = "out-key",
            displayLabel = "+155501",
            records = listOf(
                MessageRecord.Outgoing(
                    id = "msg-out",
                    peerLabel = "+155501",
                    preview = "Outgoing message body",
                    timestampMs = now - 60_000L,
                    status = OutgoingMessageStatus.ACCEPTED,
                    engineSmsId = "engine-1",
                ),
            ),
        )

        val incomingPresentation = conversationRowPresentation(incomingGroup, now, localDate)
        val outgoingPresentation = conversationRowPresentation(outgoingGroup, now, localDate)

        assertNull(incomingPresentation.statusText)
        assertEquals("已受理", outgoingPresentation.statusText)
    }

    @Test
    fun callHistoryRowBoundsPhoneDurationOutcomeAndPrivateData() {
        val record = CallRecord(
            direction = CallDirection.INCOMING,
            peer = CallPeerPhone.parse("+15550123"),
            startedAtMs = now - 3_600_000L,
            connectedAtMs = now - 3_500_000L,
            endedAtMs = now - 3_400_000L,
            durationSec = 100L,
            outcome = CallOutcome.COMPLETED,
        )

        val presentation = callHistoryRowPresentation(record, now, localDate)

        assertEquals("+15550123", presentation.peerDisplay)
        assertEquals("来电", presentation.directionText)
        assertEquals("已完成", presentation.outcomeText)
        assertEquals("1 分 40 秒", presentation.durationText)
        assertEquals("1 小时前", presentation.timeText)
        assertEquals("CallHistoryRowPresentation", presentation.toString())
        assertFalse(presentation.toString().contains("15550123"))
        assertFalse(presentation.toString().contains("correlation"))
        assertFalse(presentation.toString().contains("token"))
    }

    @Test
    fun invalidCallDurationAndTimeStayBounded() {
        val record = CallRecord(
            direction = CallDirection.OUTGOING,
            peer = null,
            startedAtMs = -1L,
            connectedAtMs = null,
            endedAtMs = null,
            durationSec = Long.MAX_VALUE,
            outcome = CallOutcome.FAILED,
        )

        val presentation = callHistoryRowPresentation(record, now, localDate)

        assertEquals("未知号码", presentation.peerDisplay)
        assertEquals("时间未知", presentation.timeText)
        assertEquals("时长未知", presentation.durationText)
        assertEquals("未完成", presentation.outcomeText)
    }
}
