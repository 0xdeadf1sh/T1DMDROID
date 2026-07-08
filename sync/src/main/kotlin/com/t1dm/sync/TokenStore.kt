package com.t1dm.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-profile `rw` token at rest (PLAN.private.md Phase 3: "token in a Keystore-backed TokenStore").
 * The token is the single credential the phone holds; it must never sit in the keep-forever Room DB
 * (which a backup/export could leak), so it lives here keyed by profile id. The interface is tiny so
 * tests substitute an in-memory implementation.
 */
interface TokenStore {
    suspend fun get(profileId: String): String?
    suspend fun put(profileId: String, token: String)
    suspend fun remove(profileId: String)
}

/** Deterministic, non-persistent store for host tests and previews. */
class InMemoryTokenStore(seed: Map<String, String> = emptyMap()) : TokenStore {
    private val map = HashMap(seed)
    override suspend fun get(profileId: String): String? = map[profileId]
    override suspend fun put(profileId: String, token: String) { map[profileId] = token }
    override suspend fun remove(profileId: String) { map.remove(profileId) }
}

/**
 * AndroidKeyStore-backed store: a hardware-bound AES-256-GCM key wraps each token, and the
 * `iv:ciphertext` is parked in a private `SharedPreferences`. The raw token never touches disk in
 * the clear and the wrapping key is non-exportable. Tailscale makes transport TLS moot, but the
 * credential still deserves at-rest protection against a pulled backup.
 *
 * This avoids a `security-crypto` dependency by driving the Keystore directly; the crypto is the
 * stock GCM envelope, nothing bespoke.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun get(profileId: String): String? {
        val packed = prefs.getString(profileId, null) ?: return null
        val sep = packed.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(packed.substring(0, sep), Base64.NO_WRAP)
        val ct = Base64.decode(packed.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override suspend fun put(profileId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(profileId, packed).apply()
    }

    override suspend fun remove(profileId: String) {
        prefs.edit().remove(profileId).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS = "t1dm_server_tokens"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "t1dm_server_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
