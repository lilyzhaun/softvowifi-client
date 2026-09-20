package com.vowifi.sw.manager.ui

internal data class BackdropOwnershipContract(
    val rootPagerLayerBackdropAttachments: Int,
    val perRootPageLayerBackdropAttachments: Int,
    val privateSubpageBackdropAttachments: Int,
    val bottomBarConsumesRootPagerBackdrop: Boolean,
    val textureEffectCount: Int,
    val rootPagerBottomBarConsumers: Int,
    val bottomBarLocalBackdropAttachments: Int,
    val bottomBarLocalBackdropConsumers: Int,
    val bottomBarDrawBackdropCallCount: Int,
    val rootPagerOtherConsumers: Int,
    val rootPageLocalBackdropAttachments: Int,
    val rootPageTopBarConsumers: Int,
    val privateSubpageTopBarConsumers: Int,
    val consumerInsideOwnCaptureSubtree: Boolean,
    val drawBackdropConsumerCount: Int,
    val embeddedPageScaffoldBackdropAttachments: Int,
)

internal fun backdropOwnershipContract(
    effectiveBlur: Boolean,
    subpageVisible: Boolean,
    bottomMode: BottomChromeMode = BottomChromeMode.FLOATING,
): BackdropOwnershipContract = BackdropOwnershipContract(
    rootPagerLayerBackdropAttachments = if (effectiveBlur) 1 else 0,
    perRootPageLayerBackdropAttachments = if (effectiveBlur) 1 else 0,
    privateSubpageBackdropAttachments = if (effectiveBlur && subpageVisible) 1 else 0,
    bottomBarConsumesRootPagerBackdrop = effectiveBlur && !subpageVisible && bottomMode == BottomChromeMode.NORMAL,
    textureEffectCount = 0,
    rootPagerBottomBarConsumers = if (effectiveBlur && !subpageVisible && bottomMode == BottomChromeMode.NORMAL) 1 else 0,
    bottomBarLocalBackdropAttachments = 0,
    bottomBarLocalBackdropConsumers = 0,
    bottomBarDrawBackdropCallCount = if (effectiveBlur && !subpageVisible && bottomMode == BottomChromeMode.NORMAL) 1 else 0,
    rootPagerOtherConsumers = 0,
    rootPageLocalBackdropAttachments = if (effectiveBlur) 1 else 0,
    rootPageTopBarConsumers = if (effectiveBlur) 1 else 0,
    privateSubpageTopBarConsumers = if (effectiveBlur && subpageVisible) 1 else 0,
    consumerInsideOwnCaptureSubtree = false,
    drawBackdropConsumerCount = if (effectiveBlur) 1 + if (subpageVisible || bottomMode == BottomChromeMode.NORMAL) 1 else 0 else 0,
    embeddedPageScaffoldBackdropAttachments = 0,
)

internal enum class ChromeBlurRenderer {
    DRAW_BACKDROP,
    TRANSPARENT,
}

internal fun chromeSurfaceFallbackRenderer(
    effectiveBlur: Boolean,
    backdropAvailable: Boolean,
): ChromeBlurRenderer = if (effectiveBlur && backdropAvailable) {
    ChromeBlurRenderer.DRAW_BACKDROP
} else {
    ChromeBlurRenderer.TRANSPARENT
}

internal data class ChromeBlurContract(
    val renderer: ChromeBlurRenderer,
    val usesTextureBlur: Boolean,
)

internal fun chromeBlurContract(
    effectiveBlur: Boolean,
    backdropAvailable: Boolean,
): ChromeBlurContract = ChromeBlurContract(
    renderer = chromeSurfaceFallbackRenderer(effectiveBlur, backdropAvailable),
    usesTextureBlur = false,
)
