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

/** The `rw` token at rest, keyed by profile id. It must never sit in the keep-forever Room DB, which
 *  a backup or export could leak. */
interface TokenStore {
    suspend fun get(profileId: String): String?
    suspend fun put(profileId: String, token: String)
    suspend fun remove(profileId: String)

    /** Every profile's `rw` credential at once. */
    suspend fun clearAll()
}

class InMemoryTokenStore(seed: Map<String, String> = emptyMap()) : TokenStore {
    private val map = HashMap(seed)
    override suspend fun get(profileId: String): String? = map[profileId]
    override suspend fun put(profileId: String, token: String) { map[profileId] = token }
    override suspend fun remove(profileId: String) { map.remove(profileId) }
    override suspend fun clearAll() { map.clear() }
}

/**
 * A hardware-bound AES-256-GCM key wraps each token; the `iv:ciphertext` sits in a private
 * `SharedPreferences`. The raw token never touches disk in the clear and the wrapping key is
 * non-exportable. Drives the Keystore directly rather than depend on `security-crypto`.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One profile's plaintext, in heap, until [put]/[remove]/[clearAll] or process death. Keyed by
     * the exact `iv:ciphertext` it came from, so invalidation is the ciphertext, not a clock: [put]
     * mints a fresh IV per call, and a TTL could only ever expire an entry that is still correct.
     */
    private var cachedFor: String? = null
    private var cachedPacked: String? = null
    private var cachedToken: String? = null

    /** Bumped by [forget], read by [get] across its unwrap. The decrypt runs OUTSIDE the monitor, so
     *  a write path can land mid-unwrap; comparing the epoch on the way out is what stops an unwrap
     *  that began before an erase publishing after it. */
    private var epoch = 0L

    /** The Keystore HANDLE, not key material: its bytes never leave the TEE. */
    @Volatile private var cachedKey: SecretKey? = null

    override suspend fun get(profileId: String): String? {
        val packed = prefs.getString(profileId, null) ?: return null
        val began = synchronized(this) {
            if (cachedFor == profileId && cachedPacked == packed) return cachedToken
            epoch
        }
        val sep = packed.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(packed.substring(0, sep), Base64.NO_WRAP)
        val ct = Base64.decode(packed.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val token = String(cipher.doFinal(ct), Charsets.UTF_8)
        // The caller asked before the erase and is answered; the CACHE is what must not outlive the
        // ciphertext.
        synchronized(this) {
            if (epoch == began) {
                cachedFor = profileId
                cachedPacked = packed
                cachedToken = token
            }
        }
        return token
    }

    override suspend fun put(profileId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(profileId, packed).apply()
        forget()
    }

    override suspend fun remove(profileId: String) {
        prefs.edit().remove(profileId).apply()
        forget()
    }

    /** Drops every wrapped token AND the wrapping key; a fresh token minted later regenerates it. */
    override suspend fun clearAll() {
        prefs.edit().clear().apply()
        forget()
        cachedKey = null
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    /** The epoch bump extends the drop to an unwrap already in flight. */
    private fun forget() = synchronized(this) {
        cachedFor = null
        cachedPacked = null
        cachedToken = null
        epoch++
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            cachedKey = it.secretKey
            return it.secretKey
        }
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
        return gen.generateKey().also { cachedKey = it }
    }

    private companion object {
        const val PREFS = "t1dm_server_tokens"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "t1dm_server_token_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
