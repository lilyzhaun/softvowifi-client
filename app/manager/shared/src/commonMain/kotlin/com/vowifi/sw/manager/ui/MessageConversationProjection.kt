package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConversationGroup
import com.vowifi.sw.manager.data.ConversationGrouping
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.isStorageAttachmentNotice
import com.vowifi.sw.manager.data.messageConversationHandle
import com.vowifi.sw.manager.data.messageSenderDisplay
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle

internal data class MessageTimestampSpec(val epochMs: Long) {
    override fun toString(): String = "MessageTimestampSpec"
}

internal data class MessageDateSpec(val key: String, val label: String) {
    init {
        require(DATE_KEY.matches(key))
        require(label.isNotBlank() && label.length <= 40 && label.none(Char::isISOControl))
    }

    companion object {
        private val DATE_KEY = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

internal enum class MessageComposeMode {
    ENABLED,
    BUSY,
    READ_ONLY,
    STORAGE_BLOCKED,
    FIXTURE_READ_ONLY,
    NOT_READY,
}

internal data class MessageComposeSpec(
    val mode: MessageComposeMode,
    val notice: String?,
) {
    val editingEnabled: Boolean
        get() = when (mode) {
            MessageComposeMode.ENABLED,
            MessageComposeMode.READ_ONLY,
            MessageComposeMode.FIXTURE_READ_ONLY,
            -> true
            MessageComposeMode.BUSY,
            MessageComposeMode.STORAGE_BLOCKED,
            MessageComposeMode.NOT_READY,
            -> false
        }

    val sendingEnabled: Boolean
        get() = when (mode) {
            MessageComposeMode.ENABLED -> true
            MessageComposeMode.READ_ONLY,
            MessageComposeMode.FIXTURE_READ_ONLY,
            MessageComposeMode.BUSY,
            MessageComposeMode.STORAGE_BLOCKED,
            MessageComposeMode.NOT_READY,
            -> false
        }
}

internal data class MessageConversationRow(
    val handle: ConversationHandle,
    val peerDisplay: String,
    val avatarText: String,
    val latestPreview: String,
    val timestamp: MessageTimestampSpec,
    val recordCount: Int,
    val unreadCount: Int,
    val outgoingStatus: String?,
) {
    override fun toString(): String = "MessageConversationRow(recordCount=$recordCount, unreadCount=$unreadCount)"
}

internal data class MessageConversationPage(
    val rows: List<MessageConversationRow>,
    val compose: MessageComposeSpec,
)

internal sealed interface MessageThreadItem {
    data class DateSeparator(val date: MessageDateSpec) : MessageThreadItem

    data class IncomingBubble(
        val recordId: String,
        val body: String,
        val timestamp: MessageTimestampSpec,
    ) : MessageThreadItem {
        val isIncoming: Boolean = true
        override fun toString(): String = "IncomingBubble"
    }

    data class OutgoingBubble(
        val recordId: String,
        val body: String,
        val timestamp: MessageTimestampSpec,
        val status: String,
        val statusNote: String?,
    ) : MessageThreadItem {
        val isIncoming: Boolean = false
        override fun toString(): String = "OutgoingBubble"
    }
}

internal data class MessageThreadPage(
    val handle: ConversationHandle,
    val peerDisplay: String,
    val recipient: MessageRecipient?,
    val items: List<MessageThreadItem>,
    val compose: MessageComposeSpec,
) {
    override fun toString(): String = "MessageThreadPage(itemCount=${items.size})"
}

internal fun projectMessageConversations(
    ownerId: SimOwnerId?,
    state: MessageUiState,
): MessageConversationPage {
    if (ownerId == null) return MessageConversationPage(emptyList(), state.composeSpec())
    if (state is MessageUiState.Inbox) return MessageConversationPage(state.page.items.map { summary ->
        MessageConversationRow(summary.identity.handle, summary.peerDisplay, messageAvatarFallback(summary.peerDisplay),
            summary.latestPreview, MessageTimestampSpec(summary.timestampMs), summary.messageCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            summary.unreadCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), summary.outgoingStatus?.let { messageStatusLabel(it, summary.engineStatus) })
    }, state.composeSpec())
    val groups = if (state is MessageUiState.Ready) ConversationGrouping.group(state.records) else emptyList()
    return MessageConversationPage(
        rows = groups.map { group -> group.toRow(ownerId) },
        compose = state.composeSpec(),
    )
}

internal fun projectMessageThread(
    handle: ConversationHandle,
    ownerId: SimOwnerId?,
    state: MessageUiState,
    localDate: (Long) -> MessageDateSpec,
): MessageThreadPage? {
    if (state !is MessageUiState.Ready || ownerId == null) return null
    val group = ConversationGrouping.group(state.records).singleOrNull { conversationHandle(ownerId, it) == handle } ?: return null
    val items = buildList {
        var previousDateKey: String? = null
        group.records.forEach { record ->
            val date = localDate(record.timestampMs)
            if (date.key != previousDateKey) {
                add(MessageThreadItem.DateSeparator(date))
                previousDateKey = date.key
            }
            add(record.toThreadItem())
        }
    }
    return MessageThreadPage(
        handle = handle,
        peerDisplay = messageSenderDisplay(group.displayLabel),
        recipient = MessageRecipient.create(group.displayLabel),
        items = items,
        compose = state.composeSpec(),
    )
}

internal fun messageRouteTopBarTitle(route: AppRoute, state: MessageUiState): String? = when (route) {
    AppRoute.Messages -> "短信"
    is AppRoute.MessageThread ->
        projectMessageThread(route.conversation, messageProjectionOwner(state), state, ::messageDateSpec)?.peerDisplay ?: "短信会话"
    AppRoute.NewMessage -> "新建短信"
    else -> null
}

internal class MessageRecipient private constructor(val value: String) {
    override fun toString(): String = "MessageRecipient"

    companion object {
        fun create(value: String): MessageRecipient? =
            value.takeIf(::isValidMessageRecipient)?.let(::MessageRecipient)
    }
}

private fun ConversationGroup.toRow(ownerId: SimOwnerId): MessageConversationRow {
    val latest = records.last()
    return MessageConversationRow(
        handle = conversationHandle(ownerId, this),
        peerDisplay = messageSenderDisplay(displayLabel),
        avatarText = messageAvatarFallback(messageSenderDisplay(displayLabel)),
        latestPreview = boundedCommunicationText(latest.preview, "内容不可显示", 80),
        timestamp = MessageTimestampSpec(latest.timestampMs),
        recordCount = records.size,
        unreadCount = records.count { record -> record is MessageRecord.Incoming && !record.isRead },
        outgoingStatus = (latest as? MessageRecord.Outgoing)?.let(::messageRecordStatusLabel),
    )
}

internal fun messageAvatarFallback(peerDisplay: String): String =
    if (peerDisplay == "未知号码") "?" else peerDisplay.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "?"

private fun MessageRecord.toThreadItem(): MessageThreadItem = when (this) {
    is MessageRecord.Incoming -> MessageThreadItem.IncomingBubble(
        recordId = id,
        body = boundedCommunicationText(preview, "内容不可显示", 1_000),
        timestamp = MessageTimestampSpec(timestampMs),
    )
    is MessageRecord.Outgoing -> MessageThreadItem.OutgoingBubble(
        recordId = id,
        body = boundedCommunicationText(preview, "内容不可显示", 1_000),
        timestamp = MessageTimestampSpec(timestampMs),
        status = messageRecordStatusLabel(this),
        statusNote = messageStatusNote(this),
    )
}

private fun MessageUiState.composeSpec(): MessageComposeSpec = when (this) {
    is MessageUiState.Available -> when {
        origin != MessageOrigin.ENGINE -> MessageComposeSpec(MessageComposeMode.FIXTURE_READ_ONLY, attachmentNotice)
        attachmentNotice?.let(::isStorageAttachmentNotice) == true ->
            MessageComposeSpec(MessageComposeMode.STORAGE_BLOCKED, attachmentNotice)
        sendBusy -> MessageComposeSpec(MessageComposeMode.BUSY, attachmentNotice)
        !composerEnabled -> MessageComposeSpec(MessageComposeMode.READ_ONLY, attachmentNotice)
        else -> MessageComposeSpec(MessageComposeMode.ENABLED, attachmentNotice)
    }
    MessageUiState.Loading,
    MessageUiState.Empty,
    is MessageUiState.Failure,
    is MessageUiState.PermissionDenied,
    is MessageUiState.Unavailable,
    -> MessageComposeSpec(MessageComposeMode.NOT_READY, null)
}

private fun conversationHandle(ownerId: SimOwnerId, group: ConversationGroup): ConversationHandle {
    return messageConversationHandle(ownerId, group.conversationKey)
}

internal fun messageProjectionOwner(state: MessageUiState): SimOwnerId? {
    val ready = state as? MessageUiState.Available ?: return null
    return ready.ownerId ?: if (ready.origin != MessageOrigin.ENGINE) messageProjectionOwner() else null
}
