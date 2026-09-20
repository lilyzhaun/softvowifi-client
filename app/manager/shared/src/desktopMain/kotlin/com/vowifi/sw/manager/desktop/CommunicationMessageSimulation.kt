package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.fixtureUnreadSummary
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.ui.messageProjectionOwner
import com.vowifi.sw.manager.ui.projectMessageConversations
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class CommunicationMessageSimulation(private val failSend: Boolean) : MessageSource {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val owner = messageProjectionOwner()
    private var nextId = 0
    private val records = buildList<MessageRecord> {
        repeat(123) { index ->
            add(MessageRecord.Incoming("simulation-$index", "+15550100123", "合成消息：周末在公园见面，出发前请告诉我。", 1_783_000_000_000L + index * 60_000L))
        }
        add(MessageRecord.Incoming("simulation-read", "+15550100456", "合成消息：收到，谢谢。", 1_782_900_000_000L, isRead = true))
        add(MessageRecord.Outgoing("simulation-failed", "+15550100789", "合成草稿：确认下次见面的时间。", 1_782_800_000_000L, OutgoingMessageStatus.FAILED))
    }
    private val mutable = MutableStateFlow<MessageUiState>(MessageUiState.Ready(
        records, MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner,
        attachmentNotice = "桌面合成通信演练，不连接手机或运营商。",
    ))
    override val state: StateFlow<MessageUiState> = mutable
    private val mutableUnreadSummary = MutableStateFlow(fixtureUnreadSummary(mutable.value))
    override val unreadSummary: StateFlow<com.vowifi.sw.manager.model.MessageUnreadSummary> = mutableUnreadSummary
    override fun refresh() = Unit

    override fun sendSms(number: String, text: String) {
        val current = mutable.value as MessageUiState.Ready
        if (current.sendBusy) return
        val record = MessageRecord.Outgoing("simulation-send-${nextId++}", number, text, 1_783_100_000_000L + nextId, OutgoingMessageStatus.QUEUED)
        mutable.value = current.copy(records = current.records + record, sendBusy = true)
        scope.launch {
            delay(600)
            val latest = mutable.value as MessageUiState.Ready
            mutable.value = latest.copy(sendBusy = false, records = latest.records.map {
                if (it.id == record.id) record.copy(status = if (failSend) OutgoingMessageStatus.FAILED else OutgoingMessageStatus.ACCEPTED) else it
            })
        }
    }

    override fun observeVisible(handle: ConversationHandle?, ids: List<String>) {
        if (handle == null || ids.isEmpty()) return
        val current = mutable.value as MessageUiState.Ready
        val row = projectMessageConversations(owner, current).rows.singleOrNull { it.handle == handle } ?: return
        val next = current.records.map {
            if (it is MessageRecord.Incoming && it.id in ids && it.peerLabel == row.peerDisplay) it.copy(isRead = true) else it
        }
        if (next != current.records) mutable.value = current.copy(records = next)
        mutableUnreadSummary.value = fixtureUnreadSummary(mutable.value)
    }

    override fun close() = scope.cancel()
}
