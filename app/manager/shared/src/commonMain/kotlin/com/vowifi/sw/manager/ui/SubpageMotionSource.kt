package com.vowifi.sw.manager.ui

// Provenance: KernelSU befbcd388bb6c10befd3645311ad08e556d362ac MainActivity uses Navigation3
// NavDisplay with no transition overrides; motion is miuix-navigation3-ui 0.9.3 default.
// Mechanism/provenance only; no GPL code copied. Upstream miuix implementation Apache-2.0.

internal enum class SubpageMotionSource {
    KERNELSU_MIUUX_NAVDISPLAY_DEFAULT,
}

internal data class SubpageMotionContract(
    val source: SubpageMotionSource,
    val customTransitionOverrides: Boolean,
    val miuixNavigation3Version: String,
) {
    val programmaticDurationMillis: Int get() = 500
    val predictiveDurationMillis: Int get() = 550
    val usesScale: Boolean get() = false
    val usesFade: Boolean get() = false
}

internal fun subpageMotionContract(): SubpageMotionContract = SubpageMotionContract(
    source = SubpageMotionSource.KERNELSU_MIUUX_NAVDISPLAY_DEFAULT,
    customTransitionOverrides = false,
    miuixNavigation3Version = "0.9.3",
)
