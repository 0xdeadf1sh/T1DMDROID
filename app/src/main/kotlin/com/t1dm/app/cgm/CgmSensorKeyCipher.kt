package com.t1dm.app.cgm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a CGM sensor's opaque secret at rest under an AndroidKeyStore AES-256-GCM key.
 *
 * The same shape as the watch link's key cipher, and deliberately a SEPARATE alias. The two protect
 * unrelated things with unrelated lifetimes: the watch key is burned by the full erase, and this one
 * must survive it — a sensor still on the patient's arm needs its secret to stay readable, and for some
 * families it is the only copy in existence. Sharing an alias would mean one deletion took both.
 *
 * StrongBox where the platform has it, the TEE otherwise. On-disk form is `iv || ciphertext`, with the
 * 12-byte GCM IV first: the column is a BLOB, so nothing is base64'd on the way in.
 *
 * The key is non-exportable, so a blob sealed here is readable only by this install of this app. That is
 * exactly why the archive does not carry these rows.
 */
class CgmSensorKeyCipher {

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        val out = ByteArray(iv.size + ct.size)
        iv.copyInto(out)
        ct.copyInto(out, iv.size)
        return out
    }

    /**
     * Reverses [seal]. Returns null on anything this key did not produce — a truncated blob, a failed
     * tag, a row written under a key that has since been replaced.
     *
     * Null rather than a throw because the caller's honest answer to an unreadable secret is "this sensor
     * needs rebinding", which is a state it must handle anyway. The bytes are left on disk untouched.
     */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= GCM_IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generate(strongBox = true) ?: generate(strongBox = false)!!
    }

    /** Null when StrongBox was asked for and the platform refuses it, so the caller retries in the TEE. */
    private fun generate(strongBox: Boolean): SecretKey? {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        gen.init(spec)
        return try {
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) null else throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * NOT the watch alias. Deleting this one retires every sensor whose secret it protects, so
         * nothing deletes it — there is deliberately no `deleteKey()` here, unlike on the watch side.
         */
        const val KEY_ALIAS = "t1dm_cgm_sensor_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }
}
