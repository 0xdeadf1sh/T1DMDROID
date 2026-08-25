package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.LoraWeights
import timber.log.Timber
import kotlin.math.abs

/** Re-runnable BG heads, one per model, opened from the artifact's side file; only needed when an
 *  adapter is attached. A head paired with the wrong graph produces a finite, plausible, WRONG fan,
 *  so one that fails the parity check is [Unusable] and refuses every adapter. */
class HeadCache(private val native: NativeCore) {

    sealed interface State {
        data class Ready(val head: NativeHead, val maxDelta: Double) : State

        /** No side file, or none declared: the model runs, adapters do not. */
        data object Absent : State

        data class Unusable(val why: String) : State
    }

    private val states = HashMap<String, State>()

    /** Opened on first use. Never throws. */
    @Synchronized
    fun stateOf(bundle: ModelBundle): State = states.getOrPut(bundle.id) { open(bundle) }

    /** Call when the artifact is replaced or deleted. */
    @Synchronized
    fun evict(modelId: String) {
        (states.remove(modelId) as? State.Ready)?.head?.close()
    }

    @Synchronized
    fun closeAll() {
        states.values.filterIsInstance<State.Ready>().forEach { it.head.close() }
        states.clear()
    }

    /** Releases any head it replaces; the native buffer is ours to free. */
    private fun set(modelId: String, next: State) {
        (states[modelId] as? State.Ready)
            ?.takeIf { (next as? State.Ready)?.head !== it.head }
            ?.head?.close()
        states[modelId] = next
    }

    private fun open(bundle: ModelBundle): State {
        val spec = bundle.descriptor.head ?: return State.Absent
        val file = bundle.head ?: return State.Absent
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return State.Unusable("head file unreadable: ${it.message}")
        }
        val head = native.headOpen(bytes, spec)
            ?: return State.Unusable("head file does not match its descriptor")
        return State.Ready(head, maxDelta = Double.NaN)
    }

    /** Once per model. [TOL] is loose because `head_raw` is risk-space coefficients and the two
     *  paths differ only in fp32-vs-fp64; above it is a different head, not rounding. */
    @Synchronized
    fun verify(bundle: ModelBundle, slotHidden: FloatArray?, headRaw: FloatArray, mSlots: Int) {
        val state = states[bundle.id] as? State.Ready ?: return
        if (!state.maxDelta.isNaN()) return // already verified
        val hidden = slotHidden ?: run {
            set(bundle.id, State.Unusable("the graph emits no slot_hidden; nothing to adapt"))
            return
        }
        val had = state.head.hasLora()
        if (had) state.head.setLora(null)
        val ours = runCatching { state.head.forward(hidden.map { it.toDouble() }, mSlots) }.getOrElse {
            set(bundle.id, State.Unusable("head forward failed: ${it.message}"))
            return
        }
        var worst = 0.0
        if (ours.size != headRaw.size) {
            set(bundle.id, State.Unusable("head emits ${ours.size} values, the graph ${headRaw.size}"))
            return
        }
        for (i in ours.indices) worst = maxOf(worst, abs(ours[i] - headRaw[i].toDouble()))
        set(
            bundle.id,
            if (worst.isFinite() && worst <= TOL) {
                Timber.tag(TAG).i("head parity for %s: max|Δ| = %.3e", bundle.id, worst)
                State.Ready(state.head, worst)
            } else {
                State.Unusable("head disagrees with the graph by %.3e".format(worst))
            },
        )
    }

    /** Null detaches. False when the model has no usable head. */
    @Synchronized
    fun attach(bundle: ModelBundle, w: LoraWeights?): Boolean {
        val state = stateOf(bundle) as? State.Ready ?: return false
        return runCatching { state.head.setLora(w) }.isSuccess
    }

    private companion object {
        const val TAG = "HeadCache"
        const val TOL = 1e-3
    }
}
