package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageUnreadSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MessageSource {
    fun searchHistory(query: String) = Unit
    fun loadMoreHistory() = Unit
    fun loadOlderMessages() = Unit
    suspend fun saveOwnerRemark(owner: SimOwnerId, input: String): MessageRemarkSaveResult = MessageRemarkSaveResult.Failed
    val historyChoices: StateFlow<com.vowifi.sw.manager.model.MessageHistoryChoices> get() = unavailableHistoryChoices
    fun selectHistoryOwner(owner: SimOwnerId) = Unit
    fun selectConversation(handle: String?) = Unit
    fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) = sendSms(number, text)
    val unreadSummary: StateFlow<MessageUnreadSummary> get() = unavailableUnreadSummary
    val notificationsEnabled: StateFlow<Boolean?> get() = unavailableNotificationAccess
    fun openNotificationSettings() = Unit
    fun observeVisible(handle: com.vowifi.sw.manager.ui.navigation.ConversationHandle?, ids: List<String>) = Unit
    val state: StateFlow<MessageUiState>
    fun refresh()
    fun sendSms(number: String, text: String) = Unit
    fun markVisibleRead(request: ObservedIncomingReadRequest) = Unit
    fun close() = Unit
}

private val unavailableNotificationAccess: StateFlow<Boolean?> = MutableStateFlow(null)
private val unavailableHistoryChoices = MutableStateFlow(com.vowifi.sw.manager.model.MessageHistoryChoices())
private val unavailableUnreadSummary: StateFlow<MessageUnreadSummary> =
    MutableStateFlow(MessageUnreadSummary.Unavailable(null))

class FixtureMessageSource(private val mode: MessageFixtureMode) : MessageSource {
    constructor(mode: String) : this(MessageFixtureMode.fromWire(mode))

    private val mutableState = MutableStateFlow(MessageFixtures.state(mode))
    override val state: StateFlow<MessageUiState> = mutableState.asStateFlow()
    private val mutableUnreadSummary = MutableStateFlow(fixtureUnreadSummary(mutableState.value))
    override val unreadSummary: StateFlow<MessageUnreadSummary> = mutableUnreadSummary.asStateFlow()

    override fun refresh() {
        mutableState.value = when (mode) {
            MessageFixtureMode.UNAVAILABLE -> MessageFixtures.state(MessageFixtureMode.UNAVAILABLE)
            MessageFixtureMode.READ_ONLY -> MessageFixtures.state(MessageFixtureMode.READ_ONLY)
            MessageFixtureMode.LOADING,
            MessageFixtureMode.EMPTY,
            MessageFixtureMode.READY,
            MessageFixtureMode.ERROR,
            MessageFixtureMode.PERMISSION -> MessageFixtures.ready()
        }
        mutableUnreadSummary.value = fixtureUnreadSummary(mutableState.value)
    }
}

class UnavailableMessageSource : MessageSource {
    private val mutableState = MutableStateFlow(
        MessageUiState.Unavailable(MessageFixtures.PRODUCTION_UNAVAILABLE_MESSAGE),
    )
    override val state: StateFlow<MessageUiState> = mutableState.asStateFlow()

    override fun refresh() = Unit
}
