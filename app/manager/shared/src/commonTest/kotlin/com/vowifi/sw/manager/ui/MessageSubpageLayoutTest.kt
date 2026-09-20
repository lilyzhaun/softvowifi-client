package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageSubpageLayoutTest {
    @Test
    fun threadAndNewMessageConsumeSceneTopInsetWhileComposerConsumesBottomInset() {
        val ownership = messageSubpageInsetOwnership()

        assertTrue(MessageSubpageRegion.THREAD_TIMELINE in ownership.topConsumers)
        assertTrue(MessageSubpageRegion.NEW_MESSAGE_FORM in ownership.topConsumers)
        assertTrue(MessageSubpageRegion.COMPOSER in ownership.bottomConsumers)
        assertEquals(MessageSubpageRegion.COMPOSER, ownership.imeOwner)
    }

    @Test
    fun missingThreadStateConsumesBothSceneTopAndBottomInsets() {
        val ownership = messageSubpageInsetOwnership()

        assertTrue(MessageSubpageRegion.MISSING_THREAD_STATE in ownership.topConsumers)
        assertTrue(MessageSubpageRegion.MISSING_THREAD_STATE in ownership.bottomConsumers)
    }

    @Test
    fun threadTimelineFillsViewportAndAnchorsShortContentAtBottomChronologically() {
        val contract = messageSubpageLayoutContract()

        val spacing = messageSubpageSpacing()

        assertTrue(MessageSubpageRegion.THREAD_TIMELINE in contract.filledViewports)
        assertEquals(MessageContentAnchor.BOTTOM, contract.anchors[MessageSubpageRegion.THREAD_TIMELINE])
        assertTrue(contract.chronologicalThreadItems)
        assertEquals(8, spacing.contentTopGapDp)
        assertEquals(12, spacing.threadItemGapDp)
        assertEquals(12, spacing.threadBottomGapDp)
    }

    @Test
    fun newMessageFillsViewportAndRemainsTopAligned() {
        val contract = messageSubpageLayoutContract()

        assertTrue(MessageSubpageRegion.NEW_MESSAGE_FORM in contract.filledViewports)
        assertEquals(MessageContentAnchor.TOP, contract.anchors[MessageSubpageRegion.NEW_MESSAGE_FORM])
        assertEquals(8, messageSubpageSpacing().contentTopGapDp)
    }

    @Test
    fun composerBottomPaddingAddsBasePaddingToSceneBottomInsetWithoutIme() {
        assertEquals(8.dp, messageComposerBottomPadding(0.dp))
        assertEquals(56.dp, messageComposerBottomPadding(48.dp))
        assertEquals(24.dp, messageComposerBottomPadding(16.dp))
    }
}
