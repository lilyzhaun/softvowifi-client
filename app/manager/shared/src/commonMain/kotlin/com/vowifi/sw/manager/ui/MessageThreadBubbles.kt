package com.vowifi.sw.manager.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun MessageDateSeparator(label: String) {
    val colors = LocalSoftVoWiFiColors.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, softWrap = true)
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
    var copyFeedbackToken by remember(presentation.body) { mutableStateOf(0) }

    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedbackToken > 0) {
            delay(1200L)
            copyFeedbackToken = 0
        }
    }

    val horizontalAlignment = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> Alignment.Start
        MessageBubbleDirection.OUTGOING -> Alignment.End
    }
    val boxAlignment = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> Alignment.CenterStart
        MessageBubbleDirection.OUTGOING -> Alignment.CenterEnd
    }
    val surfaceColor = when (presentation.direction) {
        MessageBubbleDirection.INCOMING -> colors.surface
        MessageBubbleDirection.OUTGOING -> lerp(colors.canvas, colors.interactive, 0.16f)
    }
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
                        copyFeedbackToken++
                    }
                }
                val currentLongPress by rememberUpdatedState(copyBody)
                Modifier
                    .semantics {
                        onLongClick(label = if (openActions == null) "复制短信" else "消息操作") {
                            copyBody()
                            true
                        }
                    }
                    .pointerInput(copyableText, presentation.recordId) {
                        detectTapGestures(onLongPress = { currentLongPress() })
                    }
            } else {
                Modifier
            }

            Surface(
                modifier = surfaceModifier,
                shape = layout.shape,
                color = surfaceColor,
                contentColor = contentColor,
            ) {
                Box(Modifier.padding(12.dp)) {
                    Text(
                        text = presentation.body,
                        fontSize = layout.bodyFontSizeSp.sp,
                        lineHeight = layout.bodyLineHeightSp.sp,
                        softWrap = true,
                    )
                }
            }

            presentation.metadataLines.forEach { line ->
                Text(
                    text = messageMetadataDisplayText(line),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    softWrap = true,
                )
            }

            if (copyFeedbackToken > 0) {
                Text(
                    text = MESSAGE_COPIED_FEEDBACK,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    softWrap = true,
                )
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
