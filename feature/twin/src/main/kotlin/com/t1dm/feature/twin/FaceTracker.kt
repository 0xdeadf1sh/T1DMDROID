package com.t1dm.feature.twin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.Connection
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.sqrt

/** [points] x,y,z per landmark, normalized to the upright frame of [width]×[height]. */
internal class FaceFrame(
    val points: FloatArray,
    val width: Int,
    val height: Int,
    val metrics: FaceMetrics? = null,
    /** Upright ARGB, [width]×[height]; only while the scan asked the tracker to keep frames. */
    val pixels: IntArray? = null,
) {
    val count: Int get() = points.size / 3
    fun x(i: Int) = points[i * 3]
    fun y(i: Int) = points[i * 3 + 1]

    /** Degrees, + = head turned to its own left; the nose against the cheek midpoint. */
    val yawDeg: Float get() = tilt((x(NOSE) - (x(CHEEK_L) + x(CHEEK_R)) / 2) * width, span(CHEEK_L, CHEEK_R))

    /** Degrees, + = chin up; the nose against the forehead–chin midpoint. */
    val pitchDeg: Float get() = tilt(((y(FOREHEAD) + y(CHIN)) / 2 - y(NOSE)) * height, span(FOREHEAD, CHIN))

    /** Face width as a fraction of the frame width. */
    fun widthFraction(): Float = span(CHEEK_L, CHEEK_R) / width

    private fun span(a: Int, b: Int): Float {
        val dx = (x(a) - x(b)) * width
        val dy = (y(a) - y(b)) * height
        return sqrt(dx * dx + dy * dy)
    }

    private fun tilt(offsetPx: Float, spanPx: Float): Float {
        if (spanPx <= 0f) return 0f
        return Math.toDegrees(asin((2f * offsetPx / spanPx).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    companion object {
        const val NOSE = 1
        const val FOREHEAD = 10
        const val CHIN = 152
        const val CHEEK_L = 234
        const val CHEEK_R = 454
    }
}

/** Mesh edges as flat start,end pairs, built once from MediaPipe's connection sets. */
internal object FaceMesh {
    val tesselation: IntArray by lazy { pairs(FaceLandmarker.FACE_LANDMARKS_TESSELATION) }
    val contours: IntArray by lazy {
        pairs(
            FaceLandmarker.FACE_LANDMARKS_FACE_OVAL +
                FaceLandmarker.FACE_LANDMARKS_LIPS +
                FaceLandmarker.FACE_LANDMARKS_LEFT_EYE +
                FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE +
                FaceLandmarker.FACE_LANDMARKS_LEFT_EYE_BROW +
                FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE_BROW,
        )
    }

    private fun pairs(set: Set<Connection>): IntArray = set.flatMap { listOf(it.start(), it.end()) }.toIntArray()
}

/** Landmarks and checks CameraX frames; the newest result lands in [latest], null when no face. */
internal class FaceTracker(
    context: Context,
    private val latest: AtomicReference<FaceFrame?>,
    private val keepPixels: AtomicBoolean,
) : Closeable {
    private var lastTs = 0L
    private var lastLogTs = 0L
    private var upright: Bitmap? = null
    private var pixels = IntArray(0)
    private var smoothed: FaceMetrics? = null

    private val landmarker: FaceLandmarker = run {
        val bytes = context.assets.open("face_landmarker.task").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    /** Analyzer thread only. Always closes [proxy]. */
    fun analyze(proxy: ImageProxy) {
        proxy.use {
            val frame = uprightOf(it.toBitmap(), it.imageInfo.rotationDegrees)
            val ts = maxOf(SystemClock.uptimeMillis(), lastTs + 1)
            lastTs = ts
            val result = runCatching { landmarker.detectForVideo(BitmapImageBuilder(frame).build(), ts) }
                .onFailure { e -> Log.w(TAG, "landmarker error", e) }
                .getOrNull()
            val lm = result?.faceLandmarks()?.firstOrNull()
            if (lm == null) {
                smoothed = null
                latest.set(null)
                return
            }
            val pts = FloatArray(lm.size * 3)
            lm.forEachIndexed { i, p ->
                pts[i * 3] = p.x()
                pts[i * 3 + 1] = p.y()
                pts[i * 3 + 2] = p.z()
            }
            val w = frame.width
            val h = frame.height
            if (pixels.size != w * h) pixels = IntArray(w * h)
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            val now = FaceQuality(pixels, w, h, FaceFrame(pts, w, h)).measure()
            val m = smoothed?.blend(now, SMOOTHING) ?: now
            smoothed = m
            if (ts - lastLogTs > 1_000) {
                lastLogTs = ts
                Log.d(TAG, "quality $m → ${m.issue()}")
            }
            latest.set(FaceFrame(pts, w, h, m, if (keepPixels.get()) pixels.copyOf() else null))
        }
    }

    /** [src] rotated upright into one reused bitmap, so landmarks and pixels share axes. */
    private fun uprightOf(src: Bitmap, degrees: Int): Bitmap {
        val sideways = degrees % 180 != 0
        val w = if (sideways) src.height else src.width
        val h = if (sideways) src.width else src.height
        val dst = upright?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { upright = it }
        val m = Matrix().apply {
            postRotate(degrees.toFloat(), src.width / 2f, src.height / 2f)
            postTranslate((w - src.width) / 2f, (h - src.height) / 2f)
        }
        Canvas(dst).drawBitmap(src, m, null)
        return dst
    }

    override fun close() = landmarker.close()

    private companion object {
        const val TAG = "FaceTracker"
        const val SMOOTHING = 0.3f
    }
}
