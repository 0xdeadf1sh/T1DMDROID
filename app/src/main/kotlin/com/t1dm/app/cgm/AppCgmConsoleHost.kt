package com.t1dm.app.cgm

import com.t1dm.app.di.AppContainer
import com.t1dm.cgm.Libre3CapturedChunk
import com.t1dm.cgm.Libre3Call
import com.t1dm.cgm.Libre3DataChar
import com.t1dm.core.model.CgmReading
import com.t1dm.feature.cgm.CgmCapturedChunk
import com.t1dm.feature.cgm.CgmConsoleHost
import com.t1dm.feature.cgm.CgmFrameResult
import com.t1dm.feature.cgm.CgmOpenedFrame
import com.t1dm.feature.cgm.CgmReadingStats

/** The CGM log console's reach into the app: the same calls the CGM panel's buttons make. */
class AppCgmConsoleHost(
    private val container: AppContainer,
    private val id: String,
    private val onSave: () -> Unit,
    private val onProvision: () -> Unit,
) : CgmConsoleHost {
    override fun echo(line: String) {
        container.echoCgmConsole(id, line)
    }

    override suspend fun readingStats(): CgmReadingStats = container.cgmReadingStats(id)

    override suspend fun lastReadings(n: Int): List<CgmReading> = container.recentCgmReadings(id, n)

    override fun connect() {
        container.activateCgm(id)
    }

    override fun disconnect() {
        container.deactivateCgm(id)
    }

    override fun reconnect() {
        container.reconnectCgm(id)
    }

    override fun fetch() {
        container.fetchCgmHistory(id)
    }

    override fun scan() {
        container.rescanCgm()
    }

    override fun makeMain() {
        container.makeAuthoritativeCgm(id)
    }

    override fun hide() {
        container.hideCgm(id)
    }

    override fun setWarmupMin(min: Int) {
        container.setCgmWarmupMin(id, min)
    }

    override fun activate() {
        container.activateCgmSensor(id)
    }

    override fun bind() {
        container.bindCgmSensor(id)
    }

    override fun repair() {
        container.repairCgmHistory(id)
    }

    override fun recoverKey() {
        container.recoverCgmKey(id)
    }

    override fun provision() = onProvision()

    override fun save() = onSave()

    override fun openFrames(chunks: List<CgmCapturedChunk>): CgmFrameResult<List<CgmOpenedFrame>> {
        val plane = container.libre3DataPlane(id) ?: return CgmFrameResult.NotLinked
        val captured = chunks.map { c -> Libre3CapturedChunk(Libre3DataChar.entries.firstOrNull { it.name == c.channel }, c.bytes) }
        return plane.openCaptured(captured).toFrameResult { opened ->
            opened.map { CgmOpenedFrame(it.chunk, it.sequence, it.kind, it.plaintext) }
        }
    }

    override fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray): CgmFrameResult<ByteArray> {
        val plane = container.libre3DataPlane(id) ?: return CgmFrameResult.NotLinked
        return plane.sealFrame(kind, sequence, plaintext).toFrameResult { it }
    }
}

private inline fun <T, R> Libre3Call<T>.toFrameResult(map: (T) -> R): CgmFrameResult<R> = when (this) {
    is Libre3Call.Ok -> CgmFrameResult.Done(map(value))
    is Libre3Call.Failed -> CgmFrameResult.Refused(reason)
    Libre3Call.AccountMismatch -> CgmFrameResult.Refused("account mismatch")
}
