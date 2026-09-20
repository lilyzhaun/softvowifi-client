@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalLogSelectionTest {
    @Test fun latestSelectionIsVisibleWhileOldReadIsBlocked() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val command = LogCommand { text ->
            when {
                text.endsWith("catalog") -> result("""{"ok":false,"code":"USAGE","message":"unsupported"}""", 64)
                text.endsWith("list") -> result("""{"ok":true,"rotatedCount":0,"files":[{"id":"f1","name":"engine.log","suffix":"0001"},{"id":"f2","name":"engine.log","suffix":"0002"},{"id":"f3","name":"engine.log","suffix":"0003"}]}""")
                text.endsWith("f1") -> {
                    started.complete(Unit)
                    release.await()
                    result("""{"ok":true,"id":"f1","truncated":false,"lines":[]}""")
                }
                else -> result("""{"ok":true,"id":"${text.substringAfterLast(' ')}","truncated":false,"lines":[]}""")
            }
        }
        val source = AndroidOperationalLogSource(command, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            withTimeout(3000) { started.await() }
            source.selectFile("f2")
            source.selectFile("f3")
            val state = source.state.value
            assertTrue("selection must keep the catalog visible, actual=$state", state is OperationalLogUiState.Ready)
            assertEquals("f3", (state as OperationalLogUiState.Ready).selectedFileId)
        } finally {
            source.close()
            release.complete(Unit)
        }
    }

    private fun result(output: String, exit: Int = 0) = AndroidSuResult(false, exit, output, false)
}
