package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppWindowPolicyTest {
    @Test
    fun androidWindowUsesEdgeToEdgeAndKeepsImeResize() {
        val policy = appWindowPolicy()

        assertTrue(policy.edgeToEdge)
        assertTrue(policy.transparentStatusBar)
        assertTrue(policy.transparentNavigationBar)
        assertEquals(29, policy.disableNavigationBarContrastFromApi)
        assertTrue(policy.preserveAdjustResize)
    }
}
