package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
) {
    var open by remember { mutableStateOf(false) }
    val active by rememberUpdatedState(LocalAppRouteActive.current && enabled)
    val action by rememberUpdatedState(onAction)
    val target = rememberCommunicationContextTarget()
    LaunchedEffect(active) { if (!active) open = false }
    Box(modifier) {
        ConversationRow(spec, onOpen, Modifier.communicationContextSource(target, open, hideUnderPreview = false), unreadCount,
            onLongClick = { if (active) open = true }, contextOpen = open)
        CommunicationContextMenu(open && active, target, { open = false },
            alignEnd = true, stableSurface = true,
            previewContent = { progress -> CompositionLocalProvider(LocalAppRouteActive provides false) {
                ConversationRow(spec, {}, unreadCount = unreadCount, previewInset = 12.dp * progress)
            } }) { finish ->
            CommunicationMenuRow("标为未读", MiuixIcons.Messages, { finish {
                open = false; if (active) action(MessageConversationAction.MARK_UNREAD)
            } })
            CommunicationMenuRow("删除", CommunicationTrash, { finish {
                open = false; if (active) action(MessageConversationAction.DELETE)
            } }, destructive = true, divider = false)
        }
    }
}
