package com.vowifi.sw.manager.ui

internal sealed interface MessageThreadScrollDecision {
    data object WaitForLayout : MessageThreadScrollDecision
    data object KeepCurrent : MessageThreadScrollDecision
    data class ScrollTo(val index: Int) : MessageThreadScrollDecision
}

internal enum class MessageMetadataRole {
    STATUS,
    NOTE,
    TIMESTAMP,
}

internal data class MessageMetadataLine(
    val role: MessageMetadataRole,
    val text: String,
) {
    init {
        require(text.isNotBlank())
        require(text.length <= MAX_METADATA_LINE_CHARACTERS)
        require(text.none(Char::isISOControl))
    }
}

internal data class MessageThreadMetadataLayout(
    val independentLines: Boolean,
    val softWrap: Boolean,
    val clipCriticalText: Boolean,
    val wholeSemanticPhrases: List<String>,
    val maximumCharactersPerLine: Int,
)

internal fun messageThreadInitialScrollDecision(
    lastItemIndex: Int,
    visibleItemIndices: List<Int>,
): MessageThreadScrollDecision {
    require(lastItemIndex >= 0)
    require(visibleItemIndices.all { it >= 0 })
    return when {
        visibleItemIndices.isEmpty() -> MessageThreadScrollDecision.WaitForLayout
        lastItemIndex in visibleItemIndices -> MessageThreadScrollDecision.KeepCurrent
        else -> MessageThreadScrollDecision.ScrollTo(lastItemIndex)
    }
}

internal fun messageOutgoingMetadataLines(
    status: String,
    note: String?,
    timestamp: String,
): List<MessageMetadataLine> = buildList {
    add(MessageMetadataLine(MessageMetadataRole.STATUS, status))
    note?.let { add(MessageMetadataLine(MessageMetadataRole.NOTE, it)) }
    add(MessageMetadataLine(MessageMetadataRole.TIMESTAMP, timestamp))
}

internal fun messageThreadMetadataLayoutContract(fontScale: Float): MessageThreadMetadataLayout {
    require(fontScale.isFinite() && fontScale > 0f)
    return MessageThreadMetadataLayout(
        independentLines = true,
        softWrap = true,
        clipCriticalText = false,
        wholeSemanticPhrases = listOf("状态报告", "已送达", "本机收到 MT 短信"),
        maximumCharactersPerLine = MAX_METADATA_LINE_CHARACTERS,
    )
}

internal const val WORD_JOINER: Char = '\u2060'

internal fun messageMetadataDisplayText(line: MessageMetadataLine): String =
    CRITICAL_METADATA_PHRASES.fold(line.text) { text, phrase ->
        text.replace(phrase, phrase.toList().joinToString(WORD_JOINER.toString()))
    }

internal fun stripMessageDisplayJoiners(text: String): String = text.replace(WORD_JOINER.toString(), "")

private val CRITICAL_METADATA_PHRASES = listOf("状态报告", "已送达", "本机收到 MT 短信")

private const val MAX_METADATA_LINE_CHARACTERS = 40
