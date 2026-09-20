package com.vowifi.sw.manager.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal expect fun messageTextClipEntry(text: String): ClipEntry
