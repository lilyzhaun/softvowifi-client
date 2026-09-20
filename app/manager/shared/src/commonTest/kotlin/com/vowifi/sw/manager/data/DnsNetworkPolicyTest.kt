package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DnsNetworkPolicyTest {
    @Test
    fun ordersValidatedWifiHandlesBeforeUnvalidatedThenSortsEachGroup() {
        val ordered = orderedWifiFallbackHandles(
            validatedHandles = listOf(9L, 2L),
            unvalidatedHandles = listOf(8L, 1L),
        )

        assertEquals(listOf(2L, 9L, 1L, 8L), ordered)
    }
}
