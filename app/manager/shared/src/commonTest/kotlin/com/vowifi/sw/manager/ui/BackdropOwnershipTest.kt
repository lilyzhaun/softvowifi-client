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
        val normal = backdropOwnershipContract(effectiveBlur = true, subpageVisible = false, bottomMode = BottomChromeMode.NORMAL)
        val floating = backdropOwnershipContract(effectiveBlur = true, subpageVisible = false)
        val subpageBlur = backdropOwnershipContract(effectiveBlur = true, subpageVisible = true, bottomMode = BottomChromeMode.NORMAL)
        val opaque = backdropOwnershipContract(effectiveBlur = false, subpageVisible = true)

        assertEquals(0, normal.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, floating.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, subpageBlur.embeddedPageScaffoldBackdropAttachments)
        assertEquals(0, opaque.embeddedPageScaffoldBackdropAttachments)

        assertEquals(1, normal.rootPagerLayerBackdropAttachments)
        assertEquals(1, normal.rootPagerBottomBarConsumers)
        assertEquals(0, normal.bottomBarLocalBackdropAttachments)
        assertEquals(0, normal.bottomBarLocalBackdropConsumers)
        assertEquals(1, normal.bottomBarDrawBackdropCallCount)
        assertEquals(0, normal.rootPagerOtherConsumers)
        assertEquals(1, normal.rootPageLocalBackdropAttachments)
        assertEquals(1, normal.rootPageTopBarConsumers)
        assertEquals(0, normal.privateSubpageBackdropAttachments)
        assertEquals(0, normal.privateSubpageTopBarConsumers)
        assertFalse(normal.consumerInsideOwnCaptureSubtree)

        assertEquals(1, floating.rootPagerLayerBackdropAttachments)
        assertEquals(0, floating.rootPagerBottomBarConsumers)
        assertEquals(0, floating.bottomBarLocalBackdropAttachments)
        assertEquals(0, floating.bottomBarLocalBackdropConsumers)
        assertEquals(0, floating.bottomBarDrawBackdropCallCount)
        assertEquals(1, floating.rootPageLocalBackdropAttachments)
        assertEquals(1, floating.rootPageTopBarConsumers)

        assertEquals(1, subpageBlur.rootPagerLayerBackdropAttachments)
        assertEquals(1, subpageBlur.privateSubpageBackdropAttachments)
        assertEquals(1, subpageBlur.privateSubpageTopBarConsumers)
        assertFalse(subpageBlur.consumerInsideOwnCaptureSubtree)
        assertEquals(0, subpageBlur.rootPagerBottomBarConsumers)
        assertEquals(0, subpageBlur.bottomBarDrawBackdropCallCount)

        assertEquals(0, opaque.rootPagerLayerBackdropAttachments)
        assertEquals(0, opaque.rootPageLocalBackdropAttachments)
        assertEquals(0, opaque.privateSubpageBackdropAttachments)
        assertEquals(0, opaque.bottomBarLocalBackdropAttachments)
        assertEquals(0, opaque.bottomBarDrawBackdropCallCount)
        assertEquals(0, opaque.drawBackdropConsumerCount)
    }
}
