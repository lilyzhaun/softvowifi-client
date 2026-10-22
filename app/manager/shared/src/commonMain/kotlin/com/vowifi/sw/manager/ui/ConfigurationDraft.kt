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
    val transportModified: Boolean get() = transport != baseline.transport
    val modifiedCount: Int get() = modifiedFields().size + if (transportModified) 1 else 0

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
        val incoming = ConfigurationDraftValues.from(instance)
        val values = synchronizedConfigurationDraft(currentValues(), baseline, incoming)
        if (!pending) replaceValues(values)
        baseline = incoming
    }

    fun discard() {
        if (pending) return
        replaceValues(baseline)
        validationRequested = false
        status = ConfigurationEditStatus.Idle
    }

    fun validate(): Boolean {
        validationRequested = true
        if (errors.isNotEmpty()) status = ConfigurationEditStatus.Invalid
        return errors.isEmpty()
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

    fun retained() = ConfigurationRetainedDraft(currentValues(), baseline, pending || status == ConfigurationEditStatus.Unconfirmed)

    private fun replaceValues(values: ConfigurationDraftValues) {
        selectedTransport = values.transport
        dnsOverrides = values.dnsOverrides
        smsc.replaceText(values.smsc)
        epdgFqdn.replaceText(values.epdgFqdn)
        imsRealm.replaceText(values.imsRealm)
        dns.replaceText(values.dns)
        imsSipSecurity = values.imsSipSecurity
        paniInitial = values.paniInitial
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
    )
}

private fun TextFieldState.replaceText(value: String) {
    if (text.toString() == value) return
    edit { replace(0, length, value) }
}
