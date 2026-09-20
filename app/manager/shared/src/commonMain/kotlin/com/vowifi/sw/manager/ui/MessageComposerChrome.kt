package com.vowifi.sw.manager.ui

internal data class MessageComposerShadowSpec(
    val radiusDp: Int,
    val lightAlpha: Float,
    val darkAlpha: Float,
)

internal fun messageComposerShadowSpec(): MessageComposerShadowSpec = MessageComposerShadowSpec(
    radiusDp = 10,
    lightAlpha = 0.1f,
    darkAlpha = 0.2f,
)
