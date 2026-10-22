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
    val configuration: ConfigurationSource = AndroidConfigurationSource(context, handles, inventory,
        readAutomaticSmsc = pool::automaticSmsc) {
        engine.refresh()
    }

    override fun close() {
        simControl.close()
        configuration.close()
        handles.clear()
    }
}
