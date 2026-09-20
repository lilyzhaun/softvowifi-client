package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpaqueIccidHandleStoreTest {
    @Test
    fun assignsSequentialOpaqueHandlesOnce() {
        val store = OpaqueIccidHandleStore()

        val first = store.handleFor(SIM_KEY_A)
        val second = store.handleFor(SIM_KEY_B)

        assertEquals("sim-1", first.value)
        assertEquals("sim-2", second.value)
        assertEquals(first, store.handleFor(SIM_KEY_A))
        assertNotEquals(first, second)
    }

    @Test
    fun reusesHandleAfterDisappearAndReappear() {
        val store = OpaqueIccidHandleStore()
        val original = store.handleFor(SIM_KEY_A)
        store.handleFor(SIM_KEY_B)

        val restored = store.handleFor(SIM_KEY_A)

        assertEquals(original, restored)
        assertEquals("sim-1", restored.value)
        assertEquals(SIM_KEY_A, store.keyFor(restored))
    }

    @Test
    fun neverReusesHandleForADifferentKey() {
        val store = OpaqueIccidHandleStore()
        val first = store.handleFor(SIM_KEY_A)
        store.handleFor(SIM_KEY_B)

        val third = store.handleFor(SIM_KEY_C)

        assertEquals("sim-3", third.value)
        assertNotEquals(first, third)
        assertEquals(SIM_KEY_C, store.keyFor(third))
        assertEquals(SIM_KEY_A, store.keyFor(first))
    }

    @Test
    fun reverseLookupReturnsOriginalKey() {
        val store = OpaqueIccidHandleStore()
        val handle = store.handleFor(SIM_ICCID_19)

        assertEquals(SIM_ICCID_19, store.keyFor(handle))
        assertNull(store.keyFor(store.handleFor(SIM_KEY_A).copy(value = "sim-99")))
    }

    @Test
    fun handleDoesNotContainKeyTailOrHashEncoding() {
        val store = OpaqueIccidHandleStore()
        val handle = store.handleFor(SIM_ICCID_19)

        assertTrue(HANDLE_PATTERN.matches(handle.value))
        assertFalse(handle.value.contains(SIM_ICCID_19))
        assertFalse(handle.value.contains(SIM_TAIL_FOUR))
        assertFalse(handle.value.contains("="))
        assertFalse(handle.value.contains("/"))
        assertPrivate(handle.value)
    }

    @Test
    fun clearIsTheOnlyResetAndRestartsOrdinals() {
        val store = OpaqueIccidHandleStore()
        val previous = store.handleFor(SIM_KEY_A)
        store.handleFor(SIM_KEY_B)

        store.clear()

        assertNull(store.keyFor(previous))
        val next = store.handleFor(SIM_KEY_C)
        assertEquals("sim-1", next.value)
        assertEquals(SIM_KEY_C, store.keyFor(next))
        assertEquals("sim-2", store.handleFor(SIM_KEY_A).value)
    }
}

private val HANDLE_PATTERN = Regex("""^sim-[1-9]\d*$""")
