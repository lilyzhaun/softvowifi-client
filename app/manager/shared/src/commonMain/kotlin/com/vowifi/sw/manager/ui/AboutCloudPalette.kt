// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// OS3 presets adapted from compose-miuix-ui/miuix a1ce560e32fce2a5b4b66cc5fc20c087ac0e9f18,
// example/shared/src/commonMain/kotlin/component/effect/BgEffectConfig.kt.
// Changes: keep only OS3, share the identical control points, omit OS2/configuration framework.
package com.vowifi.sw.manager.ui

internal class AboutCloudPalette(
    val colors: Array<FloatArray>,
    val period: Float,
    val light: Float,
    val saturation: Float,
    val pointOffset: Float,
) {
    companion object {
        val Points = floatArrayOf(.8f, .2f, 1f, .8f, .9f, 1f, .2f, .9f, 1f, .2f, .2f, 1f)
        fun forTheme(dark: Boolean, wide: Boolean): AboutCloudPalette = when {
            wide && dark -> PadDark
            wide -> PadLight
            dark -> PhoneDark
            else -> PhoneLight
        }

        private val PhoneLight = AboutCloudPalette(arrayOf(
            floatArrayOf(1f, .9f, .94f, 1f, 1f, .84f, .89f, 1f, .97f, .73f, .82f, 1f, .64f, .65f, .98f, 1f),
            floatArrayOf(.58f, .74f, 1f, 1f, 1f, .9f, .93f, 1f, .74f, .76f, 1f, 1f, .97f, .77f, .84f, 1f),
            floatArrayOf(.98f, .86f, .9f, 1f, .6f, .73f, .98f, 1f, .92f, .93f, 1f, 1f, .56f, .69f, 1f, 1f),
        ), 5f, .1f, .2f, .2f)

        private val PhoneDark = AboutCloudPalette(arrayOf(
            floatArrayOf(.2f, .06f, .88f, .4f, .3f, .14f, .55f, .5f, 0f, .64f, .96f, .5f, .11f, .16f, .83f, .4f),
            floatArrayOf(.07f, .15f, .79f, .5f, .62f, .21f, .67f, .5f, .06f, .25f, .84f, .5f, 0f, .2f, .78f, .5f),
            floatArrayOf(.58f, .3f, .74f, .4f, .27f, .18f, .6f, .5f, .66f, .26f, .62f, .5f, .12f, .16f, .7f, .6f),
        ), 8f, 0f, .17f, .4f)

        private val PadLight = AboutCloudPalette(arrayOf(
            floatArrayOf(.99f, .77f, .86f, 1f, .74f, .76f, 1f, 1f, .72f, .74f, 1f, 1f, .98f, .76f, .8f, 1f),
            floatArrayOf(.66f, .75f, 1f, 1f, 1f, .86f, .91f, 1f, .74f, .76f, 1f, 1f, .97f, .77f, .84f, 1f),
            floatArrayOf(.97f, .79f, .85f, 1f, .65f, .68f, .98f, 1f, .66f, .77f, 1f, 1f, .72f, .73f, .98f, 1f),
        ), 7f, .1f, .2f, .2f)

        private val PadDark = AboutCloudPalette(arrayOf(
            floatArrayOf(.66f, .26f, .62f, .4f, .06f, .25f, .84f, .5f, 0f, .64f, .96f, .5f, .14f, .18f, .55f, .5f),
            floatArrayOf(.07f, .15f, .79f, .5f, .11f, .16f, .83f, .5f, .06f, .25f, .84f, .5f, .66f, .26f, .62f, .5f),
            floatArrayOf(.58f, .3f, .74f, .5f, .11f, .16f, .83f, .5f, .66f, .26f, .62f, .5f, .27f, .18f, .6f, .6f),
        ), 7f, 0f, 0f, .2f)
    }
}
