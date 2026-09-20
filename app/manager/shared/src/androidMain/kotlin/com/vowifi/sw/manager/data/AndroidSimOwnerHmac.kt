package com.vowifi.sw.manager.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

internal class AndroidSimOwnerHmac {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun aliasExists(): Boolean = keyStore.containsAlias(ALIAS)

    fun ensureKey() {
        if (aliasExists()) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(KEY_SIZE_BITS)
                .setIsStrongBoxBacked(false)
                .build(),
        )
        generator.generateKey()
    }

    fun ownerIdFor(rawIccid: String): SimOwnerId? {
        val normalized = IccidNormalizer.normalize(rawIccid) ?: return null
        val mac = hmac(normalized.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return SimOwnerId.fromHmacSha256(mac)
    }

    fun probe(): ByteArray? = hmac(PROBE_PLAINTEXT.toByteArray(StandardCharsets.UTF_8))

    override fun toString(): String = "AndroidSimOwnerHmac"

    private fun hmac(data: ByteArray): ByteArray? = try {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(key)
        mac.doFinal(data)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ALIAS = "com.vowifi.sw.sim-owner-hmac-v1"
        const val PROVIDER = "AndroidKeyStore"
        const val MAC_ALGORITHM = "HmacSHA256"
        const val KEY_SIZE_BITS = 256
        const val PROBE_PLAINTEXT = "com.vowifi.sw.sim-owner-hmac-probe-v1"
    }
}
