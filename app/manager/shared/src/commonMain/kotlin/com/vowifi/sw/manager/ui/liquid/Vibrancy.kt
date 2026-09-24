// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU liquid/Vibrancy.kt @ befbcd388bb6c10befd3645311ad08e556d362ac
// and InstallerX-Revived liquid/Vibrancy.kt @ 11f15e4740c21bbed3e3156ceeab643683ed1fcb.
package com.vowifi.sw.manager.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

internal fun BackdropEffectScope.vibrancy() {
    colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)
}
