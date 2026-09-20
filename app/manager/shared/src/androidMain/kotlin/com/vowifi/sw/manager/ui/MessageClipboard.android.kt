package com.vowifi.sw.manager.ui

import android.content.ClipData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun messageTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("message", text))
