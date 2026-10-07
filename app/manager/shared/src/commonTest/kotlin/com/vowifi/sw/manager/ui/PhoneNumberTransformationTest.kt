package com.vowifi.sw.manager.ui

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.*

class PhoneNumberTransformationTest {
    @Test fun formattedDraftKeepsEveryCursorPositionAndDoesNotChangeOtherNumbers() {
        val number = "+12025550123"
        for (length in 0..number.length) {
            val raw = number.take(length)
            val transformed = PhoneNumberTransformation.filter(AnnotatedString(raw))
            assertEquals(raw, transformed.text.text.replace(" ", ""))
            for (offset in 0..raw.length) assertEquals(offset,
                transformed.offsetMapping.transformedToOriginal(transformed.offsetMapping.originalToTransformed(offset)))
            for (offset in 0..transformed.text.length) assertTrue(transformed.offsetMapping.transformedToOriginal(offset) in 0..raw.length)
        }
        assertEquals("+1 202 555 0123", PhoneNumberTransformation.filter(AnnotatedString(number)).text.text)
        for (raw in listOf("+64215550123", "123*#", "+12345678901234567890"))
            assertEquals(raw, PhoneNumberTransformation.filter(AnnotatedString(raw)).text.text)
    }
}
