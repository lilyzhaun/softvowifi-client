package com.vowifi.sw.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.foundation.layout.heightIn
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MessageComposer(
    body: String,
    compose: MessageComposeSpec,
    imeOwner: MessageImeOwner,
    requestImeOwner: () -> Unit,
    pending: Boolean,
    canSend: Boolean,
    onBodyChange: (String) -> Unit,
    onSend: () -> Unit,
    outcome: OutgoingMessageStatus? = null,
) {
    val colors = LocalSoftVoWiFiColors.current
    val sceneBottomInset = LocalSubpageBottomContentInset.current
    val active = LocalAppRouteActive.current
    val editingEnabled = active && compose.editingEnabled && !pending
    val sendingEnabled = active && compose.sendingEnabled && !pending && canSend
    val editableNow by rememberUpdatedState(editingEnabled)
    val currentChange by rememberUpdatedState(onBodyChange)
    val estimate = remember(body) { estimateSmsSegments(body) }
    val duration = smsInnerMotionMillis()
    val inputShape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .applyMessageComposerImeOwner(imeOwner)
            .padding(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = messageComposerBottomPadding(sceneBottomInset),
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        outcome?.let {
            Text(messageSubmissionNotice(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (it == OutgoingMessageStatus.FAILED) colors.error else colors.textSecondary,
                fontSize = 13.sp, lineHeight = 18.sp, softWrap = true)
        }
        compose.notice?.let {
            Text(it, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommunicationInput(
                value = body,
                onChange = { if (editableNow) currentChange(it) },
                placeholder = "短信内容",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .animateContentSize(tween(duration))
                    .clearFocusOnInactiveRouteAndImeHide()
                    .onFocusChanged {
                        if (it.isFocused) requestImeOwner()
                    },
                enabled = editingEnabled,
                maxLines = 5,
                background = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            MessageSendAction(
                pending = pending,
                enabled = sendingEnabled,
                onClick = onSend,
            )
        }
        estimate?.takeIf { it.segments > 1 }?.let {
            Text("本机编码约 ${it.segments} 段 · 非运营商计费", color = colors.textSecondary,
                fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
        }
    }
}

private fun messageSubmissionNotice(status: OutgoingMessageStatus): String = when (status) {
    OutgoingMessageStatus.ACCEPTED -> "已受理，不代表接收设备已收到。"
    OutgoingMessageStatus.DELIVERY_REPORTED -> "已收到状态报告，不代表本机收到 MT 短信。"
    OutgoingMessageStatus.FAILED -> "发送未完成，草稿已保留。"
    OutgoingMessageStatus.UNKNOWN -> "发送结果未知，请先核对；不会自动重发。"
    OutgoingMessageStatus.QUEUED -> "排队中"
}

@Composable
private fun MessageSendAction(
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    val layout = messageComposerLayoutContract()
    val duration = smsInnerMotionMillis()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentEnabled by rememberUpdatedState(enabled && LocalAppRouteActive.current)
    val currentClick by rememberUpdatedState(onClick)
    val feedback = managerActionFeedback(ManagerAction.SendMessage, enabled)
    val acceptedClick = rememberManagerActionHandler(feedback) { if (currentEnabled) currentClick() }
    val handler = { if (currentEnabled) acceptedClick() }
    val alpha by animateFloatAsState(
        targetValue = if (enabled || pending) 1f else 0.56f,
        animationSpec = tween(duration),
    )
    val background by animateColorAsState(
        targetValue = if (enabled || pending) communicationColors().accent else colors.separator,
        animationSpec = tween(duration),
    )
    Box(Modifier.size(layout.sendTargetDp.dp)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interactionSource, indication = null, onClick = handler)
        .semantics {
            contentDescription = if (pending) "发送中" else "发送短信"
            stateDescription = if (pending) "发送中" else if (enabled) "可发送" else "不可发送"
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().phonePressMotion(enabled, interactionSource).testTag("sms-send-face")
            .graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(background)
            .background(if (pressed && enabled) Color.Black.copy(alpha = .08f) else Color.Transparent), contentAlignment = Alignment.Center) {
            Crossfade(targetState = pending, animationSpec = tween(smsInnerMotionMillis())) { busy ->
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).semantics { stateDescription = "发送中" }) else Icon(
                CommunicationSendUp,
                contentDescription = null,
                tint = if (enabled || pending) communicationColors().onAccent else colors.textDisabled,
            )
            }
        }
    }
}
