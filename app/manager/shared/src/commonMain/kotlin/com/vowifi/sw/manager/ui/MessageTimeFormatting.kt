package com.vowifi.sw.manager.ui

internal expect fun messageDateSpec(epochMs: Long): MessageDateSpec

internal expect fun messageTimestampLabel(epochMs: Long): String
