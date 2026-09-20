package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

@Composable
internal fun smsInnerMotionMillis(): Int {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor
    return messageMotionContract(reducedMotion = scale == 0f).durationMillis
}
