package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceSettingsTest {
    @Test
    fun predictiveBackDefaultsOnWithoutWritingButRetainsAnExplicitPersistedOptOut() {
        val values = mutableMapOf<String, Boolean>()
        val persistence = RecordingAppearancePersistence(values)
        val source = PersistentAppearanceSettingsSource(persistence)
        assertTrue(source.state.value.predictiveBackRequested, "D4：缺键默认启用预测返回")
        assertTrue(values.isEmpty(), "读默认值不能伪装用户显式设置")
        assertTrue(persistence.writes.isEmpty())

        source.setPredictiveBackRequested(false)
        assertFalse(source.state.value.predictiveBackRequested)
        assertEquals(false, values[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey])
        val restoredPersistence = RecordingAppearancePersistence(values)
        val restored = PersistentAppearanceSettingsSource(restoredPersistence)
        assertFalse(restored.state.value.predictiveBackRequested, "默认更改不能覆盖用户已经保存的关闭选择")
        assertTrue(restoredPersistence.writes.isEmpty())

        restored.setPredictiveBackRequested(true)
        assertTrue(restored.state.value.predictiveBackRequested)
        assertEquals(listOf(AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey to true), restoredPersistence.writes)
        assertEquals(true, values[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey])
    }

    @Test
    fun defaultsEnableFloatingNavigationAndRequestedBlurAndHapticsAndPredictiveButDisableLiquid() {
        val settings = AppearanceSettings.Default

        assertTrue(settings.floatingNavigation)
        assertTrue(settings.blurRequested)
        assertFalse(settings.liquidGlassRequested)
        // D4：预测返回缺省开启；显式关闭及持久化写次数由各自原合同继续守护。
        assertTrue(settings.predictiveBackRequested)
        assertTrue(settings.hapticsEnabled)
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
