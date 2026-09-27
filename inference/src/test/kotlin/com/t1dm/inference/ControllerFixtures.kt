package com.t1dm.inference

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ChannelStat
import com.t1dm.core.model.HeadSpec
import com.t1dm.core.model.KovatchevParams
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.nativecore.StubNativeCore
import com.t1dm.inference.backend.BackendCaps
import com.t1dm.inference.backend.GraphOutput
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.inference.backend.InferenceBackend
import com.t1dm.inference.backend.LoadedModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.FloatBuffer

internal const val FIXTURE_NOW = 1_700_000_000_000L

internal class FixtureDispatchers(
    override val inference: CoroutineDispatcher = Dispatchers.Unconfined,
    override val default: CoroutineDispatcher = Dispatchers.Unconfined,
) : T1dmDispatchers {
    override val main = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val game = Dispatchers.Unconfined
}

internal object EmptyHistory : BgHistoryProvider {
    override suspend fun dosingBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
    override suspend fun recentBgSeries(maxSteps: Int, minSteps: Int): BgSeries? = null
}

internal class RecordingPredictions(private val last: List<ModelPrediction>? = null) : PredictionStore {
    val persisted = mutableListOf<ModelPrediction>()

    override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) {
        persisted += predictions
    }

    override suspend fun loadLast(): List<ModelPrediction>? = last
}

/** A fresh copy per parse, as the crate returns: identity changes on every discover. */
internal open class DescriptorCore(private val descriptor: ModelDescriptor = fixtureDescriptor()) :
    StubNativeCore() {
    override fun parseDescriptor(json: String): ModelDescriptor? = descriptor.copy()
}

/** Loads anything whose .pte exists; [run] answers an empty head. */
internal open class FakeXnnpack : InferenceBackend {
    override val id = BackendId.EXECUTORCH_XNNPACK_FP32
    override val caps = BackendCaps()

    private class Handle(override val id: String, override val caps: BackendCaps) : LoadedModel

    override fun load(desc: ModelDescriptor, pte: File): LoadedModel = Handle(pte.name, caps)

    override fun run(m: LoadedModel, x: GraphTensors): GraphOutput = GraphOutput(FloatArray(0))

    override fun close(m: LoadedModel) = Unit
}

internal fun fixtureDescriptor(head: HeadSpec? = null) = ModelDescriptor(
    bg = ChannelStat(0.0, 1.0),
    carb = ChannelStat(0.0, 1.0),
    insulin = ChannelStat(0.0, 1.0),
    ropeBase = 1000,
    quantileSpreadMin = 1e-3,
    negFill = -30000.0,
    predictionHorizonHours = 2,
    maxContextPatches = 8,
    minContextPatches = 4,
    patchSize = 6,
    nInputFeatures = 4,
    seqLen = 12,
    maxMaskedPatches = 12,
    maskMaxSpans = 3,
    maskSpanMax = 8,
    dModel = 4,
    archVersion = "risk-v5",
    kovatchev = KovatchevParams(
        scale = 2.2211457449985317,
        power = 1.084,
        offset = 5.540076976170212,
        bgClampMin = 40.0,
        bgClampMax = 400.0,
    ),
    conformalEnabled = false,
    head = head,
)

internal fun fixtureHeadSpec(file: String) = HeadSpec(
    file = file,
    dtype = "f32",
    byteOrder = "le",
    activation = "gelu",
    sha256 = "0".repeat(64),
    dModel = 4,
    hidden = 4,
    outDim = 7,
    decoder = "last",
    tensors = emptyList(),
)

/** `<id>.descriptor.json` naming `<id>.xnnpack.pte`; [pte] false leaves the artifact out. */
internal fun seedModel(dir: File, id: String, pte: Boolean = true) {
    File(dir, "$id.descriptor.json").writeText("""{"id":"$id","artifact":"$id.xnnpack.pte"}""")
    if (pte) File(dir, "$id.xnnpack.pte").writeText("pte-bytes")
}

internal fun flatSeries(n: Int = 48, anchorTsMs: Long = FIXTURE_NOW) = BgSeries(
    mgdl = DoubleArray(n) { 120.0 },
    anchorTsMs = anchorTsMs,
    gridStartMs = anchorTsMs - (n - 1) * 300_000L,
)

internal fun emptyTensors() = GraphTensors(
    patches = FloatBuffer.allocate(0),
    mask = FloatBuffer.allocate(0),
    slotSel = FloatBuffer.allocate(0),
    t = 0,
    patchDim = 0,
    mSlots = 0,
)
