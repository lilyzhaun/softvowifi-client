package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.data.isStorageAttachmentNotice
import com.vowifi.sw.manager.model.MessageUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

internal fun messageRootCompactNotice(state: MessageUiState): String? = when (state) {
    is MessageUiState.Available -> state.attachmentNotice?.takeUnless { isStorageAttachmentNotice(it) }
    else -> null
}

@Composable
internal fun MessageRootReadOnlyNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    CommunicationFootnote(text, modifier)
}
