package com.vowifi.sw.manager.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConfigurationRuntimeRetentionTest {
    @Test fun compositionRecreationRetainsMemoryButNeverWritesSavedStateOrReplays() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val memory = ConfigurationDraftMemory()
        val registry = SaveableStateRegistry(null) { true }
        val firstSource = Source()
        lateinit var store: ConfigurationDraftStore
        fun compose(source: Source, retained: ConfigurationDraftMemory) =
            Composition(NoNodes(), recomposer).also { composition ->
                composition.setContent {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        store = rememberConfigurationDraftStore(source, retained, source.state.value)
                    }
                }
            }
        val first = compose(firstSource, memory)
        val draft = store.draftFor(instance("sim-1"))
        draft.dns.edit { append("synthetic.invalid") }
        draft.submit { _, _, _ -> ConfigurationApplyAcceptance.Accepted }
        assertTrue(registry.performSave().isEmpty())
        first.dispose()
        val secondSource = Source()
        val second = compose(secondSource, memory)
        assertSame(ConfigurationUiState.Loading, secondSource.state.value)
        val restored = store.draftFor(instance("sim-2"))
        assertEquals("synthetic.invalid", restored.dns.text.toString())
        assertEquals(ConfigurationEditStatus.Unconfirmed, restored.status)
        assertEquals(0, secondSource.calls)
        store.reconcile(ConfigurationUiState.Failure("无法读取配置"))
        assertTrue(restored.dirty)
        second.dispose()
        val newProcess = compose(Source(), ConfigurationDraftMemory())
        assertEquals("", store.draftFor(instance("sim-1")).dns.text.toString())
        newProcess.dispose()
        recomposer.close()
    }

    private fun instance(handle: String) = ConfigurationInstance(
        ConfigurationInstanceId(handle), "实例 ••••", ConfigurationLifecycle.STOPPED,
        ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01",
        draftOwnerId = SimOwnerId.fromHmacSha256(ByteArray(32) { 3 }),
    )

    private class Source : ConfigurationSource {
        override val state = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
        var calls = 0
        override fun refresh() = Unit
        override fun submit(instanceId: ConfigurationInstanceId, overrides: ConfigurationOverrides,
            onComplete: (ConfigurationApplyOutcome) -> Unit): ConfigurationApplyAcceptance {
            calls++
            return ConfigurationApplyAcceptance.Unavailable
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
