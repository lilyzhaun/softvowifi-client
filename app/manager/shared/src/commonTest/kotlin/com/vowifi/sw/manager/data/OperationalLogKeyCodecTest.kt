package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationalLogKeyCodecTest {
    private val key = "a".repeat(64)
    @Test fun keysAreOpaqueAndRotationCountIsNotLimitedToCatalogSize() {
        val catalog = OperationalLogShellCodec.catalog("""{"ok":true,"rotatedCount":72,"files":[{"key":"$key","name":"engine.log","suffix":"0001"}]}""")
        assertEquals(72, catalog.rotatedCount)
        assertEquals(key, catalog.files.single().id)
        assertEquals(key, OperationalLogShellCodec.read("""{"ok":true,"key":"$key","truncated":false,"lines":[]}""").id)
    }
    @Test fun invalidKeysNamesCountsAndLegacyIdsAreRejected() {
        for (bad in listOf("f1", "../", "A".repeat(64), "$key ", "a".repeat(63))) {
            assertFailsWith<OperationalLogFormatException> {
                OperationalLogShellCodec.catalog("""{"ok":true,"rotatedCount":0,"files":[{"key":"$bad","name":"engine.log","suffix":"0001"}]}""")
            }
        }
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.catalog("""{"ok":true,"rotatedCount":0,"files":[{"key":"$key","name":"../engine.log","suffix":"0001"}]}""")
        }
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.read("""{"ok":true,"id":"f1","truncated":false,"lines":[]}""")
        }
    }
}
