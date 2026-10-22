package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationNetworkInterface
import com.vowifi.sw.manager.model.ConfigurationTransport

internal data class ConfigurationNetworkChoice(val transport: ConfigurationTransport, val label: String)

private fun ConfigurationNetworkOutlets.availableInterfaces(): List<ConfigurationNetworkInterface> =
    if (!supported || issue != null) emptyList()
    else interfaces.filter { it.up && it.hasAddress && it.kind != "loopback" }.sortedBy { it.name }

internal fun configurationNetworkChoices(outlets: ConfigurationNetworkOutlets): List<ConfigurationNetworkChoice> = buildList {
    for (mode in listOf(ConfigurationTransport.WIFI, ConfigurationTransport.SIM1, ConfigurationTransport.SIM2)) {
        add(ConfigurationNetworkChoice(mode, transportLabel(mode)))
    }
    if (outlets.supported && outlets.issue == null) {
        for (iface in outlets.availableInterfaces()) {
            add(ConfigurationNetworkChoice(ConfigurationTransport.Interface(iface.name), interfaceLabel(iface)))
        }
        add(ConfigurationNetworkChoice(ConfigurationTransport.SYSTEM_ROUTE, "系统策略路由（透明代理）"))
    }
    // Keep existing auto configurations editable without moving the three pinned modes.
    add(ConfigurationNetworkChoice(ConfigurationTransport.AUTO, transportLabel(ConfigurationTransport.AUTO)))
}

private fun interfaceLabel(iface: ConfigurationNetworkInterface): String =
    (listOf(iface.name, "UP") + iface.addresses.joinToString(" / ").ifEmpty { null }).filterNotNull().joinToString(" · ")

internal fun configurationNetworkSummary(value: ConfigurationTransport, outlets: ConfigurationNetworkOutlets): String {
    val unavailable = value is ConfigurationTransport.Interface && outlets.availableInterfaces().none { it.name == value.interfaceName }
    return listOfNotNull(
        outlets.issue,
        if (unavailable) "已保存接口 ${transportLabel(value)} 未出现在当前可用列表，请刷新配置；不会自动改选" else null,
        when (value) {
            ConfigurationTransport.SYSTEM_ROUTE -> "遵循系统策略路由，不强绑物理接口；代理须接管 Root 进程并支持 UDP。"
            is ConfigurationTransport.Interface -> "按完整接口名选择；裸 TUN 还需代理已配置同接口路由，不可用时不回退。"
            else -> null
        },
        "SWu 隧道的外层出口；刷新页面可更新可用接口，不定时扫描。",
    ).joinToString("\n")
}

internal fun transportLabel(value: ConfigurationTransport): String = when (value) {
    ConfigurationTransport.WIFI -> "Wi-Fi"
    ConfigurationTransport.SIM1 -> "SIM 1 数据"
    ConfigurationTransport.SIM2 -> "SIM 2 数据"
    ConfigurationTransport.AUTO -> "自动"
    ConfigurationTransport.SYSTEM_ROUTE -> "系统策略路由（透明代理）"
    is ConfigurationTransport.Interface -> value.interfaceName
}
