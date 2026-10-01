package com.t1dm.cgm

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.NfcV
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Reader-mode NfcV holder for one provisioning session (§8): reader mode on, wait for the tag,
 * keep it connected across the patch-info and switch transceives, close on the way out.
 */
class AndroidLibre3NfcVLink(private val activity: Activity) : Libre3NfcProvision.NfcVLink {

    private var held: NfcV? = null

    override suspend fun connect() {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: throw IOException("no NFC adapter")
        val presented = CompletableDeferred<NfcV>()
        readerModeOwner.set(this)
        try {
            adapter.enableReaderMode(activity, { tag -> presented.complete(NfcV.get(tag)) }, FLAGS, null)
        } catch (e: Exception) {
            throw IOException("reader mode unavailable", e)
        }
        val nfcV = withTimeoutOrNull(TAP_TIMEOUT_MS) { presented.await() }
        if (nfcV == null) {
            releaseReaderMode()
            throw IOException("no tag presented within ${TAP_TIMEOUT_MS / 1000} s")
        }
        try {
            nfcV.connect()
        } catch (e: Exception) {
            runCatching { nfcV.close() }
            releaseReaderMode()
            throw IOException("tag connect failed", e)
        }
        held = nfcV
    }

    override fun transceive(command: ByteArray): ByteArray =
        held?.transceive(command) ?: throw IOException("no tag held")

    override fun close() {
        held?.let { nfcV ->
            runCatching { nfcV.close() }.onFailure { Log.w(TAG, "tag close: ${it.message}") }
        }
        held = null
        runCatching { releaseReaderMode() }
            .onFailure { Log.w(TAG, "reader mode off: ${it.message}") }
    }

    /** Only the newest attempt may turn reader mode off; an orphan would end a newer wait. */
    private fun releaseReaderMode() {
        if (!readerModeOwner.compareAndSet(this, null)) return
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    private companion object {
        const val TAG = "Libre3Nfc"

        /** The link whose reader-mode callback is live; each enableReaderMode replaces the last. */
        val readerModeOwner = AtomicReference<AndroidLibre3NfcVLink?>(null)

        /** The user opens the sheet, then walks the phone to the sensor; generous, then fail. */
        const val TAP_TIMEOUT_MS = 180_000L

        val FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}