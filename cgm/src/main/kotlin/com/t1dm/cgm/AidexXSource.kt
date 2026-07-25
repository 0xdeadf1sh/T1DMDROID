package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AiDEX X [CgmSource] (§3.1). It does not own the radio — a single shared
 * [BleAdvertScanner], driven by [AidexXSourceRegistry], routes recognized adverts here via
 * [ingest]. Each accepted advert is decoded, classified, grid-stamped, persisted through the
 * [CgmRepository], and re-emitted on [readings].
 */
class AidexXSource(
    override val descriptor: CgmSourceDescriptor,
    nativeCore: NativeCore,
    private val repository: CgmRepository,
) : CgmSource {

    private val _status = MutableStateFlow(CgmSourceStatus.Idle)
    override val status: StateFlow<CgmSourceStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 64)
    override fun readings(): Flow<CgmReading> = _readings.asSharedFlow()

    private val pipeline = CgmPipeline(
        sourceId = descriptor.id,
        nativeCore = nativeCore,
        classifier = ReadingClassifier(descriptor.warmupWindowMin),
    )

    /** Called when scanning begins, before any advert lands. */
    fun onScanning() {
        if (_status.value == CgmSourceStatus.Idle) _status.value = CgmSourceStatus.Scanning
    }

    /** Run one captured advert through the pipeline, persist its outputs, and emit readings. */
    suspend fun ingest(raw: RawAdvert) {
        val out = pipeline.process(raw)

        out.glucosePayload?.let { payload ->
            repository.insertRawAdvert(
                sourceId = descriptor.id,
                rxWallMs = raw.rxWallMs,
                rssi = raw.rssi,
                payload = payload,
                crcValid = out.crcValid,
                minFromStart = out.minFromStart,
            )
        }

        for (reading in out.readings) {
            repository.upsertReading(reading)
            updateStatus(reading)
            _readings.emit(reading)
        }
    }

    /** Loss-of-signal transition, driven by the service's wall-clock watchdog (§3.6-A). */
    fun markSignalLost() {
        if (_status.value == CgmSourceStatus.Live || _status.value == CgmSourceStatus.Warmup) {
            _status.value = CgmSourceStatus.SignalLost
        }
    }

    private fun updateStatus(reading: CgmReading) {
        if (reading.provenance != ReadingProvenance.MEASURED) return
        _status.value = when (reading.flag) {
            ReadingFlag.WARMUP -> CgmSourceStatus.Warmup
            ReadingFlag.NORMAL -> CgmSourceStatus.Live
            ReadingFlag.INVALID -> _status.value   // never emitted, but total-when
        }
    }
}
