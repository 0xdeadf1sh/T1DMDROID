package com.t1dm.app.cgm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** On-disk: 12-byte GCM iv || ciphertext, raw into a BLOB; non-exportable, archives omit it. */
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

    /** Null, not a throw, on anything this key did not produce; the caller must rebind anyway. */
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

    /** Null when StrongBox was asked for and refused, so the caller retries in the TEE. */
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

        /** NOT the watch alias, and never deleted: that would retire every sensor it protects. */
        const val KEY_ALIAS = "t1dm_cgm_sensor_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }
}
