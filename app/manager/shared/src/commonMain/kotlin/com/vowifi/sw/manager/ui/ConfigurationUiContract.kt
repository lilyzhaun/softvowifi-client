package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.systemSubscriptionSummary
import com.vowifi.sw.manager.model.ConfigurationInstance

internal data class ConfigurationFact(val title: String, val summary: String)

internal fun configurationSystemSubscriptionFact(instance: ConfigurationInstance): ConfigurationFact? =
    systemSubscriptionSummary(instance.systemSubscription)?.let { ConfigurationFact("系统订阅", it) }
