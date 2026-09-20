package com.vowifi.sw.manager.ui

internal enum class RootShellChildAlignment { BottomCenter }

internal data class KsuRootShellLayoutContract(
    val floatingHostFullWidth: Boolean,
    val floatingHostTransparent: Boolean,
    val floatingChildAlignment: RootShellChildAlignment,
    val floatingChildIntrinsicWidth: Boolean,
    val pagerConsumesBottomPadding: Boolean,
    val rootContentConsumesBottomPadding: Boolean,
    val contentUnderlaysFloatingHost: Boolean,
    val normalModeReservesBottomSpace: Boolean,
    val normalModeFullWidth: Boolean,
)

internal fun ksuRootShellLayoutContract(): KsuRootShellLayoutContract = KsuRootShellLayoutContract(
    floatingHostFullWidth = true,
    floatingHostTransparent = true,
    floatingChildAlignment = RootShellChildAlignment.BottomCenter,
    floatingChildIntrinsicWidth = false,
    pagerConsumesBottomPadding = false,
    rootContentConsumesBottomPadding = true,
    contentUnderlaysFloatingHost = true,
    normalModeReservesBottomSpace = true,
    normalModeFullWidth = true,
)

internal data class SettingsCardStructureContract(
    val cardCount: Int,
    val rowsByCard: List<List<SettingsRowId.Root>>,
    val smallTitleCount: Int,
    val horizontalInsetDp: Int,
    val topAndCardGapDp: Int,
    val topBarStyle: SettingsRootTopBarStyle,
)

internal fun settingsCardStructureContract(): SettingsCardStructureContract = SettingsCardStructureContract(
    cardCount = 4,
    rowsByCard = listOf(
        listOf(SettingsRowId.Root.APPEARANCE, SettingsRowId.Root.CONFIGURATION),
        listOf(SettingsRowId.Root.DIAGNOSTICS, SettingsRowId.Root.OPERATIONAL_LOGS),
        listOf(SettingsRowId.Root.CALL_NOTIFICATION),
        listOf(SettingsRowId.Root.ABOUT),
    ),
    smallTitleCount = 0,
    horizontalInsetDp = 12,
    topAndCardGapDp = 12,
    topBarStyle = SettingsRootTopBarStyle.LARGE_COLLAPSIBLE,
)
