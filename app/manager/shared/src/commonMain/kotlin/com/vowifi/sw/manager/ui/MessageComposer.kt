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
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    flight: MessageSendFlightState? = null,
    /** Thread composer floating over its timeline; the new-message form keeps its fixed composer. */
    floating: Boolean = false,
) {
    val colors = LocalSoftVoWiFiColors.current
    val sceneBottomInset = LocalSubpageBottomContentInset.current
    val active = LocalAppRouteActive.current
    val editingEnabled = active && compose.editingEnabled && !pending
    val sendingEnabled = active && compose.sendingEnabled && !pending && canSend
    val editableNow by rememberUpdatedState(editingEnabled)
    val currentChange by rememberUpdatedState(onBodyChange)
    val estimate = remember(body) { estimateSmsSegments(body) }
    val feedback = outcome to compose.notice
    val currentFeedback by rememberUpdatedState(feedback)
    val space = MotionTokens.SpringSpatialFast
    val textColor = if (editingEnabled) colors.textPrimary else colors.textDisabled
    if (flight != null) SideEffect { flight.textColor = textColor }
    // 2026-10-17: a floating composer owns no full-width surface; only its controls and notes do.
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
    ) {
        AnimatedContent(
            targetState = feedback,
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                (fadeIn(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)) togetherWith
                    fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))) using
                    SizeTransform(clip = false) { _, _ -> spring(dampingRatio = space.dampingRatio, stiffness = space.stiffness) }
            },
            label = "composer-feedback",
        ) { shown ->
            val (status, notice) = shown
            Column(
                modifier = (if (status != null || notice != null) Modifier.padding(bottom = if (floating) 6.dp else 4.dp) else Modifier)
                    .then(if (shown == currentFeedback) Modifier else Modifier.clearAndSetSemantics { }),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                status?.let {
                    MessageComposerNote(messageSubmissionNotice(it), floating, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (it == OutgoingMessageStatus.FAILED) colors.error else colors.textSecondary,
                        fontSize = 13, lineHeight = 18)
                }
                notice?.let {
                    MessageComposerNote(it, floating, color = colors.textSecondary, fontSize = 12, lineHeight = 16)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (floating) Arrangement.Start else Arrangement.spacedBy(8.dp),
            verticalAlignment = if (floating) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            CommunicationInput(
                value = body,
                onChange = { if (editableNow) currentChange(it) },
                placeholder = "短信内容",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .messageComposerHeightMotion()
                    .clearFocusOnInactiveRouteAndImeHide()
                    .onFocusChanged {
                        if (it.isFocused) requestImeOwner()
                    },
                enabled = editingEnabled,
                maxLines = 5,
                background = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                fontSize = MESSAGE_COMPOSER_FONT_SP,
                // A pending send's text may be flying out of this field; no hint is drawn underneath it.
                showPlaceholder = !(floating && pending),
                textAreaModifier = if (flight == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
                    // The send flight starts on these exact glyphs; page-local, refreshed without recomposition.
                    flight.textArea = flight.page?.takeIf { it.isAttached && coordinates.isAttached }
                        ?.localBoundingBoxOf(coordinates, clipBounds = false)
                },
            )
            // The floating send slot only exists for real text or a pending submission, so the field width follows it.
            if (!floating) MessageSendAction(pending = pending, enabled = sendingEnabled, onClick = onSend)
            else AnimatedVisibility(
                visible = body.isNotBlank() || pending,
                enter = expandHorizontally(messageComposerSlotSpring(), expandFrom = Alignment.End, clip = false) +
                    scaleIn(spring(dampingRatio = space.dampingRatio, stiffness = space.stiffness), MotionTokens.ScalePopFrom) +
                    fadeIn(tween(MotionTokens.DurFast, easing = MotionTokens.EaseEnter)),
                exit = shrinkHorizontally(messageComposerSlotSpring(), shrinkTowards = Alignment.End, clip = false) +
                    scaleOut(spring(dampingRatio = space.dampingRatio, stiffness = space.stiffness), MotionTokens.ScalePopFrom) +
                    fadeOut(tween(MotionTokens.DurMicro, easing = MotionTokens.EaseExit)),
                label = "composer-send-slot",
            ) {
                MessageSendAction(
                    pending = pending,
                    enabled = sendingEnabled,
                    onClick = onSend,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        estimate?.takeIf { it.segments > 1 }?.let {
            MessageComposerNote("本机编码约 ${it.segments} 段 · 非运营商计费", floating,
                Modifier.padding(top = if (floating) 6.dp else 4.dp), color = colors.textSecondary, fontSize = 12, lineHeight = 16)
        }
    }
}

internal const val MESSAGE_COMPOSER_FONT_SP = 17

private class ComposerHeightMotion {
    var target = -1
    var height: Animatable<Int, AnimationVector1D>? = null
}

/**
 * Multi-line growth keeps the approved SpringSpatialFast height spring (velocity carried on reversal).
 * Width is owned by the send slot's own animation: animating it here as well re-targeted every frame
 * and transiently rounded the height up, which nudged the whole floating timeline.
 */
@Composable
private fun Modifier.messageComposerHeightMotion(): Modifier {
    val scope = rememberCoroutineScope()
    val motion = remember { ComposerHeightMotion() }
    return clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val animation = motion.height ?: Animatable(placeable.height, Int.VectorConverter).also { motion.height = it }
        if (motion.target != placeable.height) {
            val first = motion.target < 0
            motion.target = placeable.height
            if (!first) scope.launch {
                animation.animateTo(placeable.height, spring(dampingRatio = MotionTokens.SpringSpatialFast.dampingRatio,
                    stiffness = MotionTokens.SpringSpatialFast.stiffness, visibilityThreshold = Int.VisibilityThreshold))
            }
        }
        val height = constraints.constrainHeight(animation.value)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
}

private fun messageComposerSlotSpring() = spring(
    dampingRatio = MotionTokens.SpringSpatialFast.dampingRatio,
    stiffness = MotionTokens.SpringSpatialFast.stiffness,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

/** Floating composer text sits over moving messages, so it carries the same communication material. */
@Composable
private fun MessageComposerNote(text: String, floating: Boolean, modifier: Modifier = Modifier, color: Color, fontSize: Int,
    lineHeight: Int) {
    Text(text, if (!floating) modifier else modifier
        .then(communicationSurfaceModifier(Modifier, MessageComposerNoteShape, communicationColors().field))
        .padding(horizontal = 12.dp, vertical = 6.dp),
        color = color, fontSize = fontSize.sp, lineHeight = lineHeight.sp, softWrap = true)
}

private val MessageComposerNoteShape = RoundedCornerShape(14.dp)

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
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val layout = messageComposerLayoutContract()
    val duration = smsInnerMotionMillis()
    val interactionSource = remember { MutableInteractionSource() }
    val actionable = enabled && !pending && LocalAppRouteActive.current
    val motion = rememberManagerPressMotion(interactionSource, actionable, ManagerPressStyle.Round)
    val currentEnabled by rememberUpdatedState(actionable)
    val currentClick by rememberUpdatedState(onClick)
    val feedback = managerActionFeedback(ManagerAction.SendMessage, actionable)
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
    Box(modifier.size(layout.sendTargetDp.dp)
        .managerPressInputGate(actionable)
        .clickable(enabled = actionable, role = Role.Button, interactionSource = interactionSource, indication = null, onClick = handler)
        .managerKeyboardFocusRing(interactionSource, actionable, CircleShape, communicationColors().onAccent)
        .semantics {
            contentDescription = if (pending) "发送中" else "发送短信"
            stateDescription = if (pending) "发送中" else if (enabled) "可发送" else "不可发送"
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().managerPressDrawing(motion).testTag("sms-send-face")
            .graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(background)
            .drawBehind { drawRect(Color.Black.copy(alpha = motion.tintAlpha.value)) }, contentAlignment = Alignment.Center) {
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
