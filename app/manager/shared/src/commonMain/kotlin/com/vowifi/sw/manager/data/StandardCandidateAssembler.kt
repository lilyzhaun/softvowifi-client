package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.StandardProvision
import com.vowifi.sw.manager.model.resolvedLine1
import com.vowifi.sw.manager.model.resolvedSmsc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object StandardCandidateAssembler {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun assemble(identity: RawSubscriptionIdentity): CandidateAssembly {
        val profile = StandardProvision.automaticProfileFor(identity.mcc, identity.mnc)
            ?: return CandidateAssembly.Unsupported(UNSUPPORTED)
        if (!ICCID_SHAPE.matches(identity.iccid) ||
            identity.subscriptionId < 0 ||
            (identity.slot != 0 && identity.slot != 1)
        ) {
            return CandidateAssembly.Invalid(INVALID)
        }
        val payload = json.encodeToString(
            WireCandidate.serializer(),
            WireCandidate(
                iccid = identity.iccid,
                slot = identity.slot,
                subscriptionId = identity.subscriptionId,
                mcc = profile.mcc,
                mnc = profile.mnc,
                mncLen = profile.mncLen,
                epdgFqdn = profile.epdgFqdn,
                imsRealm = profile.imsRealm,
                transport = transportWire(profile.transport),
                smsc = resolvedSmsc(identity.smsc, ""),
                dns = profile.dns,
                enabled = profile.enabled,
                imei = "",
                imsi = "",
                imsIdentityMode = "auto",
                line1 = resolvedLine1(identity.line1),
                carrierId = AndroidCarrierId.project(identity.carrierId),
            ),
        )
        return CandidateAssembly.Ready(payload)
    }
}

internal sealed interface CandidateAssembly {
    class Ready(val payload: String) : CandidateAssembly {
        override fun toString(): String = "Ready"
    }
    data class Unsupported(val message: String) : CandidateAssembly
    data class Invalid(val message: String) : CandidateAssembly
}

internal class RawSubscriptionIdentity(
    val iccid: String,
    val slot: Int,
    val subscriptionId: Int,
    val mcc: String?,
    val mnc: String?,
    val line1: String?,
    val smsc: String?,
    val carrierId: Int? = null,
) {
    override fun toString(): String = "RawIdentity"
}

@Serializable
private data class WireCandidate(
    val iccid: String,
    val slot: Int,
    val subscriptionId: Int,
    val mcc: String,
    val mnc: String,
    val mncLen: Int,
    val epdgFqdn: String,
    val imsRealm: String,
    val transport: String,
    val smsc: String,
    val dns: String,
    val enabled: Boolean,
    val imei: String,
    val imsi: String,
    val imsIdentityMode: String = "auto",
    val ikeProposalLayout: String? = null,
    val line1: String? = null,
    val carrierId: Int? = null,
)

private const val UNSUPPORTED = "该 SIM 不支持自动配置"
private const val INVALID = "SIM 身份格式无效"
private val ICCID_SHAPE = Regex("""^\d{15,22}$""")
