package com.vowifi.sw.manager.model

/** Current authenticated selection; neither configured proposals nor a kernel readback. */
data class ConfigurationNegotiatedTunnelFacts(
    val ike: ConfigurationNegotiatedIke,
    val child: ConfigurationNegotiatedChild,
) {
    override fun toString(): String = "ConfigurationNegotiatedTunnelFacts"
}

data class ConfigurationNegotiatedIke(
    val encryption: Int, val keyBits: Int, val integrity: Int, val prf: Int, val dh: Int,
) {
    override fun toString(): String = "ConfigurationNegotiatedIke"
}

data class ConfigurationNegotiatedChild(
    val encryption: Int, val keyBits: Int, val integrity: Int, val esn: Boolean, val pfs: Int,
) {
    override fun toString(): String = "ConfigurationNegotiatedChild"
}
