package com.vowifi.sw.manager.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
        )
    }
}

internal fun synchronizedConfigurationDraft(
    current: ConfigurationDraftValues,
    baseline: ConfigurationDraftValues,
    incoming: ConfigurationDraftValues,
): ConfigurationDraftValues = ConfigurationDraftValues(
    transport = current.transport.takeUnless { it == baseline.transport } ?: incoming.transport,
    smsc = current.smsc.takeUnless { it == baseline.smsc } ?: incoming.smsc,
    epdgFqdn = current.epdgFqdn.takeUnless { it == baseline.epdgFqdn } ?: incoming.epdgFqdn,
    imsRealm = current.imsRealm.takeUnless { it == baseline.imsRealm } ?: incoming.imsRealm,
    dns = current.dns.takeUnless { it == baseline.dns } ?: incoming.dns,
    imsSipSecurity = current.imsSipSecurity.takeUnless { it == baseline.imsSipSecurity } ?: incoming.imsSipSecurity,
    paniInitial = current.paniInitial.takeUnless { it == baseline.paniInitial } ?: incoming.paniInitial,
    paniCountry = current.paniCountry.takeUnless { it == baseline.paniCountry } ?: incoming.paniCountry,
    imsDigestRealm = current.imsDigestRealm.takeUnless { it == baseline.imsDigestRealm } ?: incoming.imsDigestRealm,
)

internal class ConfigurationEditorDraft(
    instance: ConfigurationInstance,
    retained: ConfigurationRetainedDraft? = null,
) {
    var instanceId: ConfigurationInstanceId = instance.instanceId
        private set
    private val owner = instance.draftOwnerId
    private var baseline by mutableStateOf(retained?.baseline ?: ConfigurationDraftValues.from(instance))
    var transport by mutableStateOf(baseline.transport)
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
    private var validationRequested by mutableStateOf(false)
    val errors: Map<ConfigurationFieldKey, String>
        get() = if (validationRequested) configurationFieldErrors(overrides()) else emptyMap()
    val pending: Boolean get() = status == ConfigurationEditStatus.Saving
    val dirty: Boolean get() = currentValues() != baseline

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
        val submitted = currentValues().copy(paniCountry = com.vowifi.sw.manager.data.paniCountryValue(paniCountry.text.toString()))
        val submittedId = instanceId
        status = ConfigurationEditStatus.Saving
        val acceptance = apply(submittedId, overrides()) { outcome ->
            if (!pending) return@apply
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
        }
        when (acceptance) {
            ConfigurationApplyAcceptance.Accepted -> Unit
            ConfigurationApplyAcceptance.Busy -> status = ConfigurationEditStatus.BusyRejected
            ConfigurationApplyAcceptance.Unavailable -> status = ConfigurationEditStatus.Unavailable
        }
    }

    fun retained() = ConfigurationRetainedDraft(currentValues(), baseline, pending || status == ConfigurationEditStatus.Unconfirmed)

    private fun replaceValues(values: ConfigurationDraftValues) {
        transport = values.transport
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
    )
}

private fun TextFieldState.replaceText(value: String) {
    if (text.toString() == value) return
    edit { replace(0, length, value) }
}
