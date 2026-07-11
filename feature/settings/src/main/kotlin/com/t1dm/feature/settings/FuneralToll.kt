package com.t1dm.feature.settings

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * A runtime-synthesised funeral bell — the app bundles no audio assets, so a single strike is
 * generated as 16-bit mono PCM at construction and played through [AudioTrack] on a background
 * thread. The strike is an inharmonic bell: a low fundamental with a handful of stretched, unequally
 * decaying partials, a near-instant attack and a long exponential tail, so it reads as a slow, solemn
 * toll rather than a pure tone. Every [AudioTrack] touch is wrapped so a device without audio focus
 * stays silent instead of crashing the rite.
 */
class FuneralToll(private val sampleRate: Int = 44100) {
    // Cached so overlapping strikes ring over one another's tails, as a real bell does.
    private val executor = Executors.newCachedThreadPool()
    private val pcm: ShortArray = synthesizeStrike()

    /** Play one bell strike. Never throws. */
    fun toll() {
        runCatching {
            executor.execute { runCatching { playOnce() } }
        }
    }

    fun release() {
        runCatching { executor.shutdownNow() }
    }

    private fun playOnce() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        // Let the tail ring out, then reclaim the track (interrupted on release()).
        Thread.sleep(pcm.size * 1000L / sampleRate + 120L)
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun synthesizeStrike(): ShortArray {
        val decaySeconds = 2.6
        val n = (sampleRate * decaySeconds).toInt()
        val out = DoubleArray(n)
        val fundamental = 72.0
        // Struck-bell partial ratios (hum / prime / tierce-ish / quint-ish); each partial keeps its own
        // amplitude and decay — the upper partials fade first, as a cast bell's do.
        val ratios = doubleArrayOf(1.0, 2.76, 5.40, 8.93)
        val amps = doubleArrayOf(1.0, 0.6, 0.4, 0.25)
        val decays = doubleArrayOf(2.6, 1.9, 1.2, 0.8)
        val attackSeconds = 0.005
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val attack = if (t < attackSeconds) t / attackSeconds else 1.0
            var s = 0.0
            for (p in ratios.indices) {
                s += amps[p] * exp(-t / decays[p]) * sin(2.0 * PI * fundamental * ratios[p] * t)
            }
            out[i] = attack * s
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val gain = 0.92 / peak
        return ShortArray(n) { (out[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }
}
