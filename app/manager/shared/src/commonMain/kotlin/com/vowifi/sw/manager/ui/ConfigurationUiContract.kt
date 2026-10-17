package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.vowifi.sw.manager.data.systemSubscriptionSummary
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationTransport

internal enum class ConfigurationTransportControl { OVERLAY_DROPDOWN_PREFERENCE }

internal data class CompactConfigurationUiContract(
    val usesDefaultCardCornerRadius: Boolean,
    val cardInsideVerticalMarginDp: Int,
    val verticalScrollOwnerCount: Int,
    val embeddedNestedScrollEnabled: Boolean,
    val transportControl: ConfigurationTransportControl,
    val actionMinimumHeightDp: Int,
    val supportsTwoActiveRows: Boolean,
    val cardsSeparatedBy12Dp: Boolean = true,
    val collapsedByDefault: Boolean = true,
    val independentDisclosure: Boolean = true,
    val usesInterruptibleSpringSizeAnimation: Boolean = true,
    val directSnapContract: Boolean = false,
    val cardOwnedPressIndication: Boolean = true,
    val innerRowRectangularIndication: Boolean = false,
    val disclosureHeaderUsesStockArrowPreference: Boolean = false,
    val disclosureHeaderUsesCustomArrow: Boolean = true,
    val disclosureArrowUsesInterruptibleSpring: Boolean = true,
    val collapsedArrowRotationDegrees: Float = 0f,
    val expandedArrowRotationDegrees: Float = 90f,
)

internal val compactConfigurationUiContract = CompactConfigurationUiContract(
    usesDefaultCardCornerRadius = true,
    cardInsideVerticalMarginDp = 4,
    verticalScrollOwnerCount = 1,
    embeddedNestedScrollEnabled = false,
    transportControl = ConfigurationTransportControl.OVERLAY_DROPDOWN_PREFERENCE,
    actionMinimumHeightDp = 48,
    supportsTwoActiveRows = true,
    cardsSeparatedBy12Dp = true,
    collapsedByDefault = true,
    independentDisclosure = true,
    usesInterruptibleSpringSizeAnimation = true,
    directSnapContract = false,
    cardOwnedPressIndication = true,
    innerRowRectangularIndication = false,
    disclosureHeaderUsesStockArrowPreference = false,
    disclosureHeaderUsesCustomArrow = true,
    disclosureArrowUsesInterruptibleSpring = true,
    collapsedArrowRotationDegrees = 0f,
    expandedArrowRotationDegrees = 90f,
)

internal data class ConfigurationDisclosureInteractionContract(
    val cardClickable: Boolean,
    val headerClickable: Boolean,
    val headerUsesTopSquircleClip: Boolean,
    val headerUsesWrapContentInputBounds: Boolean,
    val cardInterceptsChildControls: Boolean,
    val clickOwnerCount: Int = 1,
    val usesStockArrowPreference: Boolean = false,
    val arrowRotationDegrees: Float,
    val arrowUsesInterruptibleSpring: Boolean = true,
    val headerGivesBoundedShapedFillFeedback: Boolean = headerUsesTopSquircleClip,
    val headerInterceptsChildControls: Boolean = false,
)

internal fun configurationDisclosureInteractionContract(
    expanded: Boolean,
): ConfigurationDisclosureInteractionContract = if (expanded) {
    ConfigurationDisclosureInteractionContract(
        cardClickable = false,
        headerClickable = true,
        headerUsesTopSquircleClip = true,
        headerUsesWrapContentInputBounds = true,
        cardInterceptsChildControls = false,
        clickOwnerCount = 1,
        usesStockArrowPreference = false,
        arrowRotationDegrees = 90f,
        arrowUsesInterruptibleSpring = true,
        headerGivesBoundedShapedFillFeedback = true,
        headerInterceptsChildControls = false,
    )
} else {
    ConfigurationDisclosureInteractionContract(
        cardClickable = true,
        headerClickable = false,
        headerUsesTopSquircleClip = false,
        headerUsesWrapContentInputBounds = true,
        cardInterceptsChildControls = false,
        clickOwnerCount = 1,
        usesStockArrowPreference = false,
        arrowRotationDegrees = 0f,
        arrowUsesInterruptibleSpring = true,
        headerGivesBoundedShapedFillFeedback = false,
        headerInterceptsChildControls = false,
    )
}

internal data class ConfigurationFact(val title: String, val summary: String)

internal fun configurationSystemSubscriptionFact(instance: ConfigurationInstance): ConfigurationFact? =
    systemSubscriptionSummary(instance.systemSubscription)?.let { ConfigurationFact("系统订阅", it) }

internal class ConfigurationExpansionState(
    private val expandedMap: MutableMap<String, Boolean> = mutableStateMapOf(),
) {
    fun isExpanded(id: ConfigurationInstanceId): Boolean = expandedMap[id.value] == true

    fun toggle(id: ConfigurationInstanceId) {
        expandedMap[id.value] = !(expandedMap[id.value] == true)
    }

    fun setExpanded(id: ConfigurationInstanceId, expanded: Boolean) {
        expandedMap[id.value] = expanded
    }

    companion object {
        val StateSaver = Saver<ConfigurationExpansionState, List<String>>(
            save = { state -> state.expandedMap.filterValues { it }.keys.toList() },
            restore = { keys ->
                ConfigurationExpansionState(mutableStateMapOf<String, Boolean>().apply {
                    keys.forEach { put(it, true) }
                })
            },
        )
    }
}

@Composable
internal fun rememberConfigurationExpansionState(): ConfigurationExpansionState =
    rememberSaveable(saver = ConfigurationExpansionState.StateSaver) { ConfigurationExpansionState() }

internal data class SimCardPresentationContract(
    val cardGapDp: Int,
    val collapsedByDefault: Boolean,
    val independentDisclosure: Boolean,
    val collapsedSummary: String,
    val isExpanded: Boolean,
)

internal fun simCardPresentationContract(
    instance: ConfigurationInstance,
    transport: ConfigurationTransport,
    status: String?,
    expanded: Boolean,
): SimCardPresentationContract = SimCardPresentationContract(
    cardGapDp = 12,
    collapsedByDefault = true,
    independentDisclosure = true,
    collapsedSummary = listOfNotNull(status ?: "已匹配配置", transportLabel(transport)).joinToString(" • "),
    isExpanded = expanded,
)
