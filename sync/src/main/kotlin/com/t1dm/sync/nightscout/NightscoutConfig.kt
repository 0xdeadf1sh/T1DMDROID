package com.t1dm.sync.nightscout

import com.t1dm.sync.TokenStore
import java.security.MessageDigest

/** Only ever built when the bridge is enabled AND both halves are present. */
data class NightscoutConfig(val baseUrl: String, val secretSha1: String)

/** One copy of each string, for the same reason `ReMirrorKeys` exists. */
object NightscoutKeys {
    const val URL = "ns.url"
    const val ENABLED = "ns.enabled"

    /** Not a server profile: must never appear in `server_profile` as a T1DMSERVER target. */
    const val SECRET_ID = "nightscout"
}

/** Lower-case hex; the hash Nightscout's `api-secret` header carries. */
fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Either form (plaintext or SHA-1); 40 hex=digest, else hashed; 40-hex plaintext fails loudly. */
fun normalizeApiSecret(input: String): String {
    val trimmed = input.trim()
    val isDigest = trimmed.length == 40 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isDigest) trimmed.lowercase() else sha1Hex(trimmed)
}

/** URL/flag in kv, secret in Keystore [TokenStore]; secret never enters Room, URL never logged. */
class NightscoutConfigStore(
    private val getKv: suspend (String) -> String?,
    private val putKv: suspend (String, String, Long) -> Unit,
    private val tokens: TokenStore,
) {
    /** Read per request, so switching the bridge off takes effect next row, not next launch. */
    suspend fun current(): NightscoutConfig? {
        if (!enabled()) return null
        val url = url()?.takeIf { it.isNotBlank() } ?: return null
        val secret = tokens.get(NightscoutKeys.SECRET_ID)?.takeIf { it.isNotBlank() } ?: return null
        return NightscoutConfig(baseUrl = url, secretSha1 = secret)
    }

    suspend fun url(): String? = getKv(NightscoutKeys.URL)

    suspend fun enabled(): Boolean = getKv(NightscoutKeys.ENABLED) == "1"

    suspend fun hasSecret(): Boolean = !tokens.get(NightscoutKeys.SECRET_ID).isNullOrBlank()

    /** Blank [secretInput] KEEPS the secret: field is write-only, reads blank; erase disarms it. */
    suspend fun save(baseUrl: String, secretInput: String, enabled: Boolean, nowMs: Long) {
        putKv(NightscoutKeys.URL, baseUrl.trim().trimEnd('/'), nowMs)
        putKv(NightscoutKeys.ENABLED, if (enabled) "1" else "0", nowMs)
        if (secretInput.isNotBlank()) tokens.put(NightscoutKeys.SECRET_ID, normalizeApiSecret(secretInput))
    }

    suspend fun clearSecret() = tokens.remove(NightscoutKeys.SECRET_ID)
}
