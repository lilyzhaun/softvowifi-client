package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.SimControlUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrationStatusScreenTest {
    @Test
    fun systemSubscriptionLabelIsUserFacingAndBounded() {
        val label = systemSubscriptionLabel(2)

        assertEquals("系统订阅 2", label)
        assertFalse(label.contains("subId", ignoreCase = true))
    }

    @Test
    fun simControlCompatibilityPresentationKeepsSafeEmptyCopy() {
        val presentation = registrationEnginePresentation(SimControlUiState.Ready(emptyList()))

        assertEquals("当前没有可显示的 SIM", presentation.message)
        assertTrue(presentation.rows.isEmpty())
        assertTrue("/data/" !in presentation.message.orEmpty())
    }
}
