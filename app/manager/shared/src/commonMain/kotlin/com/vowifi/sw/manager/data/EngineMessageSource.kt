package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class EngineMessageSource(
    runtime: EngineMessageRuntime,
    parentContext: CoroutineContext,
    repository: MessageRepository,
    ownerIdFor: (String) -> SimOwnerId?,
    nextMessageId: () -> String,
    observer: MessagePersistenceObserver = MessagePersistenceObserver { _, _, _ -> },
) : MessageSource {
    private val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
    private val session = EngineMessageSession(
        runtime = runtime,
        repository = repository,
        ownerIdFor = ownerIdFor,
        nextMessageId = nextMessageId,
        scope = scope,
        observer = observer,
    )
    override val state: StateFlow<MessageUiState> = session.state
    override val unreadSummary = session.unreadSummary.state
    override val historyChoices = session.selection.historyChoices
    override suspend fun lookupConversation(owner: SimOwnerId, recipient: String): MessageConversationLookup {
        if (session.closed) return MessageConversationLookup.Unavailable
        val identity = StoredMessageConversation(owner, PeerNumberNormalizer.normalize(recipient))
        return try {
            if (session.repository.history.hasConversation(identity)) MessageConversationLookup.Found(identity.handle)
            else MessageConversationLookup.Missing
        } catch (_: MessageStorageException) {
            MessageConversationLookup.Unavailable
        }
    }
    override suspend fun findConversation(owner: SimOwnerId, recipient: String): com.vowifi.sw.manager.ui.navigation.ConversationHandle? =
        (lookupConversation(owner, recipient) as? MessageConversationLookup.Found)?.handle
    override suspend fun saveOwnerRemark(owner: SimOwnerId, input: String) = session.selection.saveOwnerRemark(owner, input)
    override fun selectHistoryOwner(owner: SimOwnerId) = session.selection.selectHistoryOwner(owner)
    override fun selectConversation(handle: String?) = session.selection.select(handle)
    override fun searchHistory(query: String) = session.selection.searchInbox(query)
    override fun loadMoreHistory() = session.selection.loadMoreInbox()
    override fun loadOlderMessages() = session.selection.loadOlder()
    override suspend fun changeConversation(handle: String, action: MessageConversationAction) =
        changeStoredMessageConversation(session, handle, action)
    override fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) {
        val displayed = state.value as? MessageUiState.Available ?: return
        if (session.closed || owner == null || displayed.ownerId != owner || !displayed.composerEnabled) return
        val line = session.sessions[owner] ?: return
        scope.launch { sendMessage(session, line, number, text) }
    }

    init {
        refresh()
    }

    override fun refresh() {
        if (session.closed) return
        session.unreadSummary.refresh()
        session.selection.refreshInbox()
        scope.launch { refreshMessageAttachment(session) }
    }

    internal fun watchAttachment() = scope.launch {
        while (isActive) {
            delay(3_000)
            session.unreadSummary.refresh()
            refreshMessageAttachment(session, force = false)
        }
    }

    override fun sendSms(number: String, text: String) {
        sendFromDisplayedOwner((state.value as? MessageUiState.Available)?.ownerId, number, text)
    }

    override fun markVisibleRead(request: ObservedIncomingReadRequest) {
        if (session.closed) return
        scope.launch { markVisibleIncomingRead(session, request) }
    }

    override fun close() {
        session.closed = true
        session.sessions.values.forEach { it.transport.retireMessages() }
        session.selection.close()
        scope.cancel()
    }
}
