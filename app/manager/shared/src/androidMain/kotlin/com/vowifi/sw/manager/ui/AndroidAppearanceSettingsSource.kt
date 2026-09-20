package com.vowifi.sw.manager.ui

import android.content.Context

class AndroidAppearanceSettingsSource(context: Context) : AppearanceSettingsSource {
    private val delegate = PersistentAppearanceSettingsSource(
        AndroidAppearancePersistence(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    override val state = delegate.state

    override fun setFloatingNavigation(enabled: Boolean) = delegate.setFloatingNavigation(enabled)

    override fun setBlurRequested(enabled: Boolean) = delegate.setBlurRequested(enabled)

    override fun setLiquidGlassRequested(enabled: Boolean) = delegate.setLiquidGlassRequested(enabled)

    override fun setPredictiveBackRequested(enabled: Boolean) = delegate.setPredictiveBackRequested(enabled)

    override fun setHapticsEnabled(enabled: Boolean) = delegate.setHapticsEnabled(enabled)

    private companion object {
        const val PREFERENCES_NAME = "appearance"
    }
}

private class AndroidAppearancePersistence(
    private val preferences: android.content.SharedPreferences,
) : AppearancePersistence {
    override fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun writeBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
}
