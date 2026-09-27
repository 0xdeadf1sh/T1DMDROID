package com.t1dm.inference.backend

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ModelDescriptor
import java.io.File

/** Stands in for a .pte that is absent or won't load; runs nothing, so no forecast. */
class StubBackend : InferenceBackend {
    override val id = BackendId.STUB
    override val caps = BackendCaps()

    private class StubModel(override val id: String, override val caps: BackendCaps) : LoadedModel

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel = StubModel("stub", caps)

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput = error("no working .pte")

    override fun close(m: LoadedModel) = Unit
}
