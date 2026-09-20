package com.vowifi.sw.manager.ui

internal data class AppWindowPolicy(
    val edgeToEdge: Boolean,
    val transparentStatusBar: Boolean,
    val transparentNavigationBar: Boolean,
    val disableNavigationBarContrastFromApi: Int,
    val preserveAdjustResize: Boolean,
)

internal fun appWindowPolicy(): AppWindowPolicy = AppWindowPolicy(
    edgeToEdge = true,
    transparentStatusBar = true,
    transparentNavigationBar = true,
    disableNavigationBarContrastFromApi = 29,
    preserveAdjustResize = true,
)
