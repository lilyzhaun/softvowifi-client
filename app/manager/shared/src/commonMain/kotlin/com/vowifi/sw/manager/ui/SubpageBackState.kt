package com.vowifi.sw.manager.ui

import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventState

internal fun <Info : NavigationEventInfo> subpageBackRenderState(
    predictiveEnabled: Boolean,
    gesture: NavigationEventState<Info>,
    resting: NavigationEventState<Info>,
): NavigationEventState<Info> = if (predictiveEnabled) gesture else resting
