package com.vowifi.sw.manager.ui

enum class AppDestination(
    val label: String,
    val refreshDescription: String,
    val content: AppDestinationContent,
    val refreshTargets: Set<RefreshTarget>,
) {
    HOME(
        "首页",
        "刷新首页状态",
        AppDestinationContent.HOME,
        setOf(
            RefreshTarget.OBSERVER,
            RefreshTarget.ENGINE,
            RefreshTarget.SIM_CONTROL,
            RefreshTarget.CONFIGURATION,
            RefreshTarget.ENVIRONMENT_DIAGNOSTICS,
        ),
    ),
    CALLS(
        "通话",
        "刷新通话状态",
        AppDestinationContent.CALLS,
        setOf(RefreshTarget.CALLS),
    ),
    MESSAGES(
        "短信",
        "刷新消息",
        AppDestinationContent.MESSAGES,
        setOf(RefreshTarget.MESSAGES),
    ),
    SETTINGS(
        "设置",
        "刷新设置状态",
        AppDestinationContent.SETTINGS,
        setOf(
            RefreshTarget.CONFIGURATION,
            RefreshTarget.SIM_CONTROL,
            RefreshTarget.ENVIRONMENT_DIAGNOSTICS,
            RefreshTarget.OPERATIONAL_LOGS,
        ),
    );

    companion object {
        fun fromAlias(value: String?): AppDestination? = when (value) {
            "status", "home" -> HOME
            "calls" -> CALLS
            "messages" -> MESSAGES
            "configuration", "diagnostics", "settings" -> SETTINGS
            else -> null
        }
    }
}

enum class AppDestinationContent {
    HOME,
    CALLS,
    MESSAGES,
    SETTINGS,
}

enum class RefreshTarget {
    OBSERVER,
    ENGINE,
    CONFIGURATION,
    MESSAGES,
    ENVIRONMENT_DIAGNOSTICS,
    OPERATIONAL_LOGS,
    SIM_CONTROL,
    CALLS,
}
