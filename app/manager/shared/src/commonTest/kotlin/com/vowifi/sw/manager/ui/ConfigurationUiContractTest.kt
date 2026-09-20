package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationUiContractTest {
    @Test
    fun compactConfigurationContractKeepsSettingsRhythmAndSingleScrollOwner() {
        assertTrue(compactConfigurationUiContract.usesDefaultCardCornerRadius)
        assertEquals(4, compactConfigurationUiContract.cardInsideVerticalMarginDp)
        assertEquals(1, compactConfigurationUiContract.verticalScrollOwnerCount)
        assertFalse(compactConfigurationUiContract.embeddedNestedScrollEnabled)
        assertEquals(
            ConfigurationTransportControl.OVERLAY_DROPDOWN_PREFERENCE,
            compactConfigurationUiContract.transportControl,
        )
        assertEquals(48, compactConfigurationUiContract.actionMinimumHeightDp)
        assertTrue(compactConfigurationUiContract.supportsTwoActiveRows)
        assertTrue(compactConfigurationUiContract.cardsSeparatedBy12Dp)
        assertTrue(compactConfigurationUiContract.collapsedByDefault)
        assertTrue(compactConfigurationUiContract.independentDisclosure)
        assertTrue(compactConfigurationUiContract.usesInterruptibleSpringSizeAnimation)
        assertFalse(compactConfigurationUiContract.directSnapContract)
        assertTrue(compactConfigurationUiContract.cardOwnedPressIndication)
        assertFalse(compactConfigurationUiContract.innerRowRectangularIndication)
        assertFalse(compactConfigurationUiContract.disclosureHeaderUsesStockArrowPreference)
        assertTrue(compactConfigurationUiContract.disclosureHeaderUsesCustomArrow)
        assertTrue(compactConfigurationUiContract.disclosureArrowUsesInterruptibleSpring)
        assertEquals(0f, compactConfigurationUiContract.collapsedArrowRotationDegrees)
        assertEquals(90f, compactConfigurationUiContract.expandedArrowRotationDegrees)
    }

    @Test
    fun disclosureMovesClickOwnershipToTheHeaderWhenExpanded() {
        val collapsed = configurationDisclosureInteractionContract(expanded = false)
        val expanded = configurationDisclosureInteractionContract(expanded = true)

        assertTrue(collapsed.cardClickable)
        assertFalse(collapsed.headerClickable)
        assertEquals(1, collapsed.clickOwnerCount)
        assertFalse(collapsed.headerUsesTopSquircleClip)
        assertFalse(collapsed.usesStockArrowPreference)
        assertEquals(0f, collapsed.arrowRotationDegrees)
        assertTrue(collapsed.arrowUsesInterruptibleSpring)

        assertFalse(expanded.cardClickable)
        assertTrue(expanded.headerClickable)
        assertEquals(1, expanded.clickOwnerCount)
        assertTrue(expanded.headerUsesTopSquircleClip)
        assertTrue(expanded.headerGivesBoundedShapedFillFeedback)
        assertTrue(expanded.headerUsesWrapContentInputBounds)
        assertFalse(expanded.cardInterceptsChildControls)
        assertFalse(expanded.headerInterceptsChildControls)
        assertFalse(expanded.usesStockArrowPreference)
        assertEquals(90f, expanded.arrowRotationDegrees)
        assertTrue(expanded.arrowUsesInterruptibleSpring)
    }

    @Test
    fun simCardPresentationContractReflectsIndependentDisclosureAndCollapsedState() {
        val instance = ConfigurationInstance(
            instanceId = ConfigurationInstanceId("sim-1"),
            displayLabel = "SIM 1",
            lifecycle = ConfigurationLifecycle.RUNNING,
            transport = ConfigurationTransport.WIFI,
            smsc = "",
            epdgFqdn = "",
            imsRealm = "",
            dns = "",
            resolvedEpdgFqdn = "epdg.example.invalid",
            resolvedImsRealm = "ims.example.invalid",
            mcc = "310",
            mnc = "260",
        )

        val collapsed = simCardPresentationContract(
            instance = instance,
            transport = ConfigurationTransport.WIFI,
            status = "已匹配配置",
            expanded = false,
        )
        val expanded = simCardPresentationContract(
            instance = instance,
            transport = ConfigurationTransport.SIM2,
            status = "运行中",
            expanded = true,
        )

        assertEquals(12, collapsed.cardGapDp)
        assertTrue(collapsed.collapsedByDefault)
        assertTrue(collapsed.independentDisclosure)
        assertEquals("已匹配配置 • Wi-Fi", collapsed.collapsedSummary)
        assertFalse(collapsed.isExpanded)

        assertEquals("运行中 • SIM 2 数据", expanded.collapsedSummary)
        assertTrue(expanded.isExpanded)
    }

    @Test
    fun configurationExpansionStateSurvivesIndependentToggles() {
        val expansion = ConfigurationExpansionState()
        val sim1 = ConfigurationInstanceId("sim-1")
        val sim2 = ConfigurationInstanceId("sim-2")

        assertFalse(expansion.isExpanded(sim1))
        assertFalse(expansion.isExpanded(sim2))

        expansion.toggle(sim1)
        assertTrue(expansion.isExpanded(sim1))
        assertFalse(expansion.isExpanded(sim2))

        expansion.toggle(sim2)
        assertTrue(expansion.isExpanded(sim1))
        assertTrue(expansion.isExpanded(sim2))

        expansion.toggle(sim1)
        assertFalse(expansion.isExpanded(sim1))
        assertTrue(expansion.isExpanded(sim2))

        expansion.setExpanded(sim1, true)
        assertTrue(expansion.isExpanded(sim1))
    }
}
