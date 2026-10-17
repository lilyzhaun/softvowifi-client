package com.vowifi.sw.manager.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.pressable

@Composable
internal fun MessageDateSeparator(label: String) {
    val colors = LocalSoftVoWiFiColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, Modifier.background(communicationColors().list.copy(alpha = .85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
    }
}

@Composable
internal fun IncomingMessageBubble(item: MessageThreadItem.IncomingBubble, modifier: Modifier = Modifier, enter: Boolean = false,
    rise: Boolean = false) {
    MessageBubble(
        presentation = MessageBubblePresentation(
            recordId = item.recordId,
            body = item.body,
            direction = MessageBubbleDirection.INCOMING,
            metadataLines = listOf(
                MessageMetadataLine(MessageMetadataRole.TIMESTAMP, messageTimestampLabel(item.timestamp.epochMs)),
            ),
        ),
        modifier = modifier.messageIncomingEntrance(item.recordId, enter, rise),
    )
}

@Composable
internal fun OutgoingMessageBubble(item: MessageThreadItem.OutgoingBubble, modifier: Modifier = Modifier, enter: Boolean = false) {
    MessageBubble(
        presentation = outgoingBubblePresentation(item),
        modifier = modifier.messageOutgoingEntrance(item.recordId, enter),
    )
}

private fun outgoingBubblePresentation(item: MessageThreadItem.OutgoingBubble) = MessageBubblePresentation(
    recordId = item.recordId,
    body = item.body,
    direction = MessageBubbleDirection.OUTGOING,
    metadataLines = messageOutgoingMetadataLines(
        status = item.status,
        note = item.statusNote,
        timestamp = messageTimestampLabel(item.timestamp.epochMs),
    ),
    statusFailed = item.statusFailed,
)

/** Draw-only copy of the real outgoing content for the send flight; semantics stay on the real bubble. */
@Composable
internal fun MessageBubbleFlightContent(item: MessageThreadItem.OutgoingBubble) {
    MessageBubbleContent(outgoingBubblePresentation(item), messageBubbleLayoutContract(MessageBubbleDirection.OUTGOING),
        LocalSoftVoWiFiColors.current.textPrimary)
}

internal val MESSAGE_BUBBLE_CONTENT_PADDING_X = 12.dp
internal val MESSAGE_BUBBLE_CONTENT_PADDING_Y = 8.dp

private data class MessageBubblePresentation(
    val recordId: String,
    val body: String,
    val direction: MessageBubbleDirection,
    val metadataLines: List<MessageMetadataLine>,
    val statusFailed: Boolean = false,
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun MessageBubble(
    presentation: MessageBubblePresentation,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val density = LocalDensity.current
    val clipboard = LocalClipboard.current
    val openActions = LocalSmsBubbleAction.current
    val scope = rememberCoroutineScope()
    val layout = messageBubbleLayoutContract(presentation.direction)
    val maxFraction = messageBubbleMaximumWidth(density.fontScale)
    val copyableText = messageCopyableBody(presentation.body)
    val interaction = remember(copyableText, presentation.recordId) { MutableInteractionSource() }
    val copied = LocalCommunicationCopyFeedback.current
    val active = LocalAppRouteActive.current
    val activeNow by rememberUpdatedState(active)
    val menu = LocalSmsBubbleMenu.current
    val selected = active && menu?.selectedId == presentation.recordId
    val actionable = copyableText != null && active
    val feedback = managerActionFeedback(ManagerAction.LongPress, actionable)
    val contextTarget = rememberCommunicationContextTarget()
    val mounted = remember(presentation.recordId) { mutableStateOf(true) }
    DisposableEffect(mounted) { onDispose { mounted.value = false } }
    val copyMessage by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !actionable || copyableText == null || (menu?.selectedId != null && menu.selectedId != presentation.recordId)) false else {
            scope.launch {
                if (!mounted.value || !activeNow) return@launch
                clipboard.setClipEntry(messageTextClipEntry(copyableText)); if (mounted.value && activeNow) copied(CommunicationCopyFeedback.MESSAGE)
            }
            menu?.onDismiss?.invoke(); true
        }
    })
    val bindMessage by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !actionable || menu == null || (menu.selectedId != presentation.recordId && (menu.selectedId != null || openActions == null))) false
        else { if (menu.selectedId != presentation.recordId) openActions?.invoke(presentation.recordId); true }
    })
    val showDetails by rememberUpdatedState<() -> Boolean>({
        if (!bindMessage()) false else { menu?.onDetails?.invoke(); true }
    })
    val reeditMessage by rememberUpdatedState<() -> Boolean>({
        if (menu?.editingEnabled != true || presentation.recordId !in menu.reeditableIds || !bindMessage()) false
        else { menu.onReedit(); true }
    })
    val rootActionsAllowed by rememberUpdatedState(actionable && menu?.selectedId == null && menu?.actionsBlocked != true)

    val horizontalAlignment = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> Alignment.Start
        MessageBubbleDirection.OUTGOING -> Alignment.End
    }
    val boxAlignment = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> Alignment.CenterStart
        MessageBubbleDirection.OUTGOING -> Alignment.CenterEnd
    }
    val baseSurfaceColor = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> communicationColors().field
        MessageBubbleDirection.OUTGOING -> communicationColors().outgoing
    }
    val surfaceColor = baseSurfaceColor
    val contentColor = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> colors.textPrimary
        MessageBubbleDirection.OUTGOING -> colors.textPrimary
    }

    // Only an outgoing bubble can be a send-flight landing; its entrance layer owns the hiding.
    val flight = LocalMessageSendFlight.current.takeIf { presentation.direction == MessageBubbleDirection.OUTGOING }
    DisposableEffect(flight, presentation.recordId) { onDispose { flight?.releaseTarget(presentation.recordId) } }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val computedMax = minOf(maxWidth * maxFraction, 600.dp)
        Column(
            modifier = Modifier
                .align(boxAlignment)
                .widthIn(max = computedMax),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val longPressModifier = if (copyableText != null && active) {
                val copyBody: () -> Unit = {
                    if (openActions != null) openActions(presentation.recordId) else { copyMessage() }
                }
                val acceptedLongPress = rememberManagerActionHandler(feedback, copyBody)
                val longPress = { if (activeNow) acceptedLongPress() }
                val currentLongPress by rememberUpdatedState(longPress)
                Modifier
                    .semantics {
                        this.selected = selected
                        customActions = if (!rootActionsAllowed || contextTarget.presented) emptyList() else buildList {
                            add(CustomAccessibilityAction("复制正文") { mounted.value && rootActionsAllowed && !contextTarget.presented && copyMessage() })
                            if (menu != null && openActions != null) {
                                add(CustomAccessibilityAction("消息详情") { mounted.value && rootActionsAllowed && !contextTarget.presented && showDetails() })
                                if (menu.editingEnabled && presentation.recordId in menu.reeditableIds)
                                    add(CustomAccessibilityAction("重新编辑") { mounted.value && rootActionsAllowed && !contextTarget.presented && reeditMessage() })
                            }
                        }
                        onLongClick(label = if (openActions == null) "复制短信" else "消息操作") {
                            if (activeNow) { longPress(); true } else false
                        }
                    }
                    .pointerInput(copyableText, presentation.recordId, active) {
                        detectTapGestures(onLongPress = { currentLongPress() })
                    }
            } else {
                Modifier
            }
            val surfaceModifier = Modifier
                .focusable(actionable, interaction)
                .managerKeyboardFocusRing(interaction, actionable, layout.shape, communicationColors().accent)
                .managerPressInputGate(actionable)
                .then(longPressModifier)
                // Observe the normal Up before the original long detector consumes it.
                .pressable(interactionSource = interaction, indication = null, enabled = actionable,
                    delay = communicationTapIndicationDelayMillis())

            Box {
            Surface(
                // Keep the real bubble painted while the Android popup window attaches/detaches.
                modifier = surfaceModifier.communicationContextSource(contextTarget, selected, hideUnderPreview = false)
                    .then(if (flight == null) Modifier else Modifier.onGloballyPositioned {
                        flight.reportTarget(presentation.recordId, it)
                    }),
                shape = layout.shape,
                color = surfaceColor,
                contentColor = contentColor,
            ) {
                MessageBubbleContent(presentation, layout, contentColor,
                    Modifier.messageBubbleContentPress(if (selected) ManagerActionFeedback.None else feedback, interaction))
            }
            if (menu != null) CommunicationContextMenu(show = selected, target = contextTarget,
                onDismiss = menu.onDismiss, sourceShape = layout.shape, sourceColor = surfaceColor,
                alignEnd = presentation.direction == MessageBubbleDirection.OUTGOING,
                stableSurface = true, previewContent = { MessageBubbleContent(presentation, layout, contentColor) }) { finish ->
                    CommunicationMenuRow("复制正文", CommunicationCopy, { finish { copyMessage() } }, enabled = copyableText != null)
                    CommunicationMenuRow("消息详情", CallPhoneIcons.Information, { finish { showDetails() } },
                        divider = presentation.recordId in menu.reeditableIds)
                    if (presentation.recordId in menu.reeditableIds) CommunicationMenuRow("重新编辑", CommunicationCompose,
                        { finish { reeditMessage() } }, enabled = menu.editingEnabled, divider = false)
            }
            }
            presentation.metadataLines.filter { it.role == MessageMetadataRole.NOTE }.forEach { line ->
                Text(messageMetadataDisplayText(line), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
            }
        }
    }
}

/** The same readonly content in each owner window, not a recorded Android RenderNode. */
@Composable private fun MessageBubbleContent(presentation: MessageBubblePresentation,
    layout: MessageBubbleLayoutSpec, contentColor: Color, contentModifier: Modifier = Modifier) {
    Column(Modifier.padding(horizontal = MESSAGE_BUBBLE_CONTENT_PADDING_X, vertical = MESSAGE_BUBBLE_CONTENT_PADDING_Y)
        .then(contentModifier), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(presentation.body, color = contentColor, fontSize = layout.bodyFontSizeSp.sp,
            lineHeight = layout.bodyLineHeightSp.sp, softWrap = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presentation.metadataLines.filter { it.role != MessageMetadataRole.NOTE }.forEach { line ->
                if (line.role == MessageMetadataRole.STATUS) {
                    // C12：保留一个状态叶与固定状态槽；旧字只绘制、不保留语义，正文和时间戳不重入。
                    val density = LocalDensity.current
                    val measurer = rememberTextMeasurer()
                    val style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
                    val labels = remember { OutgoingMessageStatus.entries.map(::outgoingStatusLabel) +
                        SmsEngineStatus.entries.map { messageStatusLabel(OutgoingMessageStatus.ACCEPTED, it) } }
                    val width = with(density) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() }
                    val color = rememberManagerStateColor(if (presentation.statusFailed)
                        LocalSoftVoWiFiColors.current.error else communicationColors().metadata)
                    Box(Modifier.width(width)) {
                        ManagerStateText(messageMetadataDisplayText(line), ColorProducer { color.value }, 12.sp, 18.sp)
                    }
                } else Text(messageMetadataDisplayText(line), color = communicationColors().metadata,
                    fontSize = 12.sp, lineHeight = 18.sp, softWrap = true)
            }
        }
    }
}

private val MessageBubbleLayoutSpec.shape: RoundedCornerShape
    get() = RoundedCornerShape(
        topStart = topStartRadiusDp.dp,
        topEnd = topEndRadiusDp.dp,
        bottomEnd = bottomEndRadiusDp.dp,
        bottomStart = bottomStartRadiusDp.dp,
    )
