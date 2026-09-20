package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimOwnerIdTest {
    @Test
    fun parseAcceptsExactV1UrlSafeBodyAndRejectsRawOrSimN() {
        val parsed = SimOwnerId.parse(OWNER_WIRE_A)
        assertNotNull(parsed)
        assertEquals(OWNER_WIRE_A, parsed.wire())
        assertNull(SimOwnerId.parse("sim-1"))
        assertNull(SimOwnerId.parse("sim-N"))
        assertNull(SimOwnerId.parse("8986001"))
        assertNull(SimOwnerId.parse(""))
        assertNull(SimOwnerId.parse("${SimOwnerId.PREFIX}short"))
        assertNull(SimOwnerId.parse("${SimOwnerId.PREFIX}${BODY_A}="))
        assertNull(SimOwnerId.parse("${SimOwnerId.PREFIX}${BODY_A.replace('A', '+')}"))
    }

    @Test
    fun fromHmacSha256RequiresThirtyTwoBytesAndUsesUrlSafeNoPad() {
        val owner = SimOwnerId.fromHmacSha256(ByteArray(32))
        assertNotNull(owner)
        assertEquals(OWNER_WIRE_A, owner.wire())
        assertNull(SimOwnerId.fromHmacSha256(ByteArray(31)))
        assertNull(SimOwnerId.fromHmacSha256(ByteArray(33)))
    }

    @Test
    fun toStringAndEqualityDoNotExposeWireValue() {
        val left = SimOwnerId.parse(OWNER_WIRE_A)
        val right = SimOwnerId.parse(OWNER_WIRE_A)
        val other = SimOwnerId.parse(OWNER_WIRE_B)
        assertNotNull(left)
        assertNotNull(right)
        assertNotNull(other)
        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertNotEquals(left, other)
        assertEquals("SimOwnerId", left.toString())
        assertFalse(left.toString().contains(OWNER_WIRE_A))
        assertFalse(left.toString().contains(BODY_A))
        assertFalse(left.toString().startsWith("sim-"))
        assertPrivate(left.toString())
    }
}

internal const val BODY_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
internal const val BODY_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
internal const val OWNER_WIRE_A = "${SimOwnerId.PREFIX}$BODY_A"
internal const val OWNER_WIRE_B = "${SimOwnerId.PREFIX}$BODY_B"
internal val OWNER_A: SimOwnerId = requireNotNull(SimOwnerId.parse(OWNER_WIRE_A))
internal val OWNER_B: SimOwnerId = requireNotNull(SimOwnerId.parse(OWNER_WIRE_B))
