package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceSettingsTest {
    @Test fun liquidGlassRequiresEffectiveBlurButPreservesTheUsersStoredRequest() {
        val source = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(liquidGlassRequested = true))
        for (supported in listOf(false, true)) {
            source.setBlurRequested(false)
            val settings = source.state.value
            assertTrue(settings.liquidGlassRequested)
            assertFalse(effectiveLiquidGlass(settings, supported))
            assertFalse(appChromeSpec(settings, supported).communicationLiquidGlass)
            val row = appearanceSpecToTogglePreference(appearancePreferenceSpecs(settings, supported)
                .single { it.key == AppearancePreferenceKey.LIQUID_GLASS_ENABLED })
            assertTrue(row.checked)
            assertFalse(row.actionable)
            assertEquals(if (supported) "需先开启模糊效果" else "当前设备不支持模糊，使用不透明背景", row.visibleSummary)
        }
        source.setBlurRequested(true)
        assertTrue(source.state.value.liquidGlassRequested)
        assertTrue(effectiveLiquidGlass(source.state.value, true))
        assertTrue(appChromeSpec(source.state.value, true).communicationLiquidGlass)
        assertEquals("通信操作使用液态折射", appearanceLiquidGlassSummary(
            source.state.value.copy(floatingNavigation = false), true).text)
    }

    @Test fun liquidGlassAvailabilityUsesTheSummaryForEachHardwareAndBlurStateWithoutChangingPreferences() {
        for (supported in listOf(false, true)) for (blur in listOf(false, true)) for (liquid in listOf(false, true)) {
            val settings = AppearanceSettings.Default.copy(blurRequested = blur, liquidGlassRequested = liquid)
            val row = appearanceSpecToTogglePreference(appearancePreferenceSpecs(settings, supported)
                .single { it.key == AppearancePreferenceKey.LIQUID_GLASS_ENABLED })
            val expected = when {
                !supported -> "当前设备不支持模糊，使用不透明背景"
                !blur -> "需先开启模糊效果"
                else -> appearanceLiquidGlassSummary(settings, supported).text
            }
            assertEquals(expected, row.visibleSummary)
            assertEquals(supported && blur, row.actionable)
            assertEquals(liquid, row.checked, "禁用不清除原请求偏好")
        }
    }

    @Test
    fun screenshotDefaultsDoNotWriteOrOverrideExplicitLiquidAndBackPreferences() {
        val values = mutableMapOf<String, Boolean>()
        val persistence = RecordingAppearancePersistence(values)
        val source = PersistentAppearanceSettingsSource(persistence)
        assertFalse(source.state.value.predictiveBackRequested, "截图要求缺键默认关闭预测返回")
        assertTrue(source.state.value.liquidGlassRequested, "截图要求缺键默认开启液态玻璃")
        assertTrue(values.isEmpty(), "读默认值不能伪装用户显式设置")
        assertTrue(persistence.writes.isEmpty())

        source.setPredictiveBackRequested(true)
        source.setLiquidGlassRequested(false)
        assertTrue(source.state.value.predictiveBackRequested)
        assertEquals(true, values[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey])
        assertEquals(false, values[AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey])
        val restoredPersistence = RecordingAppearancePersistence(values)
        val restored = PersistentAppearanceSettingsSource(restoredPersistence)
        assertTrue(restored.state.value.predictiveBackRequested, "默认更改不能覆盖用户已保存的开启选择")
        assertFalse(restored.state.value.liquidGlassRequested, "默认更改不能覆盖用户已保存的关闭选择")
        assertTrue(restoredPersistence.writes.isEmpty())

        restored.setPredictiveBackRequested(false)
        assertFalse(restored.state.value.predictiveBackRequested)
        assertEquals(listOf(AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey to false), restoredPersistence.writes)
        assertEquals(false, values[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey])
    }

    @Test
    fun defaultsMatchTheRequestedAppearanceScreenshot() {
        val settings = AppearanceSettings.Default

        assertTrue(settings.floatingNavigation)
        assertTrue(settings.blurRequested)
        assertTrue(settings.liquidGlassRequested)
        assertFalse(settings.predictiveBackRequested)
        assertTrue(settings.hapticsEnabled)
        assertEquals(settings, AppearanceSettings(floatingNavigation = true, blurRequested = true))
    }

    @Test
    fun effectiveLiquidGlassRequiresRequestAndFloatingAndRuntimeSupport() {
        assertTrue(
            effectiveLiquidGlass(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = true,
                    liquidGlassRequested = true,
                    predictiveBackRequested = false,
                    hapticsEnabled = true,
                ),
                blurSupported = true,
            ),
        )
        assertFalse(
            effectiveLiquidGlass(
                AppearanceSettings(
                    floatingNavigation = false,
                    blurRequested = true,
                    liquidGlassRequested = true,
                    predictiveBackRequested = false,
                    hapticsEnabled = true,
                ),
                blurSupported = true,
            ),
        )
        assertFalse(
            effectiveLiquidGlass(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = true,
                    liquidGlassRequested = true,
                    predictiveBackRequested = false,
                    hapticsEnabled = true,
                ),
                blurSupported = false,
            ),
        )
        assertFalse(
            effectiveLiquidGlass(
                AppearanceSettings(
                    floatingNavigation = true,
                    blurRequested = true,
                    liquidGlassRequested = false,
                    predictiveBackRequested = false,
                    hapticsEnabled = true,
                ),
                blurSupported = true,
            ),
        )
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
    fun liquidGlassSummariesAreHonest() {
        assertEquals(
            AppearanceLiquidGlassSummary.UNSUPPORTED_FALLBACK,
            appearanceLiquidGlassSummary(AppearanceSettings.Default.copy(liquidGlassRequested = true), blurSupported = false),
        )
        assertEquals(
            AppearanceLiquidGlassSummary.DISABLED,
            appearanceLiquidGlassSummary(AppearanceSettings.Default.copy(liquidGlassRequested = false), blurSupported = true),
        )
        assertEquals(
            AppearanceLiquidGlassSummary.ENABLED,
            appearanceLiquidGlassSummary(AppearanceSettings.Default.copy(liquidGlassRequested = true), blurSupported = true),
        )
        assertEquals("通信操作与悬浮底栏使用液态折射", AppearanceLiquidGlassSummary.ENABLED.text)
        assertEquals("通信操作使用普通模糊或不透明背景", AppearanceLiquidGlassSummary.DISABLED.text)
        assertEquals("当前设备不支持模糊，使用不透明背景", AppearanceLiquidGlassSummary.UNSUPPORTED_FALLBACK.text)
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
