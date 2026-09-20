package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncomingCallNotificationCoordinatorTest {
    private var keyCounter = 0
    private fun keyGenerator(): String = "key-${++keyCounter}"

    private val idleState = IncomingCallNotificationState(
        notificationKey = null,
        peerName = null,
    )

    @Test
    fun ringingWithPeerPostsNotificationWithFullNormalizedPeerName() {
        val (state, command) = computeNotificationCommand(
            idleState, CallPhase.Ringing, "+13800138000", ::keyGenerator,
        )
        val post = assertIs<IncomingCallNotificationCommand.Post>(command)
        assertEquals("+13800138000", post.peerName)
        assertEquals("key-1", post.notificationKey)
        assertEquals("key-1", state.notificationKey)
        assertEquals("+13800138000", state.peerName)
        assertEquals("IncomingCallNotificationCommand.Post", post.toString())
        assertEquals("IncomingCallNotificationState(isActive=true)", state.toString())
    }

    @Test
    fun ringingWithoutPeerUsesUnknownCallerName() {
        val (state, command) = computeNotificationCommand(
            idleState, CallPhase.Ringing, null, ::keyGenerator,
        )
        val post = assertIs<IncomingCallNotificationCommand.Post>(command)
        assertEquals(UNKNOWN_CALLER_NAME, post.peerName)
        assertEquals(UNKNOWN_CALLER_NAME, state.peerName)
    }

    @Test
    fun ringingWithInvalidPeerUsesUnknownCallerName() {
        listOf(
            "anonymous" to "anonymous",
            "sip" to "sip:user@example.invalid",
            "raw" to "raw:13800138000",
            "control" to "12\u000034",
            "21-digits" to "123456789012345678901",
        ).forEach { (caseName, peerName) ->
            val (state, command) = computeNotificationCommand(
                idleState, CallPhase.Ringing, peerName, ::keyGenerator,
            )
            val post = assertIs<IncomingCallNotificationCommand.Post>(command)
            assertEquals(UNKNOWN_CALLER_NAME, post.peerName, caseName)
            assertEquals(UNKNOWN_CALLER_NAME, state.peerName, caseName)
        }
    }

    @Test
    fun incomingCallDisplayUsesTheSameOneAndTwentyDigitBoundaries() {
        listOf(
            "1" to "1",
            "+1" to "+1",
            "12345678901234567890" to "12345678901234567890",
            "+12345678901234567890" to "+12345678901234567890",
        ).forEach { (peerName, expected) ->
            val (_, command) = computeNotificationCommand(
                idleState, CallPhase.Ringing, peerName, ::keyGenerator,
            )
            assertEquals(expected, assertIs<IncomingCallNotificationCommand.Post>(command).peerName)
        }
    }

    @Test
    fun duplicateRingingIsNoOp() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-1", peerName = "13800138000",
        )
        val (state, command) = computeNotificationCommand(
            active, CallPhase.Ringing, "13800138000", ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.NoOp>(command)
        assertEquals("key-1", state.notificationKey)
    }

    @Test
    fun newCallAfterPreviousReplacesNotification() {
        // Simulate: call A ringing -> ended -> call B ringing
        val (s1, _) = computeNotificationCommand(
            idleState, CallPhase.Ringing, "13800138000", ::keyGenerator,
        )
        assertEquals("key-1", s1.notificationKey)

        val (s2, c2) = computeNotificationCommand(
            s1, CallPhase.Idle, null, ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.Cancel>(c2)
        assertNull(s2.notificationKey)

        val (s3, c3) = computeNotificationCommand(
            s2, CallPhase.Ringing, "13900139000", ::keyGenerator,
        )
        val post = assertIs<IncomingCallNotificationCommand.Post>(c3)
        assertEquals("key-2", post.notificationKey)
        assertEquals("13900139000", post.peerName)
    }

    @Test
    fun endedCancelsActiveNotification() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-1", peerName = "13800138000",
        )
        val (state, command) = computeNotificationCommand(
            active, CallPhase.Idle, null, ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.Cancel>(command)
        assertNull(state.notificationKey)
        assertNull(state.peerName)
    }

    @Test
    fun idleToIdleIsNoOp() {
        val (state, command) = computeNotificationCommand(
            idleState, CallPhase.Idle, null, ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.NoOp>(command)
        assertFalse(state.isActive)
    }

    @Test
    fun inCallCancelsActiveNotification() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-1", peerName = "13800138000",
        )
        val (_, command) = computeNotificationCommand(
            active, CallPhase.InCall, "13800138000", ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.Cancel>(command)
    }

    @Test
    fun idleToRingingWithNoPeerUsesUnknownCaller() {
        val (state, command) = computeNotificationCommand(
            idleState, CallPhase.Ringing, null, ::keyGenerator,
        )
        assertIs<IncomingCallNotificationCommand.Post>(command)
        assertEquals(UNKNOWN_CALLER_NAME, state.peerName)
    }

    @Test
    fun answerActionWithMatchingKeyReturnsAnswer() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-1", peerName = "13800138000",
        )
        val result = resolveIncomingCallAction(active, "key-1", IncomingCallAction.ANSWER)
        val answer = assertIs<IncomingCallActionResult.Answer>(result)
        assertEquals("key-1", answer.notificationKey)
    }

    @Test
    fun rejectActionWithMatchingKeyReturnsReject() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-1", peerName = "13800138000",
        )
        val result = resolveIncomingCallAction(active, "key-1", IncomingCallAction.REJECT)
        assertIs<IncomingCallActionResult.Reject>(result)
    }

    @Test
    fun staleKeyIsIgnored() {
        val active = IncomingCallNotificationState(
            notificationKey = "key-2", peerName = "13800138000",
        )
        val result = resolveIncomingCallAction(active, "key-1", IncomingCallAction.ANSWER)
        assertIs<IncomingCallActionResult.Ignore>(result)
    }

    @Test
    fun actionWithNoActiveNotificationIsIgnored() {
        val result = resolveIncomingCallAction(idleState, "key-1", IncomingCallAction.ANSWER)
        assertIs<IncomingCallActionResult.Ignore>(result)
    }

    @Test
    fun answerActivityRequiresMatchingActionAndMicrophonePermission() {
        assertTrue(shouldHandleIncomingCallAnswer(IncomingCallAction.ANSWER.actionName, true))
        assertFalse(shouldHandleIncomingCallAnswer(IncomingCallAction.ANSWER.actionName, false))
        assertFalse(shouldHandleIncomingCallAnswer(IncomingCallAction.REJECT.actionName, true))
        assertFalse(shouldHandleIncomingCallAnswer(null, true))
    }

    @Test
    fun notificationKeyIsUniqueAcrossCalls() {
        val (s1, _) = computeNotificationCommand(
            idleState, CallPhase.Ringing, "123", ::keyGenerator,
        )
        assertNotNull(s1.notificationKey)
        assertEquals("key-1", s1.notificationKey)

        val idleAgain = IncomingCallNotificationState(null, null)
        val (s2, _) = computeNotificationCommand(
            idleAgain, CallPhase.Ringing, "456", ::keyGenerator,
        )
        assertNotNull(s2.notificationKey)
        assertEquals("key-2", s2.notificationKey)
        assertTrue(s1.notificationKey != s2.notificationKey)
    }
}
