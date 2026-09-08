package com.t1dm.feature.exercise

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Opaque `ViewGroup`, explicit lifecycle, `onDetach` mandatory. Track never leaves the phone. */
@Composable
fun ExerciseMap(
    track: List<TrackPoint>,
    modifier: Modifier = Modifier,
    cursor: TrackFix? = null,
) {
    val context = LocalContext.current
    // Composable-only getters, unreachable from `update`; the Int is what crosses.
    val ink = MaterialTheme.colorScheme.primary.toArgb()

    val host = remember(context) { MapHost(context) }
    DisposableEffect(host) { onDispose { host.release() } }
    LaunchedEffect(host) { host.open() }

    val points = remember(track) { track.map { GeoPoint(it.lat, it.lon) } }
    val map = host.map
    if (map == null) {
        Box(modifier)
        return
    }

    // Constructed without the map: handed one, osmdroid attaches an info window and inflates it.
    val line = remember(map) {
        Polyline().also {
            it.outlinePaint.isAntiAlias = true
            map.overlays.add(it)
        }
    }

    // After the polyline, so the dot draws over it.
    val dot = remember(map) { CursorOverlay().also { map.overlays.add(it) } }
    val cursorPoint = remember(cursor) { cursor?.let { GeoPoint(it.lat, it.lon) } }

    // Fitting before measurement lands zero-sized. Registered once per map, not from `update`.
    val latest by rememberUpdatedState(points)
    // One-shot, and a plain holder so setting it cannot invalidate composition.
    val fitted = remember(map, points) { booleanArrayOf(false) }
    DisposableEffect(map) {
        val fit = MapView.OnFirstLayoutListener { _, _, _, _, _ ->
            if (!fitted[0] && latest.isNotEmpty()) {
                fitTo(map, latest)
                fitted[0] = true
            }
        }
        map.addOnFirstLayoutListener(fit)
        onDispose { map.removeOnFirstLayoutListener(fit) }
    }

    // Not in `update`: `setPoints` rebuilds a `LinearRing`, and `update` runs on cursor moves.
    LaunchedEffect(map, points, ink) {
        line.setPoints(points)
        line.outlinePaint.color = ink
        line.outlinePaint.strokeWidth = TRACK_WIDTH_PX
        map.invalidate()
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            dot.at = cursorPoint
            dot.fillArgb = ink
            if (!fitted[0] && view.width > 0 && view.height > 0 && points.isNotEmpty()) {
                fitTo(view, points)
                fitted[0] = true
            }
            // Deliberately no camera follow on a cursor change.
            view.invalidate()
        },
    )
}

/** `Overlay`, not `Marker`: `Marker(mapView)` inflates an info-window layout. */
private class CursorOverlay : Overlay() {
    var at: GeoPoint? = null
    var fillArgb: Int = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CURSOR_HALO_PX
        color = CURSOR_HALO_ARGB
    }
    private val px = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        val p = at ?: return
        projection.toPixels(p, px)
        fill.color = fillArgb
        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), CURSOR_RADIUS_PX, fill)
        canvas.drawCircle(px.x.toFloat(), px.y.toFloat(), CURSOR_RADIUS_PX, halo)
    }
}

private const val CURSOR_RADIUS_PX = 9f
private const val CURSOR_HALO_PX = 3f

/** Raw white, outside the palette: separates the dot from any raster, not the app's surface. */
private val CURSOR_HALO_ARGB = 0xE6FFFFFF.toInt()

/** Built on IO (opens SQLite tile store); `MapView` can't — takes the Looper. Locks [release]. */
private class MapHost(private val context: Context) {
    var map by mutableStateOf<MapView?>(null)
        private set

    private var provider: MapTileProviderBasic? = null
    private var released = false

    suspend fun open() {
        withContext(Dispatchers.IO) { adopt(newTileProvider(context.applicationContext)) }
        // Under the lock too, so this and [release] cannot both detach the same provider.
        synchronized(this) {
            val tiles = provider ?: return
            map = newMapView(context, tiles)
        }
    }

    fun release() {
        synchronized(this) {
            released = true
            val view = map
            if (view != null) {
                view.onPause()
                view.onDetach()
            } else {
                provider?.detach()
            }
            provider = null
        }
    }

    private fun adopt(built: MapTileProviderBasic) {
        synchronized(this) {
            if (released) {
                built.detach()
            } else {
                provider = built
            }
        }
    }
}

/** Mandatory user agent: the OSM tile servers refuse an unnamed client. */
private fun newTileProvider(app: Context): MapTileProviderBasic {
    Configuration.getInstance().apply {
        userAgentValue = app.packageName
        val base = File(app.cacheDir, "osmdroid")
        osmdroidBasePath = base
        osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
    }
    return MapTileProviderBasic(app, TileSourceFactory.MAPNIK)
}

/** Tile source rides on the provider; re-setting it here would clear a cache never filled. */
private fun newMapView(context: Context, tiles: MapTileProviderBasic) = MapView(context, tiles).apply {
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
}

private fun fitTo(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    val box = BoundingBox.fromGeoPoints(points)
    // A zero-span box asks osmdroid for infinite zoom.
    if (box.latitudeSpan <= 0.0 || box.longitudeSpanWithDateLine <= 0.0) {
        map.controller.setZoom(STILL_ZOOM)
        map.controller.setCenter(points.first())
    } else {
        map.zoomToBoundingBox(box, false, TRACK_PAD_PX)
    }
}

/** Street level. */
private const val STILL_ZOOM = 17.0

private const val TRACK_PAD_PX = 48

/** osmdroid paints in raw pixels, not dp. */
private const val TRACK_WIDTH_PX = 8f
