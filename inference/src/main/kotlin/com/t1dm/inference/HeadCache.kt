package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.NativeHead
import com.t1dm.core.model.LoraWeights
import timber.log.Timber
import kotlin.math.abs

/** Re-runnable heads/model; wrong graph gives plausible WRONG fan; failed parity ⇒ [Unusable]. */
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

    /** True while a head owes a parity check; caller pays for step states only when needed. */
    @Synchronized
    fun needsVerify(bundle: ModelBundle): Boolean =
        (stateOf(bundle) as? State.Ready)?.maxDelta?.isNaN() == true

    /** Once/model; [stepStates]=step_states of hidden, null if none; [TOL] fp32-fp64 loose. */
    @Synchronized
    fun verify(bundle: ModelBundle, stepStates: List<Double>?, headRaw: FloatArray, mSlots: Int) {
        val state = states[bundle.id] as? State.Ready ?: return
        if (!state.maxDelta.isNaN()) return // already verified
        val steps = stepStates ?: run {
            set(bundle.id, State.Unusable("the graph emits no hidden state; nothing to adapt"))
            return
        }
        val had = state.head.hasLora()
        if (had) state.head.setLora(null)
        val ours = runCatching { state.head.forward(steps, mSlots) }.getOrElse {
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
