package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal enum class MessageScrollOwner {
    CONVERSATION_LIST,
    THREAD_TIMELINE,
    NEW_MESSAGE_FORM,
}

internal enum class MessageImeOwner {
    COMPOSE_COMPOSER_PADDING,
}

internal data class MessageRouteLayout(
    val scrollOwner: MessageScrollOwner,
    val composerPinned: Boolean,
    val imeOwner: MessageImeOwner,
    val composerConsumesImeInsets: Boolean,
    val reserveLastItemSpace: Boolean,
)

internal data class MessageTextLayout(
    val softWrap: Boolean,
    val breakUnboundedText: Boolean,
)

internal data class MessageListLayoutSpec(
    val rowMinHeightDp: Int,
    val avatarSizeDp: Int,
    val topActionSizeDp: Int,
    val minimumTouchDp: Int,
    val previewMaxLines: Int,
    val statusMaxLines: Int,
    val usesFab: Boolean,
    val openRows: Boolean,
)

internal data class MessageComposerLayoutSpec(
    val sendTargetDp: Int,
    val horizontal: Boolean,
    val pinnedAboveIme: Boolean,
    val capsule: Boolean,
    val circularSendAction: Boolean,
    val attachmentAction: Boolean,
)

internal enum class MessageBubbleDirection {
    INCOMING,
    OUTGOING,
}

internal enum class MessageBubbleColorRole {
    SURFACE_CONTAINER,
    INTERACTIVE_PRIMARY,
}

internal data class MessageBubbleLayoutSpec(
    val maximumWidthFraction: Float,
    val bodyFontSizeSp: Int,
    val bodyLineHeightSp: Int,
    val topStartRadiusDp: Int,
    val topEndRadiusDp: Int,
    val bottomStartRadiusDp: Int,
    val bottomEndRadiusDp: Int,
    val colorRole: MessageBubbleColorRole,
)

internal data class MessageMotionSpec(
    val durationMillis: Int,
    val rootCrossfade: Boolean,
)

internal enum class MessageMotionProperty {
    ALPHA,
    COLOR,
    SCALE,
}

internal data class MessageContentAnimationContract(
    val durationMillis: Int,
    val listAndBubbleProperties: Set<MessageMotionProperty>,
    val animatePlacement: Boolean,
    val placementDurationMillis: Int?,
    val sendActionProperties: Set<MessageMotionProperty>,
    val decorativePulse: Boolean,
)

internal data class MessageDraftPolicy(
    val preserveOnBack: Boolean,
    val clearAfterSubmission: Boolean,
)

internal fun messageConversationRoute(row: MessageConversationRow): AppRoute.MessageThread =
    AppRoute.MessageThread(row.handle)

internal fun messageRouteLayout(route: AppRoute): MessageRouteLayout = when (route) {
    AppRoute.Messages -> MessageRouteLayout(
        MessageScrollOwner.CONVERSATION_LIST,
        composerPinned = false,
        imeOwner = MessageImeOwner.COMPOSE_COMPOSER_PADDING,
        composerConsumesImeInsets = false,
        reserveLastItemSpace = false,
    )
    is AppRoute.MessageThread -> MessageRouteLayout(
        MessageScrollOwner.THREAD_TIMELINE,
        composerPinned = true,
        imeOwner = MessageImeOwner.COMPOSE_COMPOSER_PADDING,
        composerConsumesImeInsets = true,
        reserveLastItemSpace = true,
    )
    AppRoute.NewMessage -> MessageRouteLayout(
        MessageScrollOwner.NEW_MESSAGE_FORM,
        composerPinned = true,
        imeOwner = MessageImeOwner.COMPOSE_COMPOSER_PADDING,
        composerConsumesImeInsets = true,
        reserveLastItemSpace = true,
    )
    else -> error("not a message route")
}

internal fun Modifier.applyMessageComposerImeOwner(owner: MessageImeOwner): Modifier = when (owner) {
    MessageImeOwner.COMPOSE_COMPOSER_PADDING -> imePadding()
}

internal fun messageFixtureDisclosure(route: AppRoute, state: MessageUiState): String? {
    messageRouteLayout(route)
    return if (state is MessageUiState.Ready && state.origin == MessageOrigin.DESKTOP_FIXTURE) {
        FIXTURE_DISCLOSURE_TEXT
    } else {
        null
    }
}

internal fun messageTextLayoutContract(fontScale: Float): MessageTextLayout {
    require(fontScale in 1f..2f)
    return MessageTextLayout(softWrap = true, breakUnboundedText = true)
}

internal fun messageThreadLastItemIndex(itemCount: Int, disclosed: Boolean): Int? {
    require(itemCount >= 0)
    if (itemCount == 0) return null
    return itemCount - 1 + if (disclosed) 1 else 0
}

internal fun messageListLayoutContract(): MessageListLayoutSpec = MessageListLayoutSpec(
    rowMinHeightDp = 72,
    avatarSizeDp = 44,
    topActionSizeDp = 48,
    minimumTouchDp = 48,
    previewMaxLines = 2,
    statusMaxLines = 1,
    usesFab = false,
    openRows = true,
)

internal fun messageComposerLayoutContract(): MessageComposerLayoutSpec = MessageComposerLayoutSpec(
    sendTargetDp = 48,
    horizontal = true,
    pinnedAboveIme = true,
    capsule = true,
    circularSendAction = true,
    attachmentAction = false,
)

internal fun messageBubbleLayoutContract(direction: MessageBubbleDirection): MessageBubbleLayoutSpec =
    when (direction) {
        MessageBubbleDirection.INCOMING -> MessageBubbleLayoutSpec(
            maximumWidthFraction = 0.78f,
            bodyFontSizeSp = 16,
            bodyLineHeightSp = 22,
            topStartRadiusDp = 18,
            topEndRadiusDp = 18,
            bottomStartRadiusDp = 4,
            bottomEndRadiusDp = 18,
            colorRole = MessageBubbleColorRole.SURFACE_CONTAINER,
        )
        MessageBubbleDirection.OUTGOING -> MessageBubbleLayoutSpec(
            maximumWidthFraction = 0.78f,
            bodyFontSizeSp = 16,
            bodyLineHeightSp = 22,
            topStartRadiusDp = 18,
            topEndRadiusDp = 18,
            bottomStartRadiusDp = 18,
            bottomEndRadiusDp = 4,
            colorRole = MessageBubbleColorRole.INTERACTIVE_PRIMARY,
        )
    }

internal fun messageBubbleMaximumWidth(fontScale: Float): Float {
    require(fontScale in 1f..2f)
    return if (fontScale >= 1.5f) 0.90f else 0.78f
}

internal fun messageMotionContract(reducedMotion: Boolean): MessageMotionSpec = MessageMotionSpec(
    durationMillis = if (reducedMotion) 0 else 120,
    rootCrossfade = false,
)

internal fun messageContentAnimationContract(): MessageContentAnimationContract =
    MessageContentAnimationContract(
        durationMillis = 160,
        listAndBubbleProperties = setOf(MessageMotionProperty.ALPHA),
        animatePlacement = false,
        placementDurationMillis = null,
        sendActionProperties = setOf(
            MessageMotionProperty.ALPHA,
            MessageMotionProperty.COLOR,
            MessageMotionProperty.SCALE,
        ),
        decorativePulse = false,
    )

internal fun messageDraftPolicy(): MessageDraftPolicy = MessageDraftPolicy(
    preserveOnBack = true,
    clearAfterSubmission = false,
)

internal class MessageSubmissionGate {
    private var pending = false
    private var observedBusy = false

    fun tryBegin(state: MessageUiState, recipient: String, body: String): Boolean {
        if (pending || observedBusy || !canSendMessage(state, recipient, body)) return false
        pending = true
        return true
    }

    fun observeSourceBusy(busy: Boolean) {
        if (observedBusy && !busy) pending = false
        observedBusy = busy
    }

    val isPending: Boolean
        get() = pending
}

internal fun messageProjectionOwner(): SimOwnerId = checkNotNull(
    SimOwnerId.fromHmacSha256(ByteArray(32) { index -> (index + 1).toByte() }),
)
