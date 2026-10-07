package com.vowifi.sw.manager.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Only presentation changes; editing, selection, clipboard and dialing retain the raw draft. */
internal object PhoneNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (!raw.startsWith("+1") || raw.length > 12 || raw.drop(1).any { !it.isDigit() })
            return TransformedText(text, OffsetMapping.Identity)
        val breaks = listOf(2, 5, 8).filter { it < raw.length }
        val formatted = buildString {
            raw.forEachIndexed { index, char -> if (index in breaks) append(' '); append(char) }
        }
        return TransformedText(AnnotatedString(formatted), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + breaks.count { it <= offset }
            override fun transformedToOriginal(offset: Int) = offset - breaks.withIndex().count { it.value + it.index < offset }
        })
    }
}
