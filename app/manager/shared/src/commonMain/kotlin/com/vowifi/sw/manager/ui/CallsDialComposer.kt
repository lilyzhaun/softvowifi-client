package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Row

@Composable
internal fun CallsDialComposer(
    number: String,
    inputEnabled: Boolean,
    busy: Boolean,
    showMicrophonePermissionAction: Boolean,
    onNumberChange: (String) -> Unit,
    onRequestMicrophonePermission: () -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CommunicationInput(
                value = number,
                onChange = onNumberChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = "对方号码",
                enabled = inputEnabled,
                singleLine = true,
                fontSize = 28,
            )
            if (showMicrophonePermissionAction) {
                CallsActionButton(MICROPHONE_PERMISSION_ACTION, !busy, onRequestMicrophonePermission)
            }
    }
}

@Composable
internal fun CallsActionButton(label: String, enabled: Boolean, onClick: () -> Unit, tone: CallActionTone = CallActionTone.PRIMARY, icon: ImageVector? = null) {
    PhoneButton(label, onClick, enabled = enabled, icon = icon, wide = true,
        selected = tone == CallActionTone.PRIMARY, destructive = tone == CallActionTone.DESTRUCTIVE)
}

internal enum class CallActionTone { PRIMARY, SECONDARY, DESTRUCTIVE }
