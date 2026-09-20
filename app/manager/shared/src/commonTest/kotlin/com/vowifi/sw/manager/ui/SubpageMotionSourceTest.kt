package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubpageMotionSourceTest {
    @Test
    fun subpageMotionFollowsKernelSuMiuixNavDisplayDefaultContract() {
        val motion = subpageMotionContract()

        assertEquals(SubpageMotionSource.KERNELSU_MIUUX_NAVDISPLAY_DEFAULT, motion.source)
        assertFalse(motion.customTransitionOverrides)
        assertEquals("0.9.3", motion.miuixNavigation3Version)
        assertEquals(500, motion.programmaticDurationMillis)
        assertEquals(550, motion.predictiveDurationMillis)
        assertFalse(motion.usesScale)
        assertFalse(motion.usesFade)
    }
}
