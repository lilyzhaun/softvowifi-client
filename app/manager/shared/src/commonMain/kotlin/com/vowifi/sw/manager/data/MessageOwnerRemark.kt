package com.vowifi.sw.manager.data

class InvalidMessageOwnerRemark : Exception("备注最多40个字符，不能包含控制字符")

class MessageOwnerRemark private constructor(val value: String) {
    override fun toString(): String = "MessageOwnerRemark"
    companion object {
        fun parse(input: String): MessageOwnerRemark {
            val value = input.trim()
            if (input.any { it.isISOControl() } || value.codePointCount(0, value.length) > 40) {
                throw InvalidMessageOwnerRemark()
            }
            return MessageOwnerRemark(value)
        }
    }
}

data class MessageOwnerLabel(val ordinal: Long, val remark: String, val defaultName: String? = null) {
    override fun toString(): String = "MessageOwnerLabel"
}

sealed interface MessageRemarkSaveResult {
    data object Saved : MessageRemarkSaveResult
    data object Failed : MessageRemarkSaveResult
    data object Invalid : MessageRemarkSaveResult
}
