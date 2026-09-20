package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceSettingsTest {
    @Test
    fun defaultsEnableFloatingNavigationAndRequestedBlurAndHapticsAndDisablesLiquidAndPredictive() {
        val settings = AppearanceSettings.Default

        assertTrue(settings.floatingNavigation)
        assertTrue(settings.blurRequested)
        assertFalse(settings.liquidGlassRequested)
        assertFalse(settings.predictiveBackRequested)
        assertTrue(settings.hapticsEnabled)
    }

    @Test
    fun legacyLiquidPreferenceCannotChangeChromeInEitherNavigationMode() {
        for (floating in listOf(false, true)) {
            for (supported in listOf(false, true)) {
                val settings = AppearanceSettings.Default.copy(floatingNavigation = floating)
                assertEquals(
                    appChromeSpec(settings.copy(liquidGlassRequested = false), supported),
                    appChromeSpec(settings.copy(liquidGlassRequested = true), supported),
                )
            }
        }
    }

    @Test
    fun effectiveBlurDoesNotDependOnFloatingNavOrLiquidGlass() {
        assertTrue(
            effectiveBlur(
                AppearanceSettings(
                    floatingNavigation = false,
                    blurRequested = true,
                    liquidGlassRequested = false,
                    predictiveBackRequested = false,
                    hapticsEnabled = false,
                ),
                blurSupported = true,
            ),
        )
        assertFalse(
            effectiveBlur(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = false,
                    liquidGlassRequested = true,
                    predictiveBackRequested = true,
                    hapticsEnabled = true,
                ),
                blurSupported = true,
            ),
        )
        assertFalse(
            effectiveBlur(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = true,
                    liquidGlassRequested = true,
                    predictiveBackRequested = true,
                    hapticsEnabled = true,
                ),
                blurSupported = false,
            ),
        )
    }

    @Test
    fun blurDecisionRetainsRequestedRuntimeAndEffectiveSupport() {
        assertEquals(
            BlurSupportDecision(requested = true, runtimeSupported = true, effective = true),
            blurSupportDecision(requested = true, runtimeSupported = true),
        )
        assertEquals(
            BlurSupportDecision(requested = true, runtimeSupported = false, effective = false),
            blurSupportDecision(requested = true, runtimeSupported = false),
        )
        assertEquals(
            BlurSupportDecision(requested = false, runtimeSupported = true, effective = false),
            blurSupportDecision(requested = false, runtimeSupported = true),
        )
    }

    @Test
    fun unsupportedSummaryIsHonestAndOpaqueFallbackIsRequired() {
        assertEquals(
            AppearanceBlurSummary.UNSUPPORTED_OPAQUE_FALLBACK,
            appearanceBlurSummary(AppearanceSettings.Default, blurSupported = false),
        )
        assertEquals(
            AppearanceBlurSummary.DISABLED,
            appearanceBlurSummary(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = false,
                    liquidGlassRequested = false,
                    predictiveBackRequested = false,
                    hapticsEnabled = true,
                ),
                blurSupported = true,
            ),
        )
        assertEquals(
            AppearanceBlurSummary.ENABLED,
            appearanceBlurSummary(AppearanceSettings.Default, blurSupported = true),
        )
        assertEquals("已开启；模糊可用", AppearanceBlurSummary.ENABLED.text)
        assertFalse(AppearanceBlurSummary.ENABLED.text.endsWith("效果"))
    }

    @Test
    fun legacyLiquidStorageRemainsReadableWithoutWritingOrActivatingAnEffect() {
        val persistence = RecordingAppearancePersistence(mutableMapOf("liquid_glass_enabled" to true))
        val source = PersistentAppearanceSettingsSource(persistence)
        assertTrue(source.state.value.liquidGlassRequested)
        assertTrue(persistence.writes.isEmpty())
        val spec = appearancePreferenceSpecs(source.state.value, blurSupported = true)
            .single { it.key == AppearancePreferenceKey.LIQUID_GLASS_ENABLED }
        assertEquals("已改用原生静态导航，不再提供液态折射", spec.summary)
        assertFalse(spec.checked)
        assertFalse(appearanceSpecToTogglePreference(spec).actionable)
    }

    @Test
    fun predictiveBackSummariesDistinguishModesAndHapticsRemainDescriptive() {
        val enabledSummary = predictiveBackSummary(enabled = true)
        val disabledSummary = predictiveBackSummary(enabled = false)
        assertTrue(enabledSummary.isNotBlank())
        assertTrue(disabledSummary.isNotBlank())
        assertFalse(enabledSummary == disabledSummary)
        assertTrue(hapticsSummary(enabled = true).isNotBlank())
        assertFalse(hapticsSummary(enabled = true) == hapticsSummary(enabled = false))
        assertEquals("关闭触感，保留按压缩放", hapticsSummary(enabled = false))
    }

    @Test
    fun androidBlurFollowsRuntimeShaderCapability() {
        assertTrue(androidBlurSupported(runtimeShaderSupported = true))
        assertFalse(androidBlurSupported(runtimeShaderSupported = false))
    }

    @Test
    fun storeLoadsPersistsAndPublishesEachPreferenceImmediately() {
        val values = mutableMapOf(
            AppearancePreferenceKey.FLOATING_NAV.storageKey to false,
            AppearancePreferenceKey.BLUR_ENABLED.storageKey to true,
            AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey to true,
            AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey to true,
            AppearancePreferenceKey.HAPTICS_ENABLED.storageKey to false,
        )
        val persistence = RecordingAppearancePersistence(values)
        val source = PersistentAppearanceSettingsSource(persistence)

        assertEquals(
            AppearanceSettings(
                floatingNavigation = false,
                blurRequested = true,
                liquidGlassRequested = true,
                predictiveBackRequested = true,
                hapticsEnabled = false,
            ),
            source.state.value,
        )

        source.setFloatingNavigation(true)
        assertEquals(true, source.state.value.floatingNavigation)
        assertEquals(true, values[AppearancePreferenceKey.FLOATING_NAV.storageKey])

        source.setBlurRequested(false)
        assertEquals(false, source.state.value.blurRequested)
        assertEquals(false, values[AppearancePreferenceKey.BLUR_ENABLED.storageKey])

        source.setLiquidGlassRequested(false)
        assertEquals(false, source.state.value.liquidGlassRequested)
        assertEquals(false, values[AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey])

        source.setPredictiveBackRequested(false)
        assertEquals(false, source.state.value.predictiveBackRequested)
        assertEquals(false, values[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey])

        source.setHapticsEnabled(true)
        assertEquals(true, source.state.value.hapticsEnabled)
        assertEquals(true, values[AppearancePreferenceKey.HAPTICS_ENABLED.storageKey])

        assertEquals(
            listOf(
                AppearancePreferenceKey.FLOATING_NAV.storageKey to true,
                AppearancePreferenceKey.BLUR_ENABLED.storageKey to false,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey to false,
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey to false,
                AppearancePreferenceKey.HAPTICS_ENABLED.storageKey to true,
            ),
            persistence.writes,
        )
    }
}

private class RecordingAppearancePersistence(
    private val values: MutableMap<String, Boolean>,
) : AppearancePersistence {
    val writes = mutableListOf<Pair<String, Boolean>>()

    override fun readBoolean(key: String, defaultValue: Boolean): Boolean = values[key] ?: defaultValue

    override fun writeBoolean(key: String, value: Boolean) {
        values[key] = value
        writes += key to value
    }
}
