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
 * Per-profile `rw` token at rest (Phase 3: "token in a Keystore-backed TokenStore").
 * The token is the single credential the phone holds; it must never sit in the keep-forever Room DB
 * (which a backup/export could leak), so it lives here keyed by profile id. The interface is tiny so
 * tests substitute an in-memory implementation.
 */
interface TokenStore {
    suspend fun get(profileId: String): String?
    suspend fun put(profileId: String, token: String)
    suspend fun remove(profileId: String)

    /** Burn EVERY stored token (issue 5, app reset) — every profile's `rw` credential at once. */
    suspend fun clearAll()
}

/** Deterministic, non-persistent store for host tests and previews. */
class InMemoryTokenStore(seed: Map<String, String> = emptyMap()) : TokenStore {
    private val map = HashMap(seed)
    override suspend fun get(profileId: String): String? = map[profileId]
    override suspend fun put(profileId: String, token: String) { map[profileId] = token }
    override suspend fun remove(profileId: String) { map.remove(profileId) }
    override suspend fun clearAll() { map.clear() }
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

    /**
     * The last unwrap, keyed by the exact `iv:ciphertext` it came from.
     *
     * WHY. Every outbox request resolves the active endpoint, so one drain of a full batch performed
     * up to 200 AndroidKeyStore handshakes and 200 TEE-backed GCM decrypts to recover a string that
     * had not changed since the profile was configured. Decryption under a fixed key is a function of
     * the ciphertext, so remembering its result is exact: a hit is byte-identical to the decrypt it
     * replaces.
     *
     * INVALIDATION IS THE CIPHERTEXT ITSELF, not a clock. [put] mints a fresh random IV per call, so a
     * re-tokened profile never matches; [remove] and [clearAll] drop the entry outright; a different
     * profile id reads a different pref and misses. There is deliberately no TTL — a TTL could only
     * expire an entry that is still correct, and could never expire one that is stale.
     *
     * LIFETIME AND POSTURE. One profile's plaintext, in this process's heap, from the first request
     * that needs it until [put]/[remove]/[clearAll] or process death — and the store is a lazy
     * singleton, so that is the app process's lifetime. This does not open a new exposure class: the
     * stream client holds the same token inside the `/v1/stream?token=` URL of a live WebSocket for as
     * long as it is connected, which is the steady state, and each HTTP call holds it in an
     * `Authorization` header. What changes is that the plaintext is now reachable for the whole
     * process rather than only while a request is in flight. Nothing here weakens the at-rest
     * guarantee this class exists for: the wrapping key stays non-exportable, and only ciphertext ever
     * touches disk — so a pulled backup, the threat this class names, is unaffected.
     */
    private var cachedFor: String? = null
    private var cachedPacked: String? = null
    private var cachedToken: String? = null

    /** Bumped by [forget], read by [get] across its unwrap. The decrypt runs OUTSIDE the monitor —
     *  it is a Keystore round trip, and holding a lock across a binder call to serialise the very
     *  requests this cache exists to make cheap would defeat it — so a write path can land in the
     *  middle of one. Comparing the epoch on the way out is what keeps [forget]'s claim true: an
     *  unwrap that began before an erase publishes nothing after it. */
    private var epoch = 0L

    /** The Keystore HANDLE, not key material — [key] is a daemon round trip per call, and the
     *  `AndroidKeyStoreSecretKey` it returns is a reference whose bytes never leave the TEE. */
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
        // ciphertext. A [put], [remove] or [clearAll] that landed during the unwrap has already
        // dropped the entry this would re-publish — and after a full erase there is no ciphertext
        // left for it to be the plaintext of.
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

    /** Full-erase (issue 5): drop every wrapped token AND the AndroidKeyStore wrapping key, so no
     *  ciphertext and no key survive the reset. A fresh token minted later regenerates the key. */
    override suspend fun clearAll() {
        prefs.edit().clear().apply()
        forget()
        cachedKey = null
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    /** Drop the remembered plaintext. Called on every write path, and the epoch bump extends that to
     *  the unwrap already in flight, so no unwrap outlives its ciphertext. */
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
