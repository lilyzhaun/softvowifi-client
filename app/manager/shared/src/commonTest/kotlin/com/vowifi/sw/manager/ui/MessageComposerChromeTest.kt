package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageComposerChromeTest {
    @Test
    fun messageComposerShadowSpecMatchesRestrainedElevation() {
        val spec = messageComposerShadowSpec()
        assertEquals(10, spec.radiusDp)
        assertEquals(0.1f, spec.lightAlpha)
        assertEquals(0.2f, spec.darkAlpha)
    }

    @Test
    fun messageComposerLayoutContractPreservesRequiredDimensions() {
        val layout = messageComposerLayoutContract()
        assertEquals(48, layout.sendTargetDp)
        assertTrue(layout.horizontal)
        assertTrue(layout.capsule)
        assertTrue(layout.circularSendAction)
    }
}
