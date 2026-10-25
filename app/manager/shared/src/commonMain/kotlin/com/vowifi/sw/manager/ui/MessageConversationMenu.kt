package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.MessageConversationAction
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages

@Composable
internal fun MessageConversationMenu(
    spec: ConversationRowPresentation,
    unreadCount: Int,
    onOpen: () -> Unit,
    onAction: (MessageConversationAction) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    statusFailed: Boolean = false,
    pending: Boolean = false,
    removing: Boolean = false,
    onRemoved: () -> Unit = {},
    rootCustomActionsBlocked: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val active by rememberUpdatedState(LocalAppRouteActive.current && enabled)
    val action by rememberUpdatedState(onAction)
    val target = rememberCommunicationContextTarget()
    val mounted = remember { mutableStateOf(true) }
    DisposableEffect(mounted) { onDispose { mounted.value = false } }
    val available by rememberUpdatedState(active && !pending && !removing)
    val rootBlocked by rememberUpdatedState(rootCustomActionsBlocked)
    val readAction = if (unreadCount > 0) MessageConversationAction.MARK_READ else MessageConversationAction.MARK_UNREAD
    val readLabel = if (unreadCount > 0) "标为已读" else "标为未读"
    val markReadState by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !available) false else { open = false; action(readAction); true }
    })
    val deleteConversation by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !available) false else { open = false; action(MessageConversationAction.DELETE); true }
    })
    val alpha = remember { Animatable(1f) }
    val finish by rememberUpdatedState(onRemoved)
    LaunchedEffect(pending, removing) {
        alpha.animateTo(if (removing) 0f else if (pending) MotionTokens.AlphaPending else 1f,
            tween(MotionTokens.DurFast, easing = if (removing) MotionTokens.EaseExit else MotionTokens.EaseStandard))
        if (removing) finish()
    }
    LaunchedEffect(active) { if (!active) open = false }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }.then(if (removing) Modifier.clearAndSetSemantics { } else Modifier)) {
        ConversationRow(spec, onOpen, Modifier.communicationContextSource(target, open, hideUnderPreview = false), unreadCount,
            onLongClick = { if (active) open = true }, contextOpen = open, enabled = enabled && !pending && !removing,
            statusFailed = statusFailed, accessibilityActions = if (!available || rootBlocked || open || target.presented) emptyList() else listOf(
                CustomAccessibilityAction(readLabel) { mounted.value && !rootBlocked && !open && !target.presented && markReadState() },
                CustomAccessibilityAction("删除") { mounted.value && !rootBlocked && !open && !target.presented && deleteConversation() }))
        CommunicationContextMenu(open && available, target, { open = false },
            alignEnd = true, stableSurface = true,
            previewContent = { progress -> CompositionLocalProvider(LocalAppRouteActive provides false) {
                ConversationRow(spec, {}, unreadCount = unreadCount, previewInset = 12.dp * progress)
            } }) { finish ->
            CommunicationMenuRow(readLabel, MiuixIcons.Messages, { finish { markReadState() } }, enabled = available)
            CommunicationMenuRow("删除", CommunicationTrash, { finish { deleteConversation() } },
                enabled = available, destructive = true, divider = false)
        }
    }
}
