package com.vowifi.sw.manager.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceSettings(
    val floatingNavigation: Boolean,
    val blurRequested: Boolean,
    val liquidGlassRequested: Boolean = false,
    val predictiveBackRequested: Boolean = false,
    val hapticsEnabled: Boolean = true,
) {
    companion object {
        val Default = AppearanceSettings(
            floatingNavigation = true,
            blurRequested = true,
            liquidGlassRequested = false,
            predictiveBackRequested = false,
            hapticsEnabled = true,
        )
    }
}

enum class AppearancePreferenceKey(val storageKey: String) {
    FLOATING_NAV("floating_nav"),
    BLUR_ENABLED("blur_enabled"),
    LIQUID_GLASS_ENABLED("liquid_glass_enabled"),
    PREDICTIVE_BACK_ENABLED("predictive_back_enabled"),
    HAPTICS_ENABLED("haptics_enabled"),
}

enum class AppearanceBlurSummary(val text: String) {
    ENABLED("已开启；模糊可用"),
    DISABLED("已关闭，使用不透明背景"),
    UNSUPPORTED_OPAQUE_FALLBACK("当前设备不支持模糊，使用不透明背景"),
}

fun predictiveBackSummary(enabled: Boolean): String =
    if (enabled) "返回时预览上一页" else "使用普通返回动画"

fun hapticsSummary(enabled: Boolean): String =
    if (enabled) "操作与滚动有触感" else "关闭触感，保留按压缩放"

interface AppearanceSettingsSource {
    val state: StateFlow<AppearanceSettings>

    fun setFloatingNavigation(enabled: Boolean)

    fun setBlurRequested(enabled: Boolean)

    fun setLiquidGlassRequested(enabled: Boolean)

    fun setPredictiveBackRequested(enabled: Boolean)

    fun setHapticsEnabled(enabled: Boolean)
}

interface AppearancePersistence {
    fun readBoolean(key: String, defaultValue: Boolean): Boolean

    fun writeBoolean(key: String, value: Boolean)
}

class PersistentAppearanceSettingsSource(
    private val persistence: AppearancePersistence,
) : AppearanceSettingsSource {
    private val mutableState = MutableStateFlow(
        AppearanceSettings(
            floatingNavigation = persistence.readBoolean(
                AppearancePreferenceKey.FLOATING_NAV.storageKey,
                AppearanceSettings.Default.floatingNavigation,
            ),
            blurRequested = persistence.readBoolean(
                AppearancePreferenceKey.BLUR_ENABLED.storageKey,
                AppearanceSettings.Default.blurRequested,
            ),
            liquidGlassRequested = persistence.readBoolean(
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey,
                AppearanceSettings.Default.liquidGlassRequested,
            ),
            predictiveBackRequested = persistence.readBoolean(
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey,
                AppearanceSettings.Default.predictiveBackRequested,
            ),
            hapticsEnabled = persistence.readBoolean(
                AppearancePreferenceKey.HAPTICS_ENABLED.storageKey,
                AppearanceSettings.Default.hapticsEnabled,
            ),
        ),
    )

    override val state: StateFlow<AppearanceSettings> = mutableState.asStateFlow()

    override fun setFloatingNavigation(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(floatingNavigation = enabled)
        persistence.writeBoolean(AppearancePreferenceKey.FLOATING_NAV.storageKey, enabled)
    }

    override fun setBlurRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(blurRequested = enabled)
        persistence.writeBoolean(AppearancePreferenceKey.BLUR_ENABLED.storageKey, enabled)
    }

    override fun setLiquidGlassRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(liquidGlassRequested = enabled)
        persistence.writeBoolean(AppearancePreferenceKey.LIQUID_GLASS_ENABLED.storageKey, enabled)
    }

    override fun setPredictiveBackRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(predictiveBackRequested = enabled)
        persistence.writeBoolean(AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED.storageKey, enabled)
    }

    override fun setHapticsEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(hapticsEnabled = enabled)
        persistence.writeBoolean(AppearancePreferenceKey.HAPTICS_ENABLED.storageKey, enabled)
    }
}

class InMemoryAppearanceSettingsSource(
    initial: AppearanceSettings = AppearanceSettings.Default,
) : AppearanceSettingsSource {
    private val mutableState = MutableStateFlow(initial)

    override val state: StateFlow<AppearanceSettings> = mutableState.asStateFlow()

    override fun setFloatingNavigation(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(floatingNavigation = enabled)
    }

    override fun setBlurRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(blurRequested = enabled)
    }

    override fun setLiquidGlassRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(liquidGlassRequested = enabled)
    }

    override fun setPredictiveBackRequested(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(predictiveBackRequested = enabled)
    }

    override fun setHapticsEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(hapticsEnabled = enabled)
    }
}

internal data class BlurSupportDecision(
    val requested: Boolean,
    val runtimeSupported: Boolean,
    val effective: Boolean,
)

internal fun blurSupportDecision(requested: Boolean, runtimeSupported: Boolean): BlurSupportDecision =
    BlurSupportDecision(requested, runtimeSupported, requested && runtimeSupported)

internal fun effectiveBlur(settings: AppearanceSettings, blurSupported: Boolean): Boolean =
    blurSupportDecision(settings.blurRequested, blurSupported).effective

internal fun appearanceBlurSummary(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): AppearanceBlurSummary = when {
    !settings.blurRequested -> AppearanceBlurSummary.DISABLED
    !blurSupported -> AppearanceBlurSummary.UNSUPPORTED_OPAQUE_FALLBACK
    else -> AppearanceBlurSummary.ENABLED
}
