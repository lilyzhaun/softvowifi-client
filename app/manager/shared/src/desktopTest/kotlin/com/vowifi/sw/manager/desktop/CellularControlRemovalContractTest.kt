package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.model.ConfigurationInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellularControlRemovalContractTest {
    @Test
    fun appContractNoLongerExposesModemControl() {
        assertFalse(ConfigurationSource::class.java.methods.any { it.name == "submitCellular" })
        assertFalse(ConfigurationInstance::class.java.declaredFields.any { it.name == "cellularControl" })
    }

    @Test
    fun productionEntryAndSourcesHaveNoRetiredControlWiring() {
        val root = locateManager()
        val shared = root.resolve("shared/src")
        val files = listOf(
            "commonMain/kotlin/com/vowifi/sw/manager/SoftVoWiFiDestinationContent.kt",
            "commonMain/kotlin/com/vowifi/sw/manager/ui/ConfigurationEditor.kt",
            "commonMain/kotlin/com/vowifi/sw/manager/ui/ConfigurationScreen.kt",
            "commonMain/kotlin/com/vowifi/sw/manager/ui/SettingsSubpages.kt",
            "androidMain/kotlin/com/vowifi/sw/manager/data/AndroidConfigurationSource.kt",
            "androidMain/kotlin/com/vowifi/sw/manager/data/AndroidConfigurationCatalog.kt",
        )
        files.forEach { name ->
            val file = shared.resolve(name)
            assertTrue(file.isFile, "missing production source $name")
            val text = file.readText()
            listOf("CellularNetworkControls", "submitCellular", "cellularCommands", "cellularFor", "onCellularSubmit")
                .forEach { forbidden -> assertFalse(text.contains(forbidden), "$name retains $forbidden") }
        }
        assertFalse(shared.resolve("commonMain/kotlin/com/vowifi/sw/manager/ui/CellularNetworkControls.kt").exists())
        assertFalse(shared.resolve("androidMain/kotlin/com/vowifi/sw/manager/data/AndroidCellularCommands.kt").exists())
    }

    private fun locateManager(): File {
        val here = File("").absoluteFile
        return listOf(here, File(here, ".."), File(here, "../.."), File(here, "app/manager"))
            .first { it.resolve("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/ConfigurationEditor.kt").isFile }
    }
}
