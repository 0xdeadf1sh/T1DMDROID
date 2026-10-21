package com.t1dm.inference.backend

import com.t1dm.inference.ZooDescriptor
import com.t1dm.inference.ZooRunner
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/** SPEC/zoo.md §1; the loaded Module isn't concurrency-safe, so callers serialise. */
class ExecuTorchZooRunner(private val desc: ZooDescriptor, pte: File) : ZooRunner {
    private val module: Module

    init {
        require(pte.exists()) { "pte artifact missing: ${pte.absolutePath}" }
        module = Module.load(pte.absolutePath, Module.LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS)
    }

    override fun run(bg: FloatArray, insulin: FloatArray, carb: FloatArray): FloatArray {
        val n = (desc.ctxSteps + desc.zoneSteps).toLong()
        val out = module.forward(
            EValue.from(Tensor.fromBlob(bg, longArrayOf(1, desc.ctxSteps.toLong()))),
            EValue.from(Tensor.fromBlob(insulin, longArrayOf(1, n))),
            EValue.from(Tensor.fromBlob(carb, longArrayOf(1, n))),
        )
        require(out.isNotEmpty() && out[0].isTensor) { "zoo model returned no median tensor" }
        return out[0].toTensor().dataAsFloatArray
    }

    override fun close() {
        runCatching { module.destroy() }
    }
}
