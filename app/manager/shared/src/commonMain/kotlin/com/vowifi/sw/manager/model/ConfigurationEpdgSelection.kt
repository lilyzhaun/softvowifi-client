package com.vowifi.sw.manager.model

enum class ConfigurationEpdgKind(val wire: String, val label: String) {
    ADDRESS("address", "指定地址"),
    PLMN("plmn", "指定 PLMN"),
    HOME_PLMN("home-plmn", "归属 PLMN"),
    REGISTERED_PLMN("registered-plmn", "当前注册 PLMN"),
    FIRST_EHPLMN("first-ehplmn", "首条等价归属 PLMN"),
}

enum class ConfigurationEpdgAccess(val wire: String, val label: String) {
    ANY("any", "不限漫游状态"), HOME("home", "仅非漫游"), ROAMING("roaming", "仅漫游"),
}

data class ConfigurationEpdgCandidate(
    val kind: ConfigurationEpdgKind,
    val value: String = "",
    val access: ConfigurationEpdgAccess = ConfigurationEpdgAccess.ANY,
    val registeredPlmns: List<String> = emptyList(),
) {
    override fun toString(): String = "ConfigurationEpdgCandidate"
}

/** A missing outer model preserves unknown input; null candidates explicitly clear the policy. */
data class ConfigurationEpdgSelection(val candidates: List<ConfigurationEpdgCandidate>? = null) {
    override fun toString(): String = "ConfigurationEpdgSelection"
}

data class ConfigurationEpdgSelectionFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationEpdgSelection? = null,
    val overrideValue: ConfigurationEpdgSelection? = null,
    val compatibilityBaseline: Boolean = false,
)

/** Requested candidate and captured access, not an authenticated peer identity. */
data class ConfigurationEpdgConnection(val candidate: Int, val registeredPlmn: String? = null, val roaming: Boolean? = null)
