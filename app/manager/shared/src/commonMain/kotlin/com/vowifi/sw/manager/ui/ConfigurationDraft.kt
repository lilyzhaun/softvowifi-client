package com.vowifi.sw.manager.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationSubmit
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationApplyEffect
import com.vowifi.sw.manager.model.ConfigurationCallSignaling
import com.vowifi.sw.manager.model.ConfigurationCallMedia
import com.vowifi.sw.manager.model.ConfigurationTunnelSecurity
import com.vowifi.sw.manager.model.ConfigurationTunnelTiming
import com.vowifi.sw.manager.model.ConfigurationImsProtection
import com.vowifi.sw.manager.model.ConfigurationEpdgSelection
import com.vowifi.sw.manager.model.ConfigurationTunnelIdentity
import com.vowifi.sw.manager.model.ConfigurationImsTiming
import com.vowifi.sw.manager.model.ConfigurationSwuAccess

internal data class ConfigurationDraftValues(
    val transport: ConfigurationTransport,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
    val imsSipSecurity: String = "",
    val paniInitial: String = "",
    val paniCountry: String = "",
    val imsDigestRealm: String = "",
    // Every transport's DNS override as edited; [dns] mirrors the entry for [transport].
    val dnsOverrides: Map<String, String> = emptyMap(),
    val callSignaling: ConfigurationCallSignaling? = null,
    val callMedia: ConfigurationCallMedia? = null,
    val tunnelSecurity: ConfigurationTunnelSecurity? = null,
    val tunnelTiming: ConfigurationTunnelTiming? = null,
    val imsProtection: ConfigurationImsProtection? = null,
    val epdgAddressFamily: String? = null,
    val epdgSelection: ConfigurationEpdgSelection? = null,
    val tunnelIdentity: ConfigurationTunnelIdentity? = null,
    val imsTiming: ConfigurationImsTiming? = null,
    val swuAccess: ConfigurationSwuAccess? = null,
) {
    override fun toString(): String = "ConfigurationDraftValues"
    companion object {
        fun from(instance: ConfigurationInstance) = ConfigurationDraftValues(
            transport = instance.transport,
            smsc = instance.smsc,
            epdgFqdn = instance.epdgFqdn,
            imsRealm = instance.imsRealm,
            dns = instance.dns,
            imsSipSecurity = instance.imsPolicy.securityOverride,
            paniInitial = instance.imsPolicy.initialOverride,
            paniCountry = instance.imsPolicy.countryOverride,
            imsDigestRealm = instance.imsPolicy.digestRealmOverride,
            dnsOverrides = instance.dnsOverrides.withDns(instance.transport, instance.dns),
            callSignaling = instance.callSignaling,
            callMedia = instance.callMedia,
            tunnelSecurity = instance.tunnelSecurity,
            tunnelTiming = instance.tunnelTiming,
            imsProtection = instance.imsProtection,
            epdgAddressFamily = instance.epdgAddressFamily,
            epdgSelection = instance.epdgSelection,
            tunnelIdentity = instance.tunnelIdentity,
            imsTiming = instance.imsTiming,
            swuAccess = instance.swuAccess,
        )
    }
}

private fun Map<String, String>.withDns(transport: ConfigurationTransport, value: String): Map<String, String> =
    if (value.isBlank()) this - transport.wireValue else this + (transport.wireValue to value)

internal fun synchronizedConfigurationDraft(
    current: ConfigurationDraftValues,
    baseline: ConfigurationDraftValues,
    incoming: ConfigurationDraftValues,
): ConfigurationDraftValues {
    val transport = current.transport.takeUnless { it == baseline.transport } ?: incoming.transport
    // Per transport: keep the user's edited override, otherwise take the refreshed one.
    val dnsOverrides = (current.dnsOverrides.keys + baseline.dnsOverrides.keys + incoming.dnsOverrides.keys).mapNotNull { key ->
        val edited = current.dnsOverrides[key] != baseline.dnsOverrides[key]
        (if (edited) current.dnsOverrides[key] else incoming.dnsOverrides[key])?.let { key to it }
    }.toMap()
    return ConfigurationDraftValues(
        transport = transport,
        smsc = current.smsc.takeUnless { it == baseline.smsc } ?: incoming.smsc,
        epdgFqdn = current.epdgFqdn.takeUnless { it == baseline.epdgFqdn } ?: incoming.epdgFqdn,
        imsRealm = current.imsRealm.takeUnless { it == baseline.imsRealm } ?: incoming.imsRealm,
        dns = dnsOverrides[transport.wireValue].orEmpty(),
        imsSipSecurity = current.imsSipSecurity.takeUnless { it == baseline.imsSipSecurity } ?: incoming.imsSipSecurity,
        paniInitial = current.paniInitial.takeUnless { it == baseline.paniInitial } ?: incoming.paniInitial,
        paniCountry = current.paniCountry.takeUnless { it == baseline.paniCountry } ?: incoming.paniCountry,
        imsDigestRealm = current.imsDigestRealm.takeUnless { it == baseline.imsDigestRealm } ?: incoming.imsDigestRealm,
        dnsOverrides = dnsOverrides,
        // Signal settings are one revision-bound atomic group. Do not combine
        // siblings from a fresh group with the user's in-progress older group.
        callSignaling = if (current.callSignaling != baseline.callSignaling) current.callSignaling else incoming.callSignaling,
        callMedia = if (current.callMedia != baseline.callMedia) current.callMedia else incoming.callMedia,
        tunnelSecurity = if (current.tunnelSecurity != baseline.tunnelSecurity) current.tunnelSecurity else incoming.tunnelSecurity,
        tunnelTiming = if (current.tunnelTiming != baseline.tunnelTiming) current.tunnelTiming else incoming.tunnelTiming,
        imsProtection = if (current.imsProtection != baseline.imsProtection) current.imsProtection else incoming.imsProtection,
        epdgAddressFamily = if (current.epdgAddressFamily != baseline.epdgAddressFamily) current.epdgAddressFamily else incoming.epdgAddressFamily,
        epdgSelection = if (current.epdgSelection != baseline.epdgSelection) current.epdgSelection else incoming.epdgSelection,
        tunnelIdentity = if (current.tunnelIdentity != baseline.tunnelIdentity) current.tunnelIdentity else incoming.tunnelIdentity,
        imsTiming = if (current.imsTiming != baseline.imsTiming) current.imsTiming else incoming.imsTiming,
        swuAccess = if (current.swuAccess != baseline.swuAccess) current.swuAccess else incoming.swuAccess,
    )
}

internal class ConfigurationEditorDraft(
    instance: ConfigurationInstance,
    retained: ConfigurationRetainedDraft? = null,
) {
    var instanceId: ConfigurationInstanceId = instance.instanceId
        private set
    private val owner = instance.draftOwnerId
    private var baseline by mutableStateOf(retained?.baseline ?: ConfigurationDraftValues.from(instance))
    private var expectedRevision = if (retained != null) retained.expectedRevision else instance.configRevision
    private var baselineRevision = if (retained != null) retained.baselineRevision else instance.configRevision
    private var resolvedImsSecurity by mutableStateOf(instance.imsPolicy.security)
    private var selectedTransport by mutableStateOf(baseline.transport)
    private var dnsOverrides by mutableStateOf(baseline.dnsOverrides)

    /** Switching transport keeps the edited DNS of the old one and shows the new one's override. */
    var transport: ConfigurationTransport
        get() = selectedTransport
        set(value) {
            if (value == selectedTransport) return
            dnsOverrides = dnsOverrides.withDns(selectedTransport, dns.text.toString())
            selectedTransport = value
            dns.replaceText(dnsOverrides[value.wireValue].orEmpty())
        }
    val smsc = TextFieldState(baseline.smsc)
    val epdgFqdn = TextFieldState(baseline.epdgFqdn)
    val imsRealm = TextFieldState(baseline.imsRealm)
    val dns = TextFieldState(baseline.dns)
    var imsSipSecurity by mutableStateOf(baseline.imsSipSecurity)
    var paniInitial by mutableStateOf(baseline.paniInitial)
    var callSignaling by mutableStateOf(baseline.callSignaling)
        private set
    var callMedia by mutableStateOf(baseline.callMedia)
        private set
    var tunnelSecurity by mutableStateOf(baseline.tunnelSecurity)
        private set
    var tunnelTiming by mutableStateOf(baseline.tunnelTiming)
        private set
    var imsProtection by mutableStateOf(baseline.imsProtection)
        private set
    var epdgAddressFamily by mutableStateOf(baseline.epdgAddressFamily)
        private set
    var epdgSelection by mutableStateOf(baseline.epdgSelection)
        private set
    var tunnelIdentity by mutableStateOf(baseline.tunnelIdentity)
        private set
    var imsTiming by mutableStateOf(baseline.imsTiming)
        private set
    var swuAccess by mutableStateOf(baseline.swuAccess)
        private set
    private var lastSubmissionNextCall by mutableStateOf(false)
    val paniCountry = TextFieldState(baseline.paniCountry)
    val imsDigestRealm = TextFieldState(baseline.imsDigestRealm)
    var status by mutableStateOf(if (retained?.unconfirmed == true) ConfigurationEditStatus.Unconfirmed else ConfigurationEditStatus.Idle)
        private set
    var completionSequence by mutableLongStateOf(0L)
        private set
    var completionStatus: ConfigurationEditStatus? by mutableStateOf(null)
        private set
    private var validationRequested by mutableStateOf(false)
    val errors: Map<ConfigurationFieldKey, String>
        get() = if (validationRequested) configurationFieldErrors(overrides()) else emptyMap()
    val pending: Boolean get() = status == ConfigurationEditStatus.Saving
    val dirty: Boolean get() = currentValues() != baseline
    val signalingModified: Boolean get() = callSignaling != baseline.callSignaling
    val mediaModified: Boolean get() = callMedia != baseline.callMedia
    val tunnelModified: Boolean get() = tunnelSecurity != baseline.tunnelSecurity
    val timingModified: Boolean get() = tunnelTiming != baseline.tunnelTiming
    val protectionModified: Boolean get() = imsProtection != baseline.imsProtection
    val addressFamilyModified: Boolean get() = epdgAddressFamily != baseline.epdgAddressFamily
    val selectionModified: Boolean get() = epdgSelection != baseline.epdgSelection
    val identityModified: Boolean get() = tunnelIdentity != baseline.tunnelIdentity
    val imsTimingModified: Boolean get() = imsTiming != baseline.imsTiming
    val swuAccessModified: Boolean get() = swuAccess != baseline.swuAccess
    val onlySignalingChanges: Boolean get() = signalingModified && !mediaModified && !tunnelModified && !timingModified && !protectionModified && !addressFamilyModified && !selectionModified && !identityModified && !imsTimingModified && !swuAccessModified && !transportModified && modifiedFields().isEmpty()
    val onlyNextCallChanges: Boolean get() = (signalingModified || mediaModified) && !tunnelModified && !timingModified && !protectionModified && !addressFamilyModified && !selectionModified && !identityModified && !imsTimingModified && !swuAccessModified && !transportModified && modifiedFields().isEmpty()
    val nextCallSaveOnly: Boolean get() = if (dirty || pending) onlyNextCallChanges
        else lastSubmissionNextCall && status in setOf(ConfigurationEditStatus.Saved, ConfigurationEditStatus.Unchanged)
    val signalingError: String? get() = if (validationRequested) {
        try { callSignaling?.let(com.vowifi.sw.manager.data.ConfigurationCallSignalingCodec::encode); null }
        catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) { "通话信令参数无效或相互冲突；请核对支持开关、对端要求与会话期限" }
    } else null
    val mediaError: String? get() = if (validationRequested) {
        try { callMedia?.let(com.vowifi.sw.manager.data.ConfigurationCallMediaCodec::encode); null }
        catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) { "语音格式无效或重复；请核对模式集合、初始模式及载荷编号" }
    } else null
    val transportModified: Boolean get() = transport != baseline.transport
    val tunnelError: String? get() = if (validationRequested) {
        try { tunnelSecurity?.let(com.vowifi.sw.manager.data.ConfigurationTunnelSecurityCodec::encode); null }
        catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) { "请选择完整 IKE 与 Child 报价；同组 DH 须一致，合并布局不能扩大组合" }
    } else null
    val timingError: String? get() = if (validationRequested) {
        try { tunnelTiming?.let(com.vowifi.sw.manager.data.ConfigurationTunnelTimingCodec::encode); null }
        catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) { "请完整填写两类 SA 期限：硬期限大于软期限，软期限减提前量至少 30 秒；保活与重传参数须在有效范围内" }
    } else null
    val protectionError: String? get() = if (validationRequested) {
        try {
            imsProtection?.let(com.vowifi.sw.manager.data.ConfigurationImsProtectionCodec::encode)
            // Clearing a user transport override can reveal an unknown inherited
            // policy. Only the backend resolves that case; do not guess it here.
            if (imsProtection?.suites != null && (imsSipSecurity == "swu_udp_aka" ||
                imsSipSecurity == baseline.imsSipSecurity && resolvedImsSecurity == "swu_udp_aka"))
                "IMS 保护组合需要 TCP + IPsec；请修改 SIP 安全策略或清除保护组" else null
        } catch (_: com.vowifi.sw.manager.model.ConfigurationFormatException) { "请至少选择一个不重复的完整 IMS 保护组合；空列表不是恢复默认" }
    } else null
    val addressFamilyError: String? get() = if (validationRequested) {
        try { epdgAddressFamily?.let(com.vowifi.sw.manager.data.ConfigurationAddressFamilyCodec::encode); null }
        catch (error: com.vowifi.sw.manager.model.ConfigurationFormatException) { error.message }
    } else null
    val selectionError: String? get() = if (validationRequested) {
        try { epdgSelection?.let { com.vowifi.sw.manager.data.ConfigurationEpdgSelectionCodec.validate(it, epdgFqdn.text.toString()) }; null }
        catch (error: com.vowifi.sw.manager.model.ConfigurationFormatException) { error.message }
    } else null
    val identityError: String? get() = if (validationRequested) {
        try { tunnelIdentity?.let(com.vowifi.sw.manager.data.ConfigurationTunnelIdentityCodec::encode); null }
        catch (error: com.vowifi.sw.manager.model.ConfigurationFormatException) { error.message }
    } else null
    val imsTimingError: String? get() = if (validationRequested) {
        try { imsTiming?.let(com.vowifi.sw.manager.data.ConfigurationImsTimingCodec::encode); null }
        catch (error: com.vowifi.sw.manager.model.ConfigurationFormatException) { error.message }
    } else null
    val swuAccessError: String? get() = if (validationRequested) {
        try { swuAccess?.let(com.vowifi.sw.manager.data.ConfigurationSwuAccessCodec::encode); null }
        catch (error: com.vowifi.sw.manager.model.ConfigurationFormatException) { error.message }
    } else null
    val modifiedCount: Int get() = modifiedFields().size + (if (transportModified) 1 else 0) + (if (signalingModified) 1 else 0) + (if (mediaModified) 1 else 0) + (if (tunnelModified) 1 else 0) + (if (timingModified) 1 else 0) + (if (protectionModified) 1 else 0) + (if (addressFamilyModified) 1 else 0) + (if (selectionModified) 1 else 0) + (if (identityModified) 1 else 0) + (if (imsTimingModified) 1 else 0) + (if (swuAccessModified) 1 else 0)

    fun replaceSwuAccess(value: ConfigurationSwuAccess): Boolean {
        if (pending || baseline.swuAccess == null && value != ConfigurationSwuAccess()) return false
        swuAccess = value
        return true
    }

    fun replaceCallSignaling(value: ConfigurationCallSignaling): Boolean {
        if (pending || baseline.callSignaling == null && value != ConfigurationCallSignaling()) return false
        callSignaling = value
        return true
    }

    fun replaceCallMedia(value: ConfigurationCallMedia): Boolean {
        if (pending || baseline.callMedia == null && value != ConfigurationCallMedia()) return false
        callMedia = value
        return true
    }

    fun replaceTunnelSecurity(value: ConfigurationTunnelSecurity): Boolean {
        if (pending || baseline.tunnelSecurity == null && value != ConfigurationTunnelSecurity()) return false
        tunnelSecurity = value
        return true
    }

    fun replaceTunnelTiming(value: ConfigurationTunnelTiming): Boolean {
        if (pending || baseline.tunnelTiming == null && value != ConfigurationTunnelTiming()) return false
        tunnelTiming = value
        return true
    }

    fun replaceImsTiming(value: ConfigurationImsTiming): Boolean {
        if (pending || baseline.imsTiming == null && value != ConfigurationImsTiming()) return false
        imsTiming = value
        return true
    }

    fun replaceImsProtection(value: ConfigurationImsProtection): Boolean {
        if (pending || baseline.imsProtection == null && value != ConfigurationImsProtection()) return false
        imsProtection = value
        return true
    }

    fun replaceEpdgAddressFamily(value: String): Boolean {
        if (pending || baseline.epdgAddressFamily == null && value.isNotEmpty()) return false
        epdgAddressFamily = value
        return true
    }

    fun replaceEpdgSelection(value: ConfigurationEpdgSelection): Boolean {
        if (pending || baseline.epdgSelection == null && value != ConfigurationEpdgSelection()) return false
        epdgSelection = value.copy(candidates = value.candidates?.map { it.copy(registeredPlmns = it.registeredPlmns.sorted()) })
        return true
    }

    fun replaceTunnelIdentity(value: ConfigurationTunnelIdentity): Boolean {
        if (pending || baseline.tunnelIdentity == null && value != ConfigurationTunnelIdentity()) return false
        tunnelIdentity = value
        return true
    }

    /** Fields whose current draft value differs from the latest authoritative baseline (transport is separate). */
    fun modifiedFields(): Set<ConfigurationFieldKey> {
        val current = currentValues()
        return buildSet {
            if (current.smsc != baseline.smsc) add(ConfigurationFieldKey.Smsc)
            if (current.epdgFqdn != baseline.epdgFqdn) add(ConfigurationFieldKey.Epdg)
            if (current.imsRealm != baseline.imsRealm) add(ConfigurationFieldKey.Realm)
            if (current.dnsOverrides != baseline.dnsOverrides) add(ConfigurationFieldKey.Dns)
            if (current.imsSipSecurity != baseline.imsSipSecurity) add(ConfigurationFieldKey.SipSecurity)
            if (current.paniInitial != baseline.paniInitial) add(ConfigurationFieldKey.PaniInitial)
            if (current.paniCountry != baseline.paniCountry) add(ConfigurationFieldKey.PaniCountry)
            if (current.imsDigestRealm != baseline.imsDigestRealm) add(ConfigurationFieldKey.DigestRealm)
        }
    }

    fun textFor(key: ConfigurationFieldKey): TextFieldState? = when (key) {
        ConfigurationFieldKey.Smsc -> smsc
        ConfigurationFieldKey.Epdg -> epdgFqdn
        ConfigurationFieldKey.Realm -> imsRealm
        ConfigurationFieldKey.Dns -> dns
        ConfigurationFieldKey.PaniCountry -> paniCountry
        ConfigurationFieldKey.DigestRealm -> imsDigestRealm
        ConfigurationFieldKey.SipSecurity, ConfigurationFieldKey.PaniInitial -> null
    }

    /** Same codec validation as save, for one field's candidate value; never writes the draft. */
    fun candidateError(key: ConfigurationFieldKey, value: String): String? {
        val current = overrides()
        val candidate = when (key) {
            ConfigurationFieldKey.Smsc -> current.copy(smsc = value)
            ConfigurationFieldKey.Epdg -> current.copy(epdgFqdn = value)
            ConfigurationFieldKey.Realm -> current.copy(imsRealm = value)
            ConfigurationFieldKey.Dns -> current.copy(dns = value)
            ConfigurationFieldKey.SipSecurity -> current.copy(imsSipSecurity = value)
            ConfigurationFieldKey.PaniInitial -> current.copy(paniInitial = value)
            ConfigurationFieldKey.PaniCountry -> current.copy(paniCountry = value)
            ConfigurationFieldKey.DigestRealm -> current.copy(imsDigestRealm = value)
        }
        return configurationFieldErrors(candidate)[key]
    }

    fun replaceField(key: ConfigurationFieldKey, value: String) {
        if (pending) return
        textFor(key)?.replaceText(value)
    }

    init {
        retained?.let { replaceValues(it.current) }
        syncFrom(instance)
    }

    fun syncFrom(instance: ConfigurationInstance) {
        check(instance.draftOwnerId == owner && (owner != null || instance.instanceId == instanceId))
        instanceId = instance.instanceId
        // A clean draft can follow a fresh snapshot. Once an edit spans a
        // revision change, only explicit discard or a confirmed save may bind
        // it to the new revision; refresh/retention must not authorize replay.
        if (!dirty && !pending && expectedRevision == baselineRevision) expectedRevision = instance.configRevision
        baselineRevision = instance.configRevision
        resolvedImsSecurity = instance.imsPolicy.security
        val incoming = ConfigurationDraftValues.from(instance)
        val values = synchronizedConfigurationDraft(currentValues(), baseline, incoming)
        if (!pending) replaceValues(values)
        baseline = incoming
    }

    fun discard() {
        if (pending) return
        replaceValues(baseline)
        expectedRevision = baselineRevision
        validationRequested = false
        status = ConfigurationEditStatus.Idle
        lastSubmissionNextCall = false
    }

    /**
     * Clears every non-transport manual override back to automatic/inherited defaults in the local
     * draft only; the user still confirms persistence through the save bar.
     */
    fun resetAdvancedToAutomatic(): Boolean {
        if (pending) return false
        val before = currentValues()
        val reset = ConfigurationDraftValues(
            transport = before.transport,
            smsc = "",
            epdgFqdn = "",
            imsRealm = "",
            dns = "",
            imsSipSecurity = "",
            paniInitial = "",
            paniCountry = "",
            imsDigestRealm = "",
            dnsOverrides = emptyMap(),
            callSignaling = baseline.callSignaling?.let { ConfigurationCallSignaling() },
            callMedia = baseline.callMedia?.let { ConfigurationCallMedia() },
            tunnelSecurity = baseline.tunnelSecurity?.let { ConfigurationTunnelSecurity() },
            tunnelTiming = baseline.tunnelTiming?.let { ConfigurationTunnelTiming() },
            imsProtection = baseline.imsProtection?.let { ConfigurationImsProtection() },
            epdgAddressFamily = baseline.epdgAddressFamily?.let { "" },
            epdgSelection = baseline.epdgSelection?.let { ConfigurationEpdgSelection() },
            tunnelIdentity = baseline.tunnelIdentity?.let { ConfigurationTunnelIdentity() },
            imsTiming = baseline.imsTiming?.let { ConfigurationImsTiming() },
            swuAccess = baseline.swuAccess?.let { ConfigurationSwuAccess() },
        )
        if (reset == before) return false
        replaceValues(reset)
        validationRequested = false
        if (status == ConfigurationEditStatus.Invalid) status = ConfigurationEditStatus.Idle
        return true
    }

    val advancedModified: Boolean
        get() = modifiedFields().isNotEmpty() || signalingModified || mediaModified ||
            tunnelModified || timingModified || protectionModified || addressFamilyModified ||
            selectionModified || identityModified || imsTimingModified || swuAccessModified

    internal fun snapshotValues(): ConfigurationDraftValues = currentValues()

    fun validate(): Boolean {
        validationRequested = true
        val valid = errors.isEmpty() && signalingError == null && mediaError == null && tunnelError == null && timingError == null && protectionError == null && addressFamilyError == null && selectionError == null && identityError == null && imsTimingError == null && swuAccessError == null
        if (!valid) status = ConfigurationEditStatus.Invalid
        return valid
    }

    fun submit(apply: ConfigurationSubmit) {
        if (pending) return
        if (!dirty) {
            status = ConfigurationEditStatus.Unchanged
            return
        }
        if (!validate()) return
        val current = currentValues()
        val normalizedDns = current.dnsOverrides.mapValues { com.vowifi.sw.manager.data.dnsServerValue(it.value) }
        val submitted = current.copy(
            paniCountry = com.vowifi.sw.manager.data.paniCountryValue(paniCountry.text.toString()),
            dns = normalizedDns[current.transport.wireValue].orEmpty(),
            dnsOverrides = normalizedDns,
        )
        val submittedId = instanceId
        val beforeCompletion = completionSequence
        status = ConfigurationEditStatus.Saving
        val acceptance = apply(submittedId, overrides()) { outcome ->
            if (!pending || completionSequence != beforeCompletion) return@apply
            status = when (outcome) {
                ConfigurationApplyOutcome.Busy -> ConfigurationEditStatus.AdmissionRefused
                ConfigurationApplyOutcome.Failed -> ConfigurationEditStatus.Failed
                ConfigurationApplyOutcome.Unconfirmed -> ConfigurationEditStatus.Unconfirmed
                is ConfigurationApplyOutcome.Confirmed -> {
                    val incoming = outcome.instance
                    if (incoming.draftOwnerId != owner || incoming.instanceId != submittedId ||
                        ConfigurationDraftValues.from(incoming) != submitted
                    ) {
                        ConfigurationEditStatus.Unconfirmed
                    } else {
                        baseline = ConfigurationDraftValues.from(incoming)
                        baselineRevision = incoming.configRevision
                        resolvedImsSecurity = incoming.imsPolicy.security
                        expectedRevision = incoming.configRevision
                        lastSubmissionNextCall = outcome.effect == ConfigurationApplyEffect.NextCall
                        replaceValues(baseline)
                        if (outcome.result == com.vowifi.sw.manager.model.ConfigurationWriteResult.Unchanged)
                            ConfigurationEditStatus.Unchanged else ConfigurationEditStatus.Saved
                    }
                }
            }
            // Actual typed callback only: synchronous completion may never render a Saving frame.
            completionStatus = status
            completionSequence++
        }
        when (acceptance) {
            ConfigurationApplyAcceptance.Accepted -> Unit
            ConfigurationApplyAcceptance.Busy -> status = ConfigurationEditStatus.BusyRejected
            ConfigurationApplyAcceptance.Unavailable -> status = ConfigurationEditStatus.Unavailable
        }
    }

    fun retained() = ConfigurationRetainedDraft(
        currentValues(), baseline, pending || status == ConfigurationEditStatus.Unconfirmed,
        expectedRevision, baselineRevision,
    )

    private fun replaceValues(values: ConfigurationDraftValues) {
        selectedTransport = values.transport
        dnsOverrides = values.dnsOverrides
        smsc.replaceText(values.smsc)
        epdgFqdn.replaceText(values.epdgFqdn)
        imsRealm.replaceText(values.imsRealm)
        dns.replaceText(values.dns)
        imsSipSecurity = values.imsSipSecurity
        paniInitial = values.paniInitial
        callSignaling = values.callSignaling
        callMedia = values.callMedia
        tunnelSecurity = values.tunnelSecurity
        tunnelTiming = values.tunnelTiming
        imsProtection = values.imsProtection
        epdgAddressFamily = values.epdgAddressFamily
        epdgSelection = values.epdgSelection
        tunnelIdentity = values.tunnelIdentity
        imsTiming = values.imsTiming
        swuAccess = values.swuAccess
        paniCountry.replaceText(values.paniCountry)
        imsDigestRealm.replaceText(values.imsDigestRealm)
    }

    fun overrides() = ConfigurationOverrides(
        transport = transport,
        smsc = smsc.text.toString(),
        epdgFqdn = epdgFqdn.text.toString(),
        imsRealm = imsRealm.text.toString(),
        dns = dns.text.toString(),
        imsSipSecurity = imsSipSecurity,
        paniInitial = paniInitial,
        paniCountry = paniCountry.text.toString(),
        imsDigestRealm = imsDigestRealm.text.toString(),
        dnsOverrides = dnsOverrides,
        expectedRevision = expectedRevision,
        callSignaling = callSignaling,
        callMedia = callMedia,
        tunnelSecurity = tunnelSecurity,
        tunnelTiming = tunnelTiming,
        imsProtection = imsProtection,
        epdgAddressFamily = epdgAddressFamily,
        epdgSelection = epdgSelection,
        tunnelIdentity = tunnelIdentity,
        imsTiming = imsTiming,
        swuAccess = swuAccess,
    )

    private fun currentValues() = ConfigurationDraftValues(
        transport = transport,
        smsc = smsc.text.toString(),
        epdgFqdn = epdgFqdn.text.toString(),
        imsRealm = imsRealm.text.toString(),
        dns = dns.text.toString(),
        imsSipSecurity = imsSipSecurity,
        paniInitial = paniInitial,
        paniCountry = paniCountry.text.toString(),
        imsDigestRealm = imsDigestRealm.text.toString(),
        dnsOverrides = dnsOverrides.withDns(transport, dns.text.toString()),
        callSignaling = callSignaling,
        callMedia = callMedia,
        tunnelSecurity = tunnelSecurity,
        tunnelTiming = tunnelTiming,
        imsProtection = imsProtection,
        epdgAddressFamily = epdgAddressFamily,
        epdgSelection = epdgSelection,
        tunnelIdentity = tunnelIdentity,
        imsTiming = imsTiming,
        swuAccess = swuAccess,
    )
}

private fun TextFieldState.replaceText(value: String) {
    if (text.toString() == value) return
    edit { replace(0, length, value) }
}
