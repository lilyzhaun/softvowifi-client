package com.vowifi.sw.manager.ui

internal enum class MessageBubbleSurfaceMode {
    BODY_ONLY_WRAP_CONTENT_WITH_MAX,
}

internal enum class MessageBubbleMetadataPlacement {
    OUTSIDE,
}

internal enum class MessageBubbleCopyGesture {
    LONG_PRESS,
}

internal data class MessageBubbleInteractionSpec(
    val surfaceMode: MessageBubbleSurfaceMode,
    val metadataPlacement: MessageBubbleMetadataPlacement,
    val copyGesture: MessageBubbleCopyGesture,
)

internal const val MESSAGE_UNCOPYABLE_BODY = "内容不可显示"
internal const val MESSAGE_COPIED_FEEDBACK = "已复制"

internal fun messageBubbleInteractionContract(): MessageBubbleInteractionSpec = MessageBubbleInteractionSpec(
    surfaceMode = MessageBubbleSurfaceMode.BODY_ONLY_WRAP_CONTENT_WITH_MAX,
    metadataPlacement = MessageBubbleMetadataPlacement.OUTSIDE,
    copyGesture = MessageBubbleCopyGesture.LONG_PRESS,
)

internal fun messageCopyableBody(body: String): String? =
    if (body.isNotBlank() && body != MESSAGE_UNCOPYABLE_BODY) body else null
