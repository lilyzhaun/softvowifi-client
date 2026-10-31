@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.OWNER_A
import com.vowifi.sw.manager.data.OWNER_B
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CallMessageDraftRenderTest {
    @Test fun prefilledCallDraftKeepsOrdinaryAndOtherOwnersDraftsAndRestoresByRecipient() = onMotionTestThread { runTest {
        lateinit var draft: BoundMessageDraft
        MotionTestEnvironment(testScheduler, 420, 900) {
            draft = rememberBoundMessageDraft(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = OWNER_A), false) { true }
        }.use { e ->
            s4Frames(e, 2)
            assertTrue(draft.confirmOwner(OWNER_B)); s4Frames(e, 2)
            draft.draft.updateRecipient("155509"); draft.draft.updateBody("synthetic ordinary draft"); s4Frames(e, 2)
            assertTrue(draft.openRecipient(OWNER_A, "+155501")); s4Frames(e, 2)
            assertEquals("+155501", draft.draft.recipient); assertEquals("", draft.draft.body)
            draft.draft.updateBody("synthetic call draft"); s4Frames(e, 2)
            assertTrue(draft.openRecipient(OWNER_B, "+155501")); s4Frames(e, 2)
            assertEquals(OWNER_B, draft.owner); assertEquals("", draft.draft.body)
            assertTrue(draft.openRecipient(OWNER_A, "+155501")); s4Frames(e, 2)
            assertEquals("synthetic call draft", draft.draft.body)
            draft.openNew(); s4Frames(e, 2)
            assertEquals(OWNER_B, draft.owner, "普通草稿恢复原SIM绑定，不能被通话入口的SIM替换")
            assertEquals("155509", draft.draft.recipient); assertEquals("synthetic ordinary draft", draft.draft.body)
        }
    } }
}
