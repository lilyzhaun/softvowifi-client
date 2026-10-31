package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.ui.navigation.ConversationHandle

/** Unlike an optional suggestion, an explicit navigation must distinguish missing from unreadable. */
sealed interface MessageConversationLookup {
    data class Found(val handle: ConversationHandle) : MessageConversationLookup
    data object Missing : MessageConversationLookup
    data object Unavailable : MessageConversationLookup
}
