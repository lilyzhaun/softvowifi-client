package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState

internal class MessageOwnerDraft(
    val recipient: String,
    val body: String,
    val updateRecipient: (String) -> Unit,
    val updateBody: (String) -> Unit,
    val clear: () -> Unit,
    val canBindUnassigned: (SimOwnerId) -> Boolean,
    val bindUnassigned: (SimOwnerId) -> Boolean,
)

@Composable
internal fun rememberMessageOwnerDraft(owner: SimOwnerId?): MessageOwnerDraft {
    var entries by rememberSaveable { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val key = owner?.wire().orEmpty()
    val entry = entries[key] ?: listOf("", "")
    val canBindUnassigned: (SimOwnerId) -> Boolean = { target ->
        entries[""].orEmpty().none(String::isNotEmpty) || entries[target.wire()].orEmpty().none(String::isNotEmpty)
    }
    return remember(key, entry[0], entry[1]) { MessageOwnerDraft(
        recipient = entry[0],
        body = entry[1],
        updateRecipient = { value -> entries = entries + (key to listOf(value, entries[key]?.get(1).orEmpty())) },
        updateBody = { value -> entries = entries + (key to listOf(entries[key]?.get(0).orEmpty(), value)) },
        clear = { entries = entries - key },
        canBindUnassigned = canBindUnassigned,
        bindUnassigned = { target ->
            val unassigned = entries[""].orEmpty()
            val targetKey = target.wire()
            if (!canBindUnassigned(target)) {
                false
            } else {
                if (unassigned.any(String::isNotEmpty)) entries = (entries - "") + (targetKey to unassigned)
                true
            }
        },
    ) }
}

internal class BoundMessageDraft(
    val owner: SimOwnerId?,
    val draft: MessageOwnerDraft,
    val selectionNotice: String?,
    val clearUnbound: () -> Unit,
    val selectOwner: (SimOwnerId) -> Unit,
    val confirmOwner: (SimOwnerId) -> Boolean,
)

@Composable
internal fun rememberBoundMessageDraft(
    state: MessageUiState,
    canInitialize: Boolean,
    onSelectOwner: (SimOwnerId) -> Boolean,
): BoundMessageDraft {
    var ownerWire by rememberSaveable { mutableStateOf<String?>(null) }
    var initializeAutomatically by rememberSaveable { mutableStateOf(true) }
    var selectionNotice by remember { mutableStateOf<String?>(null) }
    val owner = ownerWire?.let(SimOwnerId::parse)
    val draft = rememberMessageOwnerDraft(owner)
    val available = state as? MessageUiState.Available
    LaunchedEffect(canInitialize, initializeAutomatically, available?.ownerId, available?.composerEnabled, available?.origin,
        owner, draft.recipient, draft.body) {
        if (canInitialize && initializeAutomatically && draft.recipient.isEmpty() && draft.body.isEmpty() &&
            available?.origin == MessageOrigin.ENGINE && available.composerEnabled) {
            ownerWire = available.ownerId?.wire()
        }
    }
    fun choose(target: SimOwnerId): Boolean {
        if (owner == null && !draft.canBindUnassigned(target)) {
            selectionNotice = "该线路已有草稿。请先清空当前未选线路的草稿，再切换；两份草稿均已保留。"
            return false
        }
        if (!onSelectOwner(target)) return false
        // The synchronous source projection has accepted the owner; only now may drafts move.
        if (owner == null) draft.bindUnassigned(target)
        initializeAutomatically = false
        ownerWire = target.wire()
        selectionNotice = null
        return true
    }
    return remember(owner, draft, selectionNotice, onSelectOwner) { BoundMessageDraft(owner, draft, selectionNotice, clearUnbound = {
        if (ownerWire == null) {
            initializeAutomatically = false
            draft.clear()
            selectionNotice = null
        }
    }, selectOwner = { target -> choose(target); Unit }, confirmOwner = ::choose) }
}

internal fun messageStateForDraft(state: MessageUiState, owner: SimOwnerId?): MessageUiState =
    if (state is MessageUiState.Available && (owner == null || owner != state.ownerId)) {
        MessageUiState.Ready(emptyList(), state.origin, ownerId = owner,
            attachmentNotice = state.attachmentNotice)
    } else state
