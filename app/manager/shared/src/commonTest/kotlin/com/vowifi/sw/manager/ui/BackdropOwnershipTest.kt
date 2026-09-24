package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackdropOwnershipTest {
    @Test
    fun chromeUsesDrawBackdropWithoutTextureBlur() {
        val blur = chromeBlurContract(effectiveBlur = true, backdropAvailable = true)
        val fallback = chromeBlurContract(effectiveBlur = true, backdropAvailable = false)

        assertEquals(ChromeBlurRenderer.DRAW_BACKDROP, blur.renderer)
        assertFalse(blur.usesTextureBlur)
        assertEquals(ChromeBlurRenderer.TRANSPARENT, fallback.renderer)

        assertEquals(ChromeBlurRenderer.DRAW_BACKDROP, chromeSurfaceFallbackRenderer(effectiveBlur = true, backdropAvailable = true))
        assertEquals(ChromeBlurRenderer.TRANSPARENT, chromeSurfaceFallbackRenderer(effectiveBlur = true, backdropAvailable = false))
        assertEquals(ChromeBlurRenderer.TRANSPARENT, chromeSurfaceFallbackRenderer(effectiveBlur = false, backdropAvailable = true))
        assertEquals(ChromeBlurRenderer.TRANSPARENT, chromeSurfaceFallbackRenderer(effectiveBlur = false, backdropAvailable = false))
    }

    @Test
    fun eachBackdropConsumerStaysOutsideItsOwnCaptureSubtree() {
        val rootBoth = backdropOwnershipContract(effectiveBlur = true, effectiveLiquidGlass = true, subpageVisible = false)
        val rootBlurOnly = backdropOwnershipContract(effectiveBlur = true, effectiveLiquidGlass = false, subpageVisible = false)
        val rootLiquidOnly = backdropOwnershipContract(effectiveBlur = false, effectiveLiquidGlass = true, subpageVisible = false)
        val subpageBlur = backdropOwnershipContract(effectiveBlur = true, effectiveLiquidGlass = true, subpageVisible = true)
        val opaque = backdropOwnershipContract(effectiveBlur = false, effectiveLiquidGlass = false, subpageVisible = true)

        assertEquals(0, rootBoth.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, rootBlurOnly.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, rootLiquidOnly.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, subpageBlur.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, opaque.embeddedPageScaffoldBackdropAttachments)

        assertEquals(1, rootBoth.rootPagerLayerBackdropAttachments)
        assertEquals(3, rootBoth.rootPagerBottomBarConsumers)
        assertEquals(1, rootBoth.bottomBarLocalBackdropAttachments)
        assertEquals(1, rootBoth.bottomBarLocalBackdropConsumers)
        assertEquals(3, rootBoth.bottomBarDrawBackdropCallCount)
        assertEquals(0, rootBoth.rootPagerOtherConsumers)
        assertEquals(1, rootBoth.rootPageLocalBackdropAttachments)
        assertEquals(1, rootBoth.rootPageTopBarConsumers)
        assertEquals(0, rootBoth.privateSubpageBackdropAttachments)
        assertEquals(0, rootBoth.privateSubpageTopBarConsumers)
        assertFalse(rootBoth.consumerInsideOwnCaptureSubtree)

        assertEquals(1, rootBlurOnly.rootPagerLayerBackdropAttachments)
        assertEquals(0, rootBlurOnly.rootPagerBottomBarConsumers)
        assertEquals(0, rootBlurOnly.bottomBarLocalBackdropAttachments)
        assertEquals(0, rootBlurOnly.bottomBarLocalBackdropConsumers)
        assertEquals(0, rootBlurOnly.bottomBarDrawBackdropCallCount)
        assertEquals(1, rootBlurOnly.rootPageLocalBackdropAttachments)
        assertEquals(1, rootBlurOnly.rootPageTopBarConsumers)

        assertEquals(1, rootLiquidOnly.rootPagerLayerBackdropAttachments)
        assertEquals(3, rootLiquidOnly.rootPagerBottomBarConsumers)
        assertEquals(1, rootLiquidOnly.bottomBarLocalBackdropAttachments)
        assertEquals(0, rootLiquidOnly.rootPageLocalBackdropAttachments)
        assertEquals(0, rootLiquidOnly.rootPageTopBarConsumers)

        assertEquals(1, subpageBlur.rootPagerLayerBackdropAttachments)
        assertEquals(1, subpageBlur.privateSubpageBackdropAttachments)
        assertEquals(1, subpageBlur.privateSubpageTopBarConsumers)
        assertFalse(subpageBlur.consumerInsideOwnCaptureSubtree)

        assertEquals(0, opaque.rootPagerLayerBackdropAttachments)
        assertEquals(0, opaque.rootPageLocalBackdropAttachments)
        assertEquals(0, opaque.privateSubpageBackdropAttachments)
        assertEquals(0, opaque.bottomBarLocalBackdropAttachments)
        assertEquals(0, opaque.bottomBarDrawBackdropCallCount)
        assertEquals(0, opaque.drawBackdropConsumerCount)
    }
}
