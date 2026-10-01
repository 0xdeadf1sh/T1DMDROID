package com.t1dm.cgm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One NFC tap: patch-info 0xa1 → activate 0xa0 or switch-receiver 0xa8 → [Libre3SensorState]
 * (PLAN_T1DMDROID.md §8). The NfcV plumbing is behind [NfcVLink] so the flow runs on host tests.
 */
class Libre3NfcProvision(
    private val link: NfcVLink,
    private val native: Libre3Native,
    private val nowMs: () -> Long,
) {

    /** Holds one ISO 15693 tag for the length of a provisioning session. */
    interface NfcVLink {
        /** Reader mode on; suspends until a tag, cancellable; throws when none comes in time. */
        suspend fun connect()

        /** Raw reply including the leading flags byte. */
        fun transceive(command: ByteArray): ByteArray

        fun close()
    }

    sealed interface Outcome {
        data class Provisioned(val state: Libre3SensorState) : Outcome

        /** Sensor answered 0xB1: its stored receiver fold is another account's. */
        data object AccountMismatch : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private val busy = AtomicBoolean(false)

    suspend fun provision(accountId: String, region: Libre3Region): Outcome {
        if (!busy.compareAndSet(false, true)) return Outcome.Failed("a tap is already running")
        try {
            link.connect()
            val receiverId = native.receiverId(accountId.trim().lowercase(), region)
                ?: return Outcome.Failed("receiver fold unavailable")
            val patch = when (val call = patchInfo()) {
                is Libre3Call.Ok -> call.value
                Libre3Call.AccountMismatch -> return Outcome.AccountMismatch
                is Libre3Call.Failed -> return Outcome.Failed(call.reason)
            }
            Log.i(TAG, "patch-info: state=${patch.sensorState} serial=${patch.serial} fw=${patch.fw}")
            // Last exit before activate/switch, which the sensor cannot undo.
            currentCoroutineContext().ensureActive()
            val timeS = nowMs() / 1000L
            val command = when (native.provisionAction(patch.sensorState)) {
                Libre3ProvisionAction.Activate -> native.nfcActivateCmd(timeS, receiverId)
                Libre3ProvisionAction.SwitchReceiver -> native.nfcSwitchCmd(timeS, receiverId)
                null -> null
            } ?: return Outcome.Failed("sensor state ${patch.sensorState} has no command")
            val reply = when (val call = native.nfcParseSwitchResponse(link.transceive(command))) {
                is Libre3Call.Ok -> call.value
                Libre3Call.AccountMismatch -> return Outcome.AccountMismatch
                is Libre3Call.Failed -> return Outcome.Failed(call.reason)
            }
            val state = Libre3SensorState.provisioned(patch, reply, receiverId, region, nowMs())
            Log.i(TAG, "provisioned serial=${state.serial} at ${state.bleAddressDisplay}")
            return Outcome.Provisioned(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tap failed: ${e.message}")
            return Outcome.Failed(e.message ?: "NFC tap failed")
        } finally {
            link.close()
            busy.set(false)
        }
    }

    private fun patchInfo(): Libre3Call<Libre3PatchInfo> {
        val command = native.nfcPatchInfoCmd()
            ?: return Libre3Call.Failed("patch-info command unavailable")
        val reply = try {
            link.transceive(command)
        } catch (e: Exception) {
            return Libre3Call.Failed("patch-info transceive: ${e.message}")
        }
        Log.d(TAG, "patch-info reply ${reply.toHex()}")
        // Rust skips the ISO flags byte and the sensor's 0xa5 echo run itself (§5.9, live EU tap).
        return native.nfcParsePatchInfo(reply)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "Libre3Nfc"
    }
}