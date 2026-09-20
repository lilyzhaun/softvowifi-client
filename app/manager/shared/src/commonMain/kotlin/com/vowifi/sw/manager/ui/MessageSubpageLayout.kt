package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class MessageSubpageRegion {
    THREAD_TIMELINE,
    NEW_MESSAGE_FORM,
    MISSING_THREAD_STATE,
    COMPOSER,
}

internal enum class MessageContentAnchor {
    TOP,
    BOTTOM,
}

internal data class MessageSubpageInsetOwnership(
    val topConsumers: Set<MessageSubpageRegion>,
    val bottomConsumers: Set<MessageSubpageRegion>,
    val imeOwner: MessageSubpageRegion,
)

internal fun messageSubpageInsetOwnership(): MessageSubpageInsetOwnership = MessageSubpageInsetOwnership(
    topConsumers = setOf(
        MessageSubpageRegion.THREAD_TIMELINE,
        MessageSubpageRegion.NEW_MESSAGE_FORM,
        MessageSubpageRegion.MISSING_THREAD_STATE,
    ),
    bottomConsumers = setOf(MessageSubpageRegion.MISSING_THREAD_STATE, MessageSubpageRegion.COMPOSER),
    imeOwner = MessageSubpageRegion.COMPOSER,
)

internal data class MessageSubpageLayoutSpec(
    val filledViewports: Set<MessageSubpageRegion>,
    val anchors: Map<MessageSubpageRegion, MessageContentAnchor>,
    val chronologicalThreadItems: Boolean,
)

internal fun messageSubpageLayoutContract(): MessageSubpageLayoutSpec = MessageSubpageLayoutSpec(
    filledViewports = setOf(MessageSubpageRegion.THREAD_TIMELINE, MessageSubpageRegion.NEW_MESSAGE_FORM),
    anchors = mapOf(
        MessageSubpageRegion.THREAD_TIMELINE to MessageContentAnchor.BOTTOM,
        MessageSubpageRegion.NEW_MESSAGE_FORM to MessageContentAnchor.TOP,
    ),
    chronologicalThreadItems = true,
)

internal data class MessageSubpageSpacing(
    val contentTopGapDp: Int,
    val threadItemGapDp: Int,
    val threadBottomGapDp: Int,
)

internal fun messageSubpageSpacing(): MessageSubpageSpacing = MessageSubpageSpacing(
    contentTopGapDp = 8,
    threadItemGapDp = 12,
    threadBottomGapDp = 12,
)

internal fun messageComposerBottomPadding(sceneBottomInset: Dp): Dp =
    MESSAGE_COMPOSER_BASE_PADDING_DP.dp + sceneBottomInset

private const val MESSAGE_COMPOSER_BASE_PADDING_DP = 8
