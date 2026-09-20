package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AndroidCarrierIdTest {
    @Test
    fun projectTreatsUnknownAndZeroAsAbsent() {
        assertNull(AndroidCarrierId.project(AndroidCarrierId.UNKNOWN))
        assertNull(AndroidCarrierId.project(0))
        assertEquals(AndroidCarrierId.UNKNOWN, -1)
    }

    @Test
    fun projectKeepsPositiveCarrierId() {
        assertEquals(15, AndroidCarrierId.project(15))
        assertEquals(1, AndroidCarrierId.project(1))
    }

    @Test
    fun projectDoesNotInferFromOperatorOrPlmn() {
        assertNull(AndroidCarrierId.project(AndroidCarrierId.UNKNOWN))
        assertEquals("AndroidCarrierId", AndroidCarrierId.toString())
        assertFalse(AndroidCarrierId.toString().contains("15"))
        assertFalse(AndroidCarrierId.toString().contains("operator"))
    }
}
