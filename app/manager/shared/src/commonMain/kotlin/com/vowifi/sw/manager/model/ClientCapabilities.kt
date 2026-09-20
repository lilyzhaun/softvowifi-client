package com.vowifi.sw.manager.model

data class EngineClientCapabilities(
    val instanceKey: String,
    val engineVersion: String,
    val capabilities: List<String>,
    val configMetadata: List<ClientConfigMetadata>,
)

enum class MetadataField(val wireValue: String) {
    SipSecurity("imsSipSecurity"), PaniInitial("paniInitial"),
    PaniCountry("paniCountry"), DigestRealm("imsDigestRealm"),
}

enum class MetadataSource(val wireValue: String) {
    User("user"), Carrier("carrier"), Default("default"), Catalog("catalog"),
    HomeMcc("home_mcc"), Identity("identity"),
}

enum class MetadataDecision(val wireValue: String) {
    Override("override"), Inherited("inherited"),
}

data class ClientConfigMetadata(
    val field: MetadataField,
    val value: String,
    val source: MetadataSource,
    val decision: MetadataDecision,
)

enum class ClientCapabilitiesFailure { Invalid, Unsupported, Rpc, Transport, Timeout }

sealed interface ClientCapabilitiesResult {
    data class Ready(val capabilities: EngineClientCapabilities) : ClientCapabilitiesResult
    data class Unavailable(val reason: ClientCapabilitiesFailure) : ClientCapabilitiesResult
}
