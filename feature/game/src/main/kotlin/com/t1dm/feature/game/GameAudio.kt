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

/**
 * [GameSynth] wired to the speaker: one `AudioTrack`, one generator thread, and audio focus.
 *
 * **The blocking write is the clock.** The thread renders exactly one HAL burst, hands it to
 * `AudioTrack.write(…, WRITE_BLOCKING)`, and blocks there until the mixer has room for the next —
 * which is the only pacing mechanism that cannot drift. There is no `Thread.sleep` on this path and no
 * timer; the one `sleep` in the file is in the parked branch, where nothing is being produced.
 *
 * **Nothing here may touch the frame loop.** The game thread's whole interaction with audio is a
 * handful of volatile field writes and an atomic OR (see [GameSynth]) — no queue, no lock, no
 * allocation — so a stalled mixer can starve the speaker but can never stall the physics, and a
 * long frame can never underrun the speaker.
 *
 * Sizing is queried rather than assumed. `FuneralToll` hardcodes 44 100 Hz; this device's HAL runs at
 * 48 000 with a 256-frame burst, and a track built at any other rate is silently resampled and
 * disqualified from the fast path — so [AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE] and
 * [AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER] are the authority and the constants below are only
 * the fallback for a device that answers neither.
 */
class GameAudio private constructor(
    private val audioManager: AudioManager,
    private val track: AudioTrack,
    private val burstFrames: Int,
    val synth: GameSynth,
) {
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(gameAttributes())
            // Duck it ourselves, on a ramp. The platform's own ducking is a step, and a step on a
            // continuous engine tone is the one artefact this whole file exists to avoid.
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

    /**
     * Start (or resume) making sound. Idempotent; takes audio focus if it is not already held.
     */
    fun resume() {
        if (!running || active) return
        // The GRANT is only ever reported as a return value — a granted request does not call the
        // listener — so the gain has to be re-derived here. A permanent AUDIOFOCUS_LOSS latched it at
        // 0, and leaving it there would mute the engine for the rest of the composition.
        val granted = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusGain = if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
        active = true
        applyGain()
        LockSupport.unpark(thread)
    }

    /**
     * **The alarm interlock's audio half, and the background hold's.** Fade out, park the track, and
     * hand focus back — an alarm ringtone must not arrive ducked underneath a game engine.
     *
     * The fade is the synth's master ramp, so this returns immediately and the silence lands ~30 ms
     * later without a click; the generator then parks rather than spinning on silence, because the
     * game route can sit backgrounded on the nav stack indefinitely.
     */
    fun release() {
        if (!active) return
        active = false
        synth.master = 0f
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    /** Permanent teardown. The generator thread does the release itself, one burst later, so nothing
     *  is freed underneath a native write in flight. */
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

    /** Underruns since the track was created: the one honest measure of whether [BURSTS] is enough
     *  headroom on a throttled SoC, and the number to reach for first if the engine ever crackles.
     *  Nothing reads it today — it is a probe, not a control. */
    val underruns: Int get() = runCatching { track.underrunCount }.getOrDefault(0)

    companion object {
        private const val FALLBACK_RATE = 48_000
        private const val FALLBACK_BURST = 256

        /** Bursts of headroom. Two is the latency-optimal figure; four survives a scheduling hiccup on
         *  a phone that is also decoding BLE frames and running a forecast, which this one is. */
        private const val BURSTS = 4

        private const val DRAIN_BURSTS = 12

        /** A poll rather than a pure park: `unpark` is the fast path, and the timeout is only there so
         *  a lost wake-up costs a fifth of a second of silence instead of a dead engine. */
        private const val PARK_POLL_NS = 200_000_000L

        private fun gameAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

        /**
         * Build the engine, or `null` if this device will not give us a track — in which case the game
         * is silent and nothing else changes.
         */
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
                        // Float in, float out: the mixer's own format, so nothing converts per sample
                        // and an overshoot clips in the mixer instead of wrapping in a short.
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

/** The engine for this composition, torn down with it. Null when the device refused a track. */
@Composable
internal fun rememberGameAudio(): GameAudio? {
    val app = LocalContext.current.applicationContext
    val audio = remember(app) { GameAudio.create(app) }
    DisposableEffect(audio) { onDispose { audio?.close() } }
    return audio
}
