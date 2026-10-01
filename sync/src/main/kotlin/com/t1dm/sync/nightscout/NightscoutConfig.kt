package com.t1dm.sync.nightscout

import com.t1dm.sync.TokenStore
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/** Only ever built when the bridge is enabled AND both halves are present. */
data class NightscoutConfig(val baseUrl: String, val secretSha1: String)

/** One copy of each kv key. */
object NightscoutKeys {
    const val URL = "ns.url"
    const val ENABLED = "ns.enabled"
    const val SECRET_ID = "nightscout"
}

/** Lower-case hex; the hash Nightscout's `api-secret` header carries. */
fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** 40 hex chars is taken as the digest, else hashed; a 40-hex plaintext fails loud at Test. */
fun normalizeApiSecret(input: String): String {
    val trimmed = input.trim()
    val isDigest = trimmed.length == 40 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isDigest) trimmed.lowercase() else sha1Hex(trimmed)
}

/** Secret lives in Keystore-backed TokenStore, never the Room DB, so backups can't carry it. */
class NightscoutConfigStore(
    private val getKv: suspend (String) -> String?,
    private val putKv: suspend (String, String, Long) -> Unit,
    private val tokens: TokenStore,
) {
    /** Read per request: bridge off takes effect on the next row, not at next launch. */
    suspend fun current(): NightscoutConfig? {
        if (!enabled()) return null
        val url = url()?.takeIf { it.isNotBlank() } ?: return null
        val secret = secret() ?: return null
        return NightscoutConfig(baseUrl = url, secretSha1 = secret)
    }

    suspend fun url(): String? = getKv(NightscoutKeys.URL)

    suspend fun enabled(): Boolean = getKv(NightscoutKeys.ENABLED) == "1"

    suspend fun hasSecret(): Boolean = secret() != null

    /** Unreadable counts as absent: a restore brings the wrapped blob but not its Keystore key. */
    private suspend fun secret(): String? {
        val stored = try {
            tokens.get(NightscoutKeys.SECRET_ID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "api-secret unreadable; treated as absent")
            null
        }
        return stored?.takeIf { it.isNotBlank() }
    }

    /** Blank secretInput KEEPS the stored secret: field is write-only, reads back blank. */
    suspend fun save(baseUrl: String, secretInput: String, enabled: Boolean, nowMs: Long) {
        putKv(NightscoutKeys.URL, baseUrl.trim().trimEnd('/'), nowMs)
        putKv(NightscoutKeys.ENABLED, if (enabled) "1" else "0", nowMs)
        if (secretInput.isNotBlank()) tokens.put(NightscoutKeys.SECRET_ID, normalizeApiSecret(secretInput))
    }

    suspend fun clearSecret() = tokens.remove(NightscoutKeys.SECRET_ID)

    private companion object {
        const val TAG = "NightscoutConfig"
    }
}
