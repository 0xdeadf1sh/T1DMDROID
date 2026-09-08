package com.t1dm.feature.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.locks.LockSupport

/** Blocking AudioTrack.write is the clock: no sleep/timer; game thread writes volatile only. */
class GameAudio private constructor(
    private val audioManager: AudioManager,
    private val track: AudioTrack,
    private val burstFrames: Int,
    val synth: GameSynth,
) {
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(gameAttributes())
            // Duck on a ramp instead: the platform's own ducking is a step.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                focusGain = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> 1f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
                    else -> 0f
                }
                applyGain()
            }
            .build()

    private val buffer = FloatArray(burstFrames)

    @Volatile
    private var running = true

    @Volatile
    private var active = false

    @Volatile
    private var focusGain = 1f

    private val thread = Thread({ run() }, "t1dm-game-audio").apply {
        isDaemon = true
        start()
    }

    fun resume() {
        if (!running || active) return
        // A granted request does not call the listener, so the gain is re-derived here.
        val granted = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusGain = if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
        active = true
        applyGain()
        LockSupport.unpark(thread)
    }

    /** Alarm interlock's audio half; fade is the synth's master ramp, silence lands ~30ms later. */
    fun release() {
        if (!active) return
        active = false
        synth.master = 0f
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    /** Generator thread releases itself one burst later, so nothing frees under a native write. */
    fun close() {
        if (!running) return
        running = false
        active = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        LockSupport.unpark(thread)
    }

    private fun applyGain() {
        synth.master = if (active) focusGain else 0f
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Prime with silence before play(), so the very first burst is never an underrun.
        buffer.fill(0f)
        repeat(2) { track.write(buffer, 0, burstFrames, AudioTrack.WRITE_BLOCKING) }
        runCatching { track.play() }

        var paused = false
        while (running) {
            if (!active && synth.master == 0f && !paused) {
                // Let the fade actually reach the speaker before parking.
                var drain = DRAIN_BURSTS
                while (drain-- > 0 && running) {
                    synth.render(buffer, burstFrames)
                    track.write(buffer, 0, burstFrames, AudioTrack.WRITE_BLOCKING)
                }
                runCatching { track.pause() }
                synth.reset()
                paused = true
            }
            if (paused) {
                if (running && !active) LockSupport.parkNanos(PARK_POLL_NS)
                if (active) {
                    paused = false
                    runCatching { track.play() }
                }
                continue
            }
            synth.render(buffer, burstFrames)
            track.write(buffer, 0, burstFrames, AudioTrack.WRITE_BLOCKING)
        }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    /** Underruns since the track was created. A probe; nothing reads it. */
    val underruns: Int get() = runCatching { track.underrunCount }.getOrDefault(0)

    companion object {
        private const val FALLBACK_RATE = 48_000
        private const val FALLBACK_BURST = 256

        /** Two is latency-optimal; four survives a scheduling hiccup. */
        private const val BURSTS = 4

        private const val DRAIN_BURSTS = 12

        /** A poll, not a pure park: a lost wake-up costs a fifth-second of silence, not engine. */
        private const val PARK_POLL_NS = 200_000_000L

        private fun gameAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        /** Null when the device refuses a track; the game is then silent. */
        fun create(context: Context): GameAudio? = runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return null
            val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                ?: FALLBACK_RATE
            val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
                ?: FALLBACK_BURST

            val minBytes = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val bytesPerFrame = 4
            val bufferBytes = maxOf(minBytes, burst * bytesPerFrame * BURSTS)

            val track = AudioTrack.Builder()
                .setAudioAttributes(gameAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        // The mixer's own format: nothing converts per sample, overshoot clips.
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            GameAudio(am, track, burst, GameSynth(rate))
        }.getOrNull()
    }
}

@Composable
internal fun rememberGameAudio(): GameAudio? {
    val app = LocalContext.current.applicationContext
    val audio = remember(app) { GameAudio.create(app) }
    DisposableEffect(audio) { onDispose { audio?.close() } }
    return audio
}
