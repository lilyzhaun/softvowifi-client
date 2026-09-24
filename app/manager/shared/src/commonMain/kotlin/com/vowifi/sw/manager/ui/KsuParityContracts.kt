package com.vowifi.sw.manager.ui

internal enum class RootShellChildAlignment { BottomCenter }
internal enum class FloatingDragGestureOwner { Indicator }

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
    floatingChildIntrinsicWidth = true,
    pagerConsumesBottomPadding = false,
    rootContentConsumesBottomPadding = true,
    contentUnderlaysFloatingHost = true,
    normalModeReservesBottomSpace = true,
    normalModeFullWidth = true,
)

internal data class FloatingRowLayoutContract(
    val outerHeightDp: Int,
    val innerPaddingDp: Int,
    val baseInnerHeightDp: Int,
    val indicatorHeightDp: Int,
    val tabMinimumWidthDp: Int,
    val tabMinimumHeightDp: Int,
    val labelSizeSp: Int,
    val labelLineHeightSp: Int,
    val tabClickIndication: TabClickIndication,
    val dragGestureOwner: FloatingDragGestureOwner,
    val baseRowDraggable: Boolean,
)

internal fun floatingRowLayoutContract(): FloatingRowLayoutContract = FloatingRowLayoutContract(
    outerHeightDp = 64,
    innerPaddingDp = 4,
    baseInnerHeightDp = 56,
    indicatorHeightDp = 56,
    tabMinimumWidthDp = 76,
    tabMinimumHeightDp = 48,
    labelSizeSp = 11,
    labelLineHeightSp = 14,
    tabClickIndication = TabClickIndication.NONE,
    dragGestureOwner = FloatingDragGestureOwner.Indicator,
    baseRowDraggable = false,
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

internal data class SafeBackdropContract(
    val pagerCaptureCount: Int,
    val bottomBarConsumerCount: Int,
    val bottomBarLocalCaptureCount: Int,
    val bottomBarLocalConsumerCount: Int,
    val bottomBarDrawBackdropCallCount: Int,
    val rootTopBarConsumerCount: Int,
    val pageLocalCaptureCount: Int,
    val pageTopBarConsumerCount: Int,
    val usesDrawBackdrop: Boolean,
    val usesTextureBlur: Boolean,
    val opaqueFallback: Boolean,
)

internal fun safeBackdropContract(effectiveBlur: Boolean): SafeBackdropContract = SafeBackdropContract(
    pagerCaptureCount = if (effectiveBlur) 1 else 0,
    bottomBarConsumerCount = if (effectiveBlur) 3 else 0,
    bottomBarLocalCaptureCount = if (effectiveBlur) 1 else 0,
    bottomBarLocalConsumerCount = if (effectiveBlur) 1 else 0,
    bottomBarDrawBackdropCallCount = if (effectiveBlur) 3 else 0,
    rootTopBarConsumerCount = 0,
    pageLocalCaptureCount = if (effectiveBlur) 1 else 0,
    pageTopBarConsumerCount = if (effectiveBlur) 1 else 0,
    usesDrawBackdrop = effectiveBlur,
    usesTextureBlur = false,
    opaqueFallback = !effectiveBlur,
)
