package com.vowifi.sw.manager.ui

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun MessageDateSeparator(label: String) {
    val colors = LocalSoftVoWiFiColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, Modifier.background(communicationColors().list.copy(alpha = .85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
    }
}

@Composable
internal fun IncomingMessageBubble(item: MessageThreadItem.IncomingBubble, modifier: Modifier = Modifier) {
    MessageBubble(
        presentation = MessageBubblePresentation(
            recordId = item.recordId,
            body = item.body,
            direction = MessageBubbleDirection.INCOMING,
            metadataLines = listOf(
                MessageMetadataLine(MessageMetadataRole.TIMESTAMP, messageTimestampLabel(item.timestamp.epochMs)),
            ),
        ),
        modifier = modifier,
    )
}

@Composable
internal fun OutgoingMessageBubble(item: MessageThreadItem.OutgoingBubble, modifier: Modifier = Modifier) {
    MessageBubble(
        presentation = MessageBubblePresentation(
            recordId = item.recordId,
            body = item.body,
            direction = MessageBubbleDirection.OUTGOING,
            metadataLines = messageOutgoingMetadataLines(
                status = item.status,
                note = item.statusNote,
                timestamp = messageTimestampLabel(item.timestamp.epochMs),
            ),
        ),
        modifier = modifier,
    )
}

private data class MessageBubblePresentation(
    val recordId: String,
    val body: String,
    val direction: MessageBubbleDirection,
    val metadataLines: List<MessageMetadataLine>,
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
    val copied = LocalCommunicationCopyFeedback.current
    val active = LocalAppRouteActive.current
    val menu = LocalSmsBubbleMenu.current
    val selected = active && menu?.selectedId == presentation.recordId
    val contextTarget = rememberCommunicationContextTarget()

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

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val computedMax = minOf(maxWidth * maxFraction, 600.dp)
        Column(
            modifier = Modifier
                .align(boxAlignment)
                .widthIn(max = computedMax),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val surfaceModifier = if (copyableText != null) {
                val copyBody: () -> Unit = {
                    if (openActions != null) openActions(presentation.recordId) else scope.launch {
                        clipboard.setClipEntry(messageTextClipEntry(copyableText))
                        copied(CommunicationCopyFeedback.MESSAGE)
                    }
                }
                val longPress = rememberManagerActionHandler(managerActionFeedback(ManagerAction.ContextAction, active), copyBody)
                val currentLongPress by rememberUpdatedState(longPress)
                Modifier
                    .semantics {
                        this.selected = selected
                        onLongClick(label = if (openActions == null) "复制短信" else "消息操作") {
                            if (active) { longPress(); true } else false
                        }
                    }
                    .pointerInput(copyableText, presentation.recordId, active) {
                        detectTapGestures(onLongPress = { currentLongPress() })
                    }
            } else {
                Modifier
            }

            Box {
            Surface(
                // Keep the real bubble painted while the Android popup window attaches/detaches.
                modifier = surfaceModifier.communicationContextSource(contextTarget, selected, hideUnderPreview = false),
                shape = layout.shape,
                color = surfaceColor,
                contentColor = contentColor,
            ) {
                MessageBubbleContent(presentation, layout, contentColor)
            }
            if (menu != null) CommunicationContextMenu(show = selected, target = contextTarget,
                onDismiss = menu.onDismiss, sourceShape = layout.shape, sourceColor = surfaceColor,
                alignEnd = presentation.direction == MessageBubbleDirection.OUTGOING,
                stableSurface = true, previewContent = { MessageBubbleContent(presentation, layout, contentColor) }) { finish ->
                    CommunicationMenuRow("复制正文", CommunicationCopy, { finish {
                        copyableText?.let { scope.launch {
                            clipboard.setClipEntry(messageTextClipEntry(it)); copied(CommunicationCopyFeedback.MESSAGE)
                        } }
                        menu.onDismiss()
                    } }, enabled = copyableText != null)
                    CommunicationMenuRow("消息详情", CallPhoneIcons.Information, { finish(menu.onDetails) },
                        divider = presentation.recordId in menu.reeditableIds)
                    if (presentation.recordId in menu.reeditableIds) CommunicationMenuRow("重新编辑", CommunicationCompose,
                        { finish(menu.onReedit) }, enabled = menu.editingEnabled, divider = false)
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
    layout: MessageBubbleLayoutSpec, contentColor: Color) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(presentation.body, color = contentColor, fontSize = layout.bodyFontSizeSp.sp,
            lineHeight = layout.bodyLineHeightSp.sp, softWrap = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presentation.metadataLines.filter { it.role != MessageMetadataRole.NOTE }.forEach { line ->
                Text(messageMetadataDisplayText(line), color = communicationColors().metadata,
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
