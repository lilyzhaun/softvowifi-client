package com.vowifi.sw.manager.ui

/** The two long-only targets live in scroll containers; use the platform, not miuix's 150ms default. */
internal expect fun communicationTapIndicationDelayMillis(): Long
