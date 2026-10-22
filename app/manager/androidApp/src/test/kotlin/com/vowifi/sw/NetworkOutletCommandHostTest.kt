@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NetworkOutletCommandShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class NetworkOutletCommandHostTest {
    @Test fun productionReaderUsesOnlyTheFixedReadOnlyEngineCommand() = runBlocking {
        NetworkOutletCommandShadow.result = AndroidSuResult(false, 0,
            """{"ok":true,"version":3,"interfaces":[{"name":"Meta","up":true,"hasAddress":true,"kind":"tun","addresses":["198.18.0.1"]}]}""", false)
        val outlets = readAndroidNetworkOutlets()
        assertTrue(outlets.supported)
        assertEquals(listOf("Meta"), outlets.interfaces.map { it.name })
        assertEquals(listOf("198.18.0.1"), outlets.interfaces.single().addresses)
    }

    @Test fun timeoutOverflowAndFailureNeverBecomeEmptyInventorySuccess() = runBlocking {
        for (result in listOf(AndroidSuResult(true, -1, "", false), AndroidSuResult(false, 0, "", true),
            AndroidSuResult(false, 1, "untrusted failure must not be shown", false))) {
            NetworkOutletCommandShadow.result = result
            val outlets = readAndroidNetworkOutlets()
            assertFalse(outlets.supported)
            assertTrue(outlets.issue != null)
            assertFalse(outlets.issue.orEmpty().contains("untrusted"))
        }
    }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class NetworkOutletCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        assertEquals(NETWORK_OUTLETS_COMMAND, command)
        return result
    }
    companion object { @Volatile var result = AndroidSuResult(false, 1, "", false) }
}
