package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.RegistrationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

class StatusPrimitivesContractTest {
    @Test
    fun statePanelLayoutContractEnforcesMiuixCardAndBasicComponentWithoutGiantCenteredSurface() {
        // Given & When
        val contract = statePanelLayoutContract

        // Then
        assertTrue(contract.usesCard)
        assertTrue(contract.usesBasicComponent)
        assertTrue(contract.usesOfficialProgressIndicator)
        assertFalse(contract.centeredPanel)
        assertEquals(48, contract.retryMinimumTouchDp)
        assertTrue(contract.fullWidth)
    }

    @Test
    fun statePanelSpecMapsLoadingAndRetryContract() {
        // Given
        val loadingSpec = StatePanelSpec(
            icon = MiuixIcons.Refresh,
            tone = StatePanelTone.INTERACTIVE,
            title = "正在读取",
            description = "正在读取状态",
            loading = true,
        )
        val errorWithRetry = StatePanelSpec(
            icon = MiuixIcons.Close,
            tone = StatePanelTone.ERROR,
            title = "读取失败",
            description = "请检查网络后重试",
            loading = false,
            retryDescription = "重试读取状态",
        )

        // Then
        assertTrue(loadingSpec.loading)
        assertNull(loadingSpec.retryDescription)
        assertFalse(errorWithRetry.loading)
        assertEquals("重试读取状态", errorWithRetry.retryDescription)
    }

    @Test
    fun registrationStatusBadgeSpecMapsAllRegistrationStatesToSemanticTones() {
        // Given & When
        val registered = registrationStatusBadgeSpec(RegistrationState.REGISTERED)
        val registering = registrationStatusBadgeSpec(RegistrationState.REGISTERING)
        val unregistered = registrationStatusBadgeSpec(RegistrationState.UNREGISTERED)
        val unknown = registrationStatusBadgeSpec(RegistrationState.UNKNOWN)

        // Then
        assertEquals("已注册", registered.label)
        assertEquals(SemanticTone.SUCCESS, registered.tone)
        assertTrue(registered.softWrap)
        assertNull(registered.maxLines)

        assertEquals("注册中", registering.label)
        assertEquals(SemanticTone.WARNING, registering.tone)
        assertTrue(registering.softWrap)

        assertEquals("未注册", unregistered.label)
        assertEquals(SemanticTone.ERROR, unregistered.tone)
        assertTrue(unregistered.softWrap)

        assertEquals("未知", unknown.label)
        assertEquals(SemanticTone.NEUTRAL, unknown.tone)
        assertTrue(unknown.softWrap)
    }
}
