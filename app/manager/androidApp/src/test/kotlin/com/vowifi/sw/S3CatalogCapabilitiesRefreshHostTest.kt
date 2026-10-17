package com.vowifi.sw

import com.vowifi.sw.manager.data.EngineClientCapabilitiesCodec
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class S3CatalogCapabilitiesRefreshHostTest {
    @Test fun registeredRuntimeWithCatalogMetadataCompletesWithoutAFalseEngineFailure() = runBlocking {
        EngineCapabilitiesControlFixture { EngineClientCapabilitiesCodec.decode(frame("catalog")) }.use { fixture ->
            assertEquals("REGISTERED", fixture.row(EngineCapabilitiesControlFixture.A).runtime?.ims)
            val read = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            fixture.dispatcher.runCurrent()
            assertEquals(RefreshResult.Completed, withTimeout(3000) { read.await() })
            val result = fixture.row(EngineCapabilitiesControlFixture.A).clientCapabilities
            assertTrue(result is ClientCapabilitiesResult.Ready)
            val metadata = (result as ClientCapabilitiesResult.Ready).capabilities.configMetadata
            assertEquals(MetadataSource.Catalog, metadata.single { it.field == MetadataField.SipSecurity }.source)
            assertTrue(fixture.commands.all { it.first == "list" })
            assertTrue(fixture.requests.all { it.second == "getStatus" })
        }
    }

    @Test fun malformedMetadataStillFailsTheRealRefreshRatherThanBecomingSuccessful() = runBlocking {
        EngineCapabilitiesControlFixture { EngineClientCapabilitiesCodec.decode(frame("home_mcc")) }.use { fixture ->
            val read = async(start = CoroutineStart.UNDISPATCHED) { fixture.source.refreshAndAwait() }
            fixture.dispatcher.runCurrent()
            assertTrue(withTimeout(3000) { read.await() } is RefreshResult.Failed)
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Invalid),
                fixture.row(EngineCapabilitiesControlFixture.A).clientCapabilities)
        }
    }

    private fun frame(source: String): EngineProtocolFrame.Response {
        val original = EngineCapabilitiesHostFixture.metadata(EngineCapabilitiesHostFixture.KEY)
        val metadata = original.getValue("configMetadata").jsonArray.map { element ->
            val item = element.jsonObject
            if (item.getValue("field").jsonPrimitive.content == "imsSipSecurity")
                JsonObject(item + ("source" to JsonPrimitive(source))) else item
        }
        return EngineProtocolFrame.Response(1,
            JsonObject(original + ("configMetadata" to JsonArray(metadata))), null)
    }
}
