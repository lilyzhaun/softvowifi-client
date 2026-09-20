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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
) {
    val colors = LocalSoftVoWiFiColors.current
    val sceneBottomInset = LocalSubpageBottomContentInset.current
    val editingEnabled = compose.editingEnabled && !pending
    val sendingEnabled = compose.sendingEnabled && !pending && canSend
    val isInDark = isSystemInDarkTheme()
    val shadowSpec = messageComposerShadowSpec()
    val estimate = remember(body) { estimateSmsSegments(body) }
    val duration = smsInnerMotionMillis()
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
        compose.notice?.let {
            Text(it, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = body,
                onValueChange = onBodyChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .animateContentSize(tween(duration))
                    .dropShadow(
                        shape = RoundedCornerShape(24.dp),
                        shadow = Shadow(
                            radius = shadowSpec.radiusDp.dp,
                            color = Color.Black,
                            alpha = if (isInDark) shadowSpec.darkAlpha else shadowSpec.lightAlpha,
                        ),
                    )
                    .clearFocusOnInactiveRouteAndImeHide()
                    .onFocusChanged {
                        if (it.isFocused) requestImeOwner()
                    },
                insideMargin = DpSize(16.dp, 10.dp),
                cornerRadius = 24.dp,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = colors.surface),
                label = "短信内容",
                useLabelAsPlaceholder = true,
                enabled = editingEnabled,
                textStyle = MiuixTheme.textStyles.main.copy(fontSize = 16.sp, lineHeight = 22.sp),
                minLines = 1,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            MessageSendAction(
                pending = pending,
                enabled = sendingEnabled,
                onClick = onSend,
            )
        }
        estimate?.let {
            Text("本机编码约 ${it.segments} 段 · 非运营商计费", color = colors.textSecondary,
                fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
        }
    }
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
    val feedback = managerActionFeedback(ManagerAction.SendMessage, enabled)
    val handler = rememberManagerActionHandler(feedback, onClick)
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.56f,
        animationSpec = tween(duration),
    )
    val background by animateColorAsState(
        targetValue = if (enabled) colors.interactive else colors.separator,
        animationSpec = tween(duration),
    )
    Surface(
        onClick = handler,
        enabled = enabled,
        modifier = Modifier.size(layout.sendTargetDp.dp).semantics { stateDescription = if (pending) "发送中" else if (enabled) "可发送" else "不可发送" }.managerPressScale(feedback, interactionSource).graphicsLayer {
            this.alpha = alpha
        },
        shape = CircleShape,
        color = background,
        contentColor = if (enabled) colors.onCommunicationAccent else colors.textDisabled,
        interactionSource = interactionSource,
        indication = null,
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Crossfade(targetState = pending, animationSpec = tween(smsInnerMotionMillis())) { busy ->
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).semantics { stateDescription = "发送中" }) else Icon(
                MiuixIcons.Send,
                contentDescription = if (pending) "发送中" else "发送短信",
                tint = if (enabled) colors.onCommunicationAccent else colors.textDisabled,
            )
            }
        }
    }
}
