package com.t1dm.sync.nightscout

import com.t1dm.sync.TokenStore
import java.security.MessageDigest

/** The resolved bridge target: where to post, and the `api-secret` value to post with. Only ever
 *  built when the bridge is enabled AND both halves are present — see [NightscoutConfigStore.current]. */
data class NightscoutConfig(val baseUrl: String, val secretSha1: String)

/** The kv keys and the [TokenStore] id this bridge keeps its configuration under. One copy of each
 *  string, here, for the same reason `ReMirrorKeys` exists. */
object NightscoutKeys {
    const val URL = "ns.url"
    const val ENABLED = "ns.enabled"

    /** The [TokenStore] profile id the api-secret is wrapped under. It is not a server profile and
     *  must never appear in `server_profile`: nothing may offer it as a T1DMSERVER sync target. */
    const val SECRET_ID = "nightscout"
}

/** Lower-case hex SHA-1, the hash Nightscout's `api-secret` header has always carried. */
fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * Accept EITHER form of the secret.
 *
 * A Nightscout-compatible host typically shows the operator both its plaintext token and that token's
 * SHA-1, and which one lands in the paste buffer is a coin toss. Forty hex characters is already the
 * digest and is taken as-is; anything else is hashed. The distinction is safe to make automatically
 * because a plaintext secret that happened to be 40 hex characters would hash to something the server
 * would reject outright — a loud failure at the Test button, not a silent mis-send.
 */
fun normalizeApiSecret(input: String): String {
    val trimmed = input.trim()
    val isDigest = trimmed.length == 40 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isDigest) trimmed.lowercase() else sha1Hex(trimmed)
}

/**
 * Where the bridge's configuration lives: the URL and the on/off flag in `kv`, the secret in the
 * Keystore-backed [TokenStore] beside the T1DMSERVER token.
 *
 * The secret is a credential and gets the credential treatment — it never enters the keep-forever
 * Room DB, so a pulled backup or a config export cannot carry it. The URL is not a secret but is
 * personal to one account, so it is stored rather than compiled in and never logged.
 *
 * kv access arrives as function references rather than a repository handle, as [com.t1dm.sync.ReMirrorLedger]
 * does, so every judgement here is testable on the host JVM without Room.
 */
class NightscoutConfigStore(
    private val getKv: suspend (String) -> String?,
    private val putKv: suspend (String, String, Long) -> Unit,
    private val tokens: TokenStore,
) {
    /** The live target, or null when the bridge is off or half-configured. The drainer reads this per
     *  request, so switching the bridge off takes effect on the next row rather than at next launch. */
    suspend fun current(): NightscoutConfig? {
        if (!enabled()) return null
        val url = url()?.takeIf { it.isNotBlank() } ?: return null
        val secret = tokens.get(NightscoutKeys.SECRET_ID)?.takeIf { it.isNotBlank() } ?: return null
        return NightscoutConfig(baseUrl = url, secretSha1 = secret)
    }

    suspend fun url(): String? = getKv(NightscoutKeys.URL)

    suspend fun enabled(): Boolean = getKv(NightscoutKeys.ENABLED) == "1"

    suspend fun hasSecret(): Boolean = !tokens.get(NightscoutKeys.SECRET_ID).isNullOrBlank()

    /**
     * Persist the configuration. A blank [secretInput] KEEPS the stored secret — the field it comes
     * from is write-only and reads back blank, so treating blank as "erase" would silently disarm the
     * bridge every time the user edited the URL.
     */
    suspend fun save(baseUrl: String, secretInput: String, enabled: Boolean, nowMs: Long) {
        putKv(NightscoutKeys.URL, baseUrl.trim().trimEnd('/'), nowMs)
        putKv(NightscoutKeys.ENABLED, if (enabled) "1" else "0", nowMs)
        if (secretInput.isNotBlank()) tokens.put(NightscoutKeys.SECRET_ID, normalizeApiSecret(secretInput))
    }

    /** Forget the secret outright (the user clearing the bridge, and the app-wide erase). */
    suspend fun clearSecret() = tokens.remove(NightscoutKeys.SECRET_ID)
}
