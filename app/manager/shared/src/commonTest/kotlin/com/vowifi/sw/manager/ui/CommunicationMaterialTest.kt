package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CommunicationMaterialTest {
    @Test fun globalPreferencesSelectLiquidBlurOrOpaqueWithoutDependingOnFloatingTabs() {
        for (floating in listOf(false, true)) {
            for (blur in listOf(false, true)) {
                for (liquid in listOf(false, true)) {
                    val settings = AppearanceSettings(floating, blur, liquid)
                    val supported = appChromeSpec(settings, true)
                    assertEquals(when {
                        liquid -> CommunicationMaterial.LIQUID
                        blur -> CommunicationMaterial.BLUR
                        else -> CommunicationMaterial.OPAQUE
                    }, communicationMaterial(supported, true))
                    assertEquals(CommunicationMaterial.OPAQUE, communicationMaterial(supported, false))
                    assertEquals(CommunicationMaterial.OPAQUE, communicationMaterial(appChromeSpec(settings, false), true))
                }
            }
        }
    }
}
