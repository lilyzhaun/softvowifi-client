package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageThreadWindow
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MessageHistorySelection(
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    private val observer: MessagePersistenceObserver,
) {
    private val lock = Any()
    private val output = MutableStateFlow<MessageUiState>(MessageUiState.Loading)
    val state: StateFlow<MessageUiState> = output
    private var live: MessageUiState = MessageUiState.Loading
    private var liveOwners = emptyMap<SimOwnerId, MessageUiState.Ready>()
    private var activeOwners: Set<SimOwnerId>? = null
    private var activeRosterComplete = false
    private var automaticOwner: SimOwnerId? = null
    private var lineLabels = emptyMap<SimOwnerId, String>()
    private var runningOwners = emptySet<SimOwnerId>()
    private var selected: String? = null
    private var target: StoredMessageConversation? = null
    private var dataRevision = 0L
    private var revision = 0L
    private var job: Job? = null
    private var history: MessageUiState = MessageUiState.Loading
    private var retryAppend = false
    private val reads = mutableMapOf<String, Long>()
    private val inbox = MessageInboxSelection(repository, scope) { synchronized(lock) { render() } }
    val historyChoices get() = inbox.choices
    suspend fun saveOwnerRemark(owner: SimOwnerId, input: String) = inbox.saveRemark(owner, input)
    fun refreshInbox() = inbox.refresh()
    fun refreshInboxContent() = inbox.refreshPage(showLoading = false)
    suspend fun initialOwners() = inbox.initialOwners.await()
    fun refreshAfterStartup() = synchronized(lock) {
        inbox.refreshPage()
        selected?.let { load(it, append = false) }
    }
    fun selectHistoryOwner(owner: SimOwnerId) = inbox.select(owner)
    fun searchInbox(query: String) = inbox.search(query)
    fun loadMoreInbox() = inbox.more()

    fun select(raw: String?) = synchronized(lock) {
        if (raw == selected) return@synchronized
        selected = raw
        target = null
        revision++
        job?.cancel()
        reads.clear()
        history = MessageUiState.Loading
        if (raw != null) load(raw, append = false)
        render()
    }

    fun loadOlder() = synchronized(lock) {
        val raw = selected ?: return@synchronized
        val window = (history as? MessageUiState.Ready)?.window
        if (window?.loading == true || window != null && window.older == null && !window.failed) return@synchronized
        load(raw, append = if (window?.failed == true) retryAppend else window?.older != null)
        render()
    }

    fun publishLive(
        value: MessageUiState,
        sessions: Map<SimOwnerId, MessageUiState.Ready> = (value as? MessageUiState.Ready)?.let { ready ->
            ready.ownerId?.let { mapOf(it to ready) }
        }.orEmpty(),
        availableOwners: Set<SimOwnerId>? = null,
        defaultOwner: SimOwnerId? = (value as? MessageUiState.Ready)?.takeIf { it.composerEnabled }?.ownerId,
        availableOwnersComplete: Boolean = availableOwners != null,
        availableLineLabels: Map<SimOwnerId, String> = emptyMap(),
        runningLineOwners: Set<SimOwnerId> = emptySet(),
    ) = synchronized(lock) {
        val previous = liveOwners
        liveOwners = sessions
        activeOwners = availableOwners
        activeRosterComplete = availableOwnersComplete
        automaticOwner = defaultOwner
        lineLabels = availableLineLabels
        runningOwners = runningLineOwners
        live = value
        if (previous.keys != sessions.keys || sessions.any { (owner, current) -> previous[owner]?.records !== current.records }) {
            inbox.refresh()
            target?.let { identity ->
                if (previous[identity.owner]?.records !== sessions[identity.owner]?.records) {
                    dataRevision++
                    selected?.let { load(it, append = false) }
                }
            }
        }
        render()
    }

    fun close() = synchronized(lock) {
        revision++
        job?.cancel()
        inbox.close()
    }

    fun allowsSend(owner: SimOwnerId): Boolean = synchronized(lock) {
        val displayed = output.value as? MessageUiState.Available
        displayed?.ownerId == owner && displayed.composerEnabled
    }

    fun read(owner: SimOwnerId, update: IncomingReadUpdate, nowMs: Long) = synchronized(lock) {
        inbox.read(owner, update, nowMs)
        val current = history as? MessageUiState.Ready
        if (current == null || current.ownerId == owner) update.updatedIds.forEach { reads[it] = nowMs }
        if (current?.ownerId == owner) history = current.copy(records = applyReads(current.records))
        render()
    }

    private fun load(raw: String, append: Boolean) {
        val token = ++revision
        job?.cancel()
        val previous = history as? MessageUiState.Ready
        val knownIdentity = previous?.window?.identity ?: target
        history = previous?.copy(window = previous.window?.copy(loading = true, failed = false)) ?: MessageUiState.Loading
        job = scope.launch {
            try {
                val identity = knownIdentity ?: repository.history.resolveConversation(raw)
                if (identity == null) {
                    synchronized(lock) { if (token == revision) history = MessageUiState.Unavailable("该会话已不存在，请返回短信列表。") }
                } else {
                    val dataToken = synchronized(lock) {
                        if (token != revision) return@launch
                        target = identity
                        dataRevision
                    }
                    val slice = if (append) repository.history.thread(identity, previous?.window?.older)
                        else repository.history.refreshThread(identity, previous?.records?.firstOrNull())
                    if (!append && slice.records.isEmpty()) {
                        synchronized(lock) {
                            if (token == revision && dataToken == dataRevision) {
                                history = MessageUiState.Unavailable("该会话已不存在，请返回短信列表。")
                                render()
                            }
                        }
                        return@launch
                    }
                    val unread = repository.history.unreadIds(identity).toSet()
                    synchronized(lock) {
                        if (token != revision || dataToken != dataRevision) return@launch
                        val records = if (append) (slice.records + previous?.records.orEmpty()).distinctBy { it.id } else slice.records
                        history = MessageUiState.Ready(applyReads(records), MessageOrigin.ENGINE, ownerId = identity.owner,
                            composerEnabled = false, attachmentNotice = HISTORY_READ_ONLY,
                            window = MessageThreadWindow(identity, slice.older))
                        if (identity.owner !in liveOwners) observer.onThreadUnread(identity, unread - reads.keys)
                        reads.clear()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: MessageStorageException) {
                synchronized(lock) {
                    if (token != revision) return@launch
                    retryAppend = append
                    history = previous?.copy(window = previous.window?.copy(loading = false, failed = true))
                        ?: MessageUiState.Failure(STORAGE_OPEN_NOTICE)
                }
            }
            synchronized(lock) { if (token == revision) render() }
        }
    }

    private fun applyReads(records: List<MessageRecord>) = records.map { record ->
        val time = reads[record.id]
        if (record is MessageRecord.Incoming && time != null) record.copy(isRead = true, readAtMs = time) else record
    }

    private fun render() {
        val root = inbox.render(live, liveOwners, activeOwners, automaticOwner, activeRosterComplete, lineLabels, runningOwners)
        val saved = history as? MessageUiState.Ready
        val current = saved?.ownerId?.let(liveOwners::get)
        output.value = if (selected == null) root else if (saved != null && current != null) {
            saved.copy(sendBusy = current.sendBusy, composerEnabled = current.composerEnabled,
                attachmentNotice = current.attachmentNotice, sendAttempt = current.sendAttempt)
        } else history
    }
}

internal const val HISTORY_READ_ONLY = "历史 SIM 会话，只读；当前未连接此 SIM，不能发送短信。"
