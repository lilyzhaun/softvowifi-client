package com.vowifi.sw.manager.data

import android.content.Context

class AndroidSimConfigurationSources(
    context: Context,
    engine: EngineControlSource,
    pool: AndroidEngineClientPool,
) : AutoCloseable {
    private val handles = OpaqueIccidHandleStore()
    private val inventory = AndroidSubscriptionInventory(context)
    val simControl: SimControlSource = AndroidSimControlSource(inventory, engine, handles)
    private val configurationSource = AndroidConfigurationSource(context, handles, inventory,
        readRuntimeFacts = pool::configurationObservation, applyNextCallPolicy = pool::applyCallPolicy) {
        engine.refresh()
    }
    val configuration: ConfigurationSource = configurationSource
    private val runtimeListener: (String, EngineRuntimeFactsChange) -> Unit = configurationSource::runtimeFactsChanged
    private val enginePool = pool

    init { pool.onRuntimeFactsChange = runtimeListener }

    override fun close() {
        if (enginePool.onRuntimeFactsChange === runtimeListener) enginePool.onRuntimeFactsChange = null
        simControl.close()
        configuration.close()
        handles.clear()
    }
}
