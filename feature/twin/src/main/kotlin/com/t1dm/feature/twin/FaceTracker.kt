package com.t1dm.feature.twin

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.Connection
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.sqrt

/** [points] x,y,z per landmark, normalized to the upright (rotated) frame of [width]×[height]. */
internal class FaceFrame(val points: FloatArray, val width: Int, val height: Int) {
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

/** Landmarks CameraX frames; the newest result lands in [latest], null when no face. */
internal class FaceTracker(context: Context, private val latest: AtomicReference<FaceFrame?>) : Closeable {
    private var lastTs = 0L

    @Volatile private var rotation = 0

    private val landmarker: FaceLandmarker = run {
        val bytes = context.assets.open("face_landmarker.task").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(model).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setResultListener { result, image ->
                val face = result.faceLandmarks().firstOrNull()
                val sideways = rotation % 180 != 0
                val w = if (sideways) image.height else image.width
                val h = if (sideways) image.width else image.height
                latest.set(
                    face?.let { lm ->
                        val pts = FloatArray(lm.size * 3)
                        lm.forEachIndexed { i, p ->
                            pts[i * 3] = p.x()
                            pts[i * 3 + 1] = p.y()
                            pts[i * 3 + 2] = p.z()
                        }
                        FaceFrame(pts, w, h)
                    },
                )
            }
            .setErrorListener { e -> Log.w(TAG, "landmarker error", e) }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    }

    /** Analyzer thread only. Always closes [proxy]. */
    fun analyze(proxy: ImageProxy) {
        proxy.use {
            val bitmap = it.toBitmap()
            val ts = maxOf(SystemClock.uptimeMillis(), lastTs + 1)
            lastTs = ts
            rotation = it.imageInfo.rotationDegrees
            val opts = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
            landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), opts, ts)
        }
    }

    override fun close() = landmarker.close()

    private companion object {
        const val TAG = "FaceTracker"
    }
}
