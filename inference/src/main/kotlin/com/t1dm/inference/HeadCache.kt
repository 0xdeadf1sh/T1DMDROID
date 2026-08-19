package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.LoraWeights
import timber.log.Timber
import kotlin.math.abs

/**
 * The re-runnable BG heads, one per model, opened from each artifact's side file and kept for as
 * long as the model is loaded.
 *
 * A head is only ever needed when an adapter is attached: without one the graph's own `head_raw`
 * is the fast path and this is dead weight. What makes it safe to use at all is the parity check
 * — with no adapter the head must reproduce the graph's output on the same hidden states. A head
 * paired with the wrong graph does not fail: it produces a finite, plausible, wrong fan. So a
 * model whose head disagrees is marked [Unusable] and refuses every adapter rather than quietly
 * forecasting differently from the one the app has been storing all along.
 */
class HeadCache(private val native: NativeCore) {

    /** What a model's head turned out to be, once. */
    sealed interface State {
        /** Opened and agreeing with the graph. */
        data class Ready(val head: NativeHead, val maxDelta: Double) : State

        /** No side file, or the descriptor declared none — the model runs, adapters do not. */
        data object Absent : State

        /** Present but not the graph's own head, or unparseable. Adapters are refused. */
        data class Unusable(val why: String) : State
    }

    private val states = HashMap<String, State>()

    /** The head for [bundle], opened on first use. Never throws. */
    @Synchronized
    fun stateOf(bundle: ModelBundle): State = states.getOrPut(bundle.id) { open(bundle) }

    /** Drop and release [modelId]'s head — call when its artifact is replaced or deleted. */
    @Synchronized
    fun evict(modelId: String) {
        (states.remove(modelId) as? State.Ready)?.head?.close()
    }

    @Synchronized
    fun closeAll() {
        states.values.filterIsInstance<State.Ready>().forEach { it.head.close() }
        states.clear()
    }

    /** Record a verdict, releasing any head it replaces — the native buffer is ours to free. */
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

    /**
     * Confirm a freshly-opened head reproduces the graph's own `head_raw` from the graph's own
     * `slot_hidden`, and record the worst disagreement. Called once per model, on the first run
     * that has both tensors in hand.
     *
     * The tolerance is loose in absolute terms because `head_raw` is risk-space coefficients
     * rather than a forecast, and the two paths differ in nothing but fp32-vs-fp64 arithmetic:
     * anything above this is a different head, not rounding.
     */
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

    /** Attach [w] (or detach with null) to [bundle]'s head; false when the model has no usable one. */
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
