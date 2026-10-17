package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** U4: group identity comes from the catalog, never the displayed four-digit suffix. */
class OperationalLogGroupedCatalogTest {
    @Test fun catalogPreservesSameOwnerPairsAndSeparatesMatchingSuffixes() {
        val first = "e".repeat(64)
        val second = "f".repeat(64)
        val files = OperationalLogShellCodec.catalog(catalog(
            file("a", "engine.log", JsonPrimitive(first)), file("b", "launcher.log", JsonPrimitive(first)),
            file("c", "engine.log", JsonPrimitive(second)), file("d", "launcher.log", JsonPrimitive(second)),
        )).files
        assertEquals(listOf(first, first, second, second), files.map { it.groupKey })
        assertEquals(listOf("a", "b", "c", "d").map { it.repeat(64) }, files.map { it.id })
        assertEquals(listOf("0001"), files.map { it.suffix }.distinct())
    }

    @Test fun oldCatalogAndLegacyListKeepTheirOriginalIdentityWithoutInventingGroups() {
        val old = OperationalLogShellCodec.catalog(catalog(file("a", "engine.log", null))).files.single()
        assertEquals("a".repeat(64), old.id); assertNull(old.groupKey)
        val legacy = OperationalLogShellCodec.list(
            """{"ok":true,"rotatedCount":0,"files":[{"id":"f1","name":"launcher.log","suffix":"0001"}]}""",
        ).files.single()
        assertEquals("f1", legacy.id); assertNull(legacy.groupKey)
        assertNull(OperationalLogShellCodec.catalog(catalog(file("a", "engine.log", JsonNull))).files.single().groupKey)
    }

    @Test fun invalidGroupValuesAreRejectedInsteadOfSilentlyDiscarded() {
        for (bad in listOf(JsonPrimitive("a".repeat(63)), JsonPrimitive("A".repeat(64)),
            JsonPrimitive("../synthetic"), JsonPrimitive("a".repeat(64) + " "), JsonPrimitive(7))) {
            assertFailsWith<OperationalLogFormatException> {
                OperationalLogShellCodec.catalog(catalog(file("a", "engine.log", bad)))
            }
        }
    }

    @Test fun oneGroupCannotClaimTheSameOriginTwiceOrConflictingDisplaySuffixes() {
        val group = JsonPrimitive("e".repeat(64))
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.catalog(catalog(file("a", "engine.log", group), file("b", "engine.log", group)))
        }
        val conflictingSuffix = kotlinx.serialization.json.JsonObject(
            file("b", "launcher.log", group) + ("suffix" to JsonPrimitive("0002")),
        )
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.catalog(catalog(file("a", "engine.log", group), conflictingSuffix))
        }
    }

    private fun file(key: String, name: String, group: kotlinx.serialization.json.JsonElement?) = buildJsonObject {
        put("key", key.repeat(64)); put("name", name); put("suffix", "0001")
        if (group != null) put("groupKey", group)
    }

    private fun catalog(vararg files: kotlinx.serialization.json.JsonObject): String = buildJsonObject {
        put("ok", true); put("rotatedCount", 0); put("files", JsonArray(files.toList()))
    }.toString()
}
