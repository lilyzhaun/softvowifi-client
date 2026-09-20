package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDraftStateTest {
    @Test
    fun draftSaveRestoreIsDeterministicAndRedactsToString() {
        val draft = MessageDraftState.create("+155501", "line one\nline two")

        val saved = MessageDraftRestorePolicy.save(checkNotNull(draft))
        val restored = MessageDraftRestorePolicy.restore(saved)

        assertEquals(saved, MessageDraftRestorePolicy.save(checkNotNull(restored)))
        assertEquals("+155501", restored.recipient)
        assertEquals("line one\nline two", restored.body)
        assertEquals("MessageDraftState(bodyLength=17)", restored.toString())
        assertFalse(restored.toString().contains("155501"))
    }

    @Test
    fun cancelDropsSavedDraftAndMalformedStateNeverPartiallyRestores() {
        val draft = checkNotNull(MessageDraftState.create("155501", "body"))

        assertNull(MessageDraftRestorePolicy.cancel())
        assertNull(MessageDraftRestorePolicy.restore("not-json"))
        assertNull(MessageDraftRestorePolicy.restore("""{"version":2,"recipient":"155501","body":"body"}"""))
        assertNull(MessageDraftRestorePolicy.restore("""{"version":1,"recipient":"sip:user","body":"body"}"""))
        assertNull(MessageDraftRestorePolicy.restore("""{"version":1,"recipient":"155501","body":"\u0000"}"""))
    }

    @Test
    fun draftBoundsRecipientAndBodyWithoutInventingSendSemantics() {
        assertTrue(MessageDraftState.create("+12345678901234567890", "x".repeat(1_000)) != null)
        assertNull(MessageDraftState.create("+123456789012345678901", "body"))
        assertNull(MessageDraftState.create("155501", "x".repeat(1_001)))
        assertNull(MessageDraftState.create("155501", "body\u0000"))
    }
}
