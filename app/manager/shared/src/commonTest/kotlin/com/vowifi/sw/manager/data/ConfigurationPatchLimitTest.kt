package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationPatchLimitTest {
    private val empty = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "")

    @Test fun rejectsEncodedPatchBeyondModuleLimit() {
        val failure = runCatching { ConfigurationCodec.encodeChanges(empty, empty.copy(dns = "a".repeat(65_536))) }.exceptionOrNull()
        assertTrue(failure is ConfigurationFormatException, "oversized encoded patch was accepted")
        assertEquals("PayloadTooLarge", failure::class.simpleName)
    }

    @Test fun unicodeAndJsonEscapesCountAsWireBytesNotCharacters() {
        for (value in listOf("é".repeat(33_000), "\n".repeat(33_000))) {
            assertTrue(runCatching { ConfigurationCodec.encodeChanges(empty, empty.copy(dns = value)) }.exceptionOrNull() is ConfigurationFormatException)
        }
    }

    @Test fun exactModuleLimitAndSparseNoChangeRemainValid() {
        val payload = ConfigurationCodec.encodeChanges(empty, empty.copy(dns = "a".repeat(65_536 - "{\"dns\":\"\"}".length)))
        assertEquals(65_536, payload.encodeToByteArray().size)
        assertEquals("{}", ConfigurationCodec.encodeChanges(empty, empty))
    }
}
