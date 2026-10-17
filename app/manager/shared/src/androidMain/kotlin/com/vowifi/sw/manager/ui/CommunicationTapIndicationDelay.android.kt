package com.vowifi.sw.manager.ui

import android.view.ViewConfiguration

internal actual fun communicationTapIndicationDelayMillis(): Long = ViewConfiguration.getTapTimeout().toLong()
