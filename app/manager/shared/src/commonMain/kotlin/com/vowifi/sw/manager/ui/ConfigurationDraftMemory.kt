package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState

class ConfigurationDraftMemory {
    internal val entries = mutableMapOf<SimOwnerId, ConfigurationRetainedDraft>()
    override fun toString(): String = "ConfigurationDraftMemory"
}

internal class ConfigurationRetainedDraft(
    val current: ConfigurationDraftValues,
    val baseline: ConfigurationDraftValues,
    val unconfirmed: Boolean,
) {
    override fun toString(): String = "ConfigurationRetainedDraft"
}

internal class ConfigurationDraftStore(private val memory: ConfigurationDraftMemory = ConfigurationDraftMemory()) {
    private val stable = mutableMapOf<SimOwnerId, ConfigurationEditorDraft>()
    private val ephemeral = mutableMapOf<ConfigurationInstanceId, ConfigurationEditorDraft>()
    val hasUnconfirmed: Boolean get() = (stable.values + ephemeral.values).any { it.status == ConfigurationEditStatus.Unconfirmed }

    fun draftFor(instance: ConfigurationInstance): ConfigurationEditorDraft {
        val owner = instance.draftOwnerId
        return if (owner == null) {
            ephemeral.getOrPut(instance.instanceId) { ConfigurationEditorDraft(instance) }
        } else {
            stable.getOrPut(owner) { ConfigurationEditorDraft(instance, memory.entries[owner]) }
        }
    }

    fun reconcile(state: ConfigurationUiState) {
        if (state !is ConfigurationUiState.Ready) return
        state.instances.forEach { draftFor(it).syncFrom(it) }
    }

    fun retain() {
        stable.forEach { (owner, draft) -> memory.entries[owner] = draft.retained() }
    }
}

@Composable
internal fun rememberConfigurationDraftStore(
    source: ConfigurationSource,
    memory: ConfigurationDraftMemory,
    state: ConfigurationUiState,
): ConfigurationDraftStore {
    val previous = remember(memory) { arrayOfNulls<ConfigurationDraftStore>(1) }
    val store = remember(source, memory) {
        previous[0]?.retain()
        ConfigurationDraftStore(memory).also { previous[0] = it }
    }
    DisposableEffect(store) { onDispose { store.retain() } }
    LaunchedEffect(store, state) { store.reconcile(state) }
    return store
}
