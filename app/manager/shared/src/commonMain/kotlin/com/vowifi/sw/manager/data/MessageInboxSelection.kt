package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageHistoryChoices
import com.vowifi.sw.manager.model.MessageHistoryLine
import com.vowifi.sw.manager.model.MessageInboxPage
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.boundedMessageSearchQuery
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class MessageInboxSelection(
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    val choices = MutableStateFlow(MessageHistoryChoices())
    private var owners: List<MessageOwnerSummary>? = null
    private var labels = emptyMap<SimOwnerId, MessageOwnerLabel>()
    private var remarkRevision = 0L
    private var selected: SimOwnerId? = null
    private var target: SimOwnerId? = null
    private var query = ""
    private var page = MessageInboxPage()
    private var retryAppend = false
    private var indexRevision = 0L
    private var pageRevision = 0L
    private var indexJob: Job? = null
    private var pageJob: Job? = null
    private var indexLoading = false
    private var indexFailed = false
    private var live: MessageUiState = MessageUiState.Loading

    suspend fun saveRemark(owner: SimOwnerId, input: String): MessageRemarkSaveResult {
        val remark = try { MessageOwnerRemark.parse(input) } catch (_: InvalidMessageOwnerRemark) { return MessageRemarkSaveResult.Invalid }
        return try {
            val saved = repository.saveOwnerRemark(owner, remark)
            if (saved.remark != remark.value) return MessageRemarkSaveResult.Failed
            synchronized(lock) { labels = labels + (owner to saved); remarkRevision++ }
            changed()
            MessageRemarkSaveResult.Saved
        } catch (_: MessageStorageException) { MessageRemarkSaveResult.Failed }
    }

    fun refresh() {
      synchronized(lock) {
        val token = ++indexRevision
        val remarkToken = remarkRevision
        indexJob?.cancel()
        indexLoading = true
        indexFailed = false
        indexJob = scope.launch {
            try {
                val rows = mutableListOf<MessageOwnerSummary>()
                var cursor: MessageOwnerCursor? = null
                do {
                    val result = repository.history.owners(cursor)
                    rows.addAll(result.items)
                    cursor = result.next
                } while (cursor != null)
                val current = synchronized(lock) { (live as? MessageUiState.Ready)?.ownerId }
                val names = repository.ownerLabels((rows.map { it.owner } + listOfNotNull(current)).distinct())
                synchronized(lock) {
                    if (token != indexRevision) return@launch
                    owners = rows
                    labels = if (remarkToken == remarkRevision) names else names + labels
                    indexLoading = false
                    target?.let { load(it, append = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: MessageStorageException) {
                synchronized(lock) {
                    if (token != indexRevision) return@launch
                    indexLoading = false
                    indexFailed = true
                }
            }
            changed()
        }
      }
      changed()
    }

    fun select(owner: SimOwnerId) {
        synchronized(lock) {
            if (choices.value.lines.none { it.owner == owner }) return
            selected = owner
        }
        changed()
    }

    fun search(value: String) {
      synchronized(lock) {
        val bounded = boundedMessageSearchQuery(value).trim()
        if (bounded == query) return
        query = bounded
        page = MessageInboxPage(query)
        target?.let { load(it, append = false) }
      }
      changed()
    }

    fun more() {
      synchronized(lock) {
        if (page.loading || page.next == null && !page.failed) return
        target?.let { load(it, append = if (page.failed) retryAppend else page.next != null) }
      }
      changed()
    }

    fun render(value: MessageUiState): MessageUiState = synchronized(lock) {
        live = value
        val current = value as? MessageUiState.Ready
        if (current?.ownerId != null && current.ownerId !in labels && !indexLoading && !indexFailed) refresh()
        val rows = owners.orEmpty()
        val owner = selected ?: current?.takeIf { it.composerEnabled }?.ownerId ?: rows.firstOrNull()?.owner ?: current?.ownerId
        choices.value = MessageHistoryChoices(
            (rows.map { it.owner } + listOfNotNull(current?.ownerId)).distinct().map { id ->
                MessageHistoryLine(id, rows.firstOrNull { it.owner == id }?.conversationCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                    id == current?.ownerId && current.composerEnabled, labels[id])
            }.sortedBy { it.label?.ordinal ?: Long.MAX_VALUE }, owner,
            storageAvailable = owners != null && !indexFailed, labelsLoading = indexLoading, labelsFailed = indexFailed,
        )
        if (owners == null) return@synchronized if (indexFailed) MessageUiState.Failure(STORAGE_OPEN_NOTICE) else MessageUiState.Loading
        if (owner == null) return@synchronized live
        if (target != owner) {
            target = owner
            page = MessageInboxPage(query)
            load(owner, append = false)
        }
        val connected = current?.takeIf { it.ownerId == owner }
        MessageUiState.Inbox(owner, page, connected?.sendBusy == true, connected?.composerEnabled == true,
            connected?.attachmentNotice ?: if (connected?.composerEnabled == true) null else HISTORY_READ_ONLY,
            connected?.sendAttempt)
    }

    fun read(owner: SimOwnerId, update: IncomingReadUpdate, nowMs: Long) {
        if (update.updatedCount > 0) refresh()
    }

    fun close() = synchronized(lock) {
        indexRevision++
        pageRevision++
        indexJob?.cancel()
        pageJob?.cancel()
    }

    private fun load(owner: SimOwnerId, append: Boolean) {
        val token = ++pageRevision
        pageJob?.cancel()
        val request = MessageSummaryRequest(owner, query, if (append) page.next else null)
        val count = if (append) MESSAGE_HISTORY_PAGE_SIZE else maxOf(MESSAGE_HISTORY_PAGE_SIZE, page.items.size)
        page = page.copy(loading = true, failed = false)
        pageJob = scope.launch {
            try {
                var result = repository.history.summaries(request)
                val loaded = result.items.toMutableList()
                while (!append && loaded.size < count && result.next != null) {
                    result = repository.history.summaries(request.copy(cursor = result.next))
                    loaded.addAll(result.items)
                }
                synchronized(lock) {
                    if (token != pageRevision || owner != target) return@launch
                    page = page.copy(items = (if (append) page.items + loaded else loaded).distinctBy { it.identity.key },
                        next = result.next, loading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: MessageStorageException) {
                synchronized(lock) {
                    if (token != pageRevision) return@launch
                    retryAppend = append
                    page = page.copy(loading = false, failed = true)
                }
            }
            changed()
        }
    }
}
