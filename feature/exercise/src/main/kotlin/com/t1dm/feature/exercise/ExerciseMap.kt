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
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * A bout's track on raster OpenStreetMap tiles.
 *
 * The app's only `AndroidView`, and the only place it reaches outside Compose at all. Two consequences
 * are worth knowing before touching it. The `MapView` is an opaque self-drawing `ViewGroup`: it punches
 * a hard rectangle through the app's transparent backdrop and follows neither the palette nor the
 * font, which is why it is clipped into a card and kept modest rather than dressed up — a colour filter
 * faking the theme would only make the tiles unreadable. And it holds a tile thread and a disk cache,
 * so its lifecycle is driven explicitly and `onDetach` is not optional.
 *
 * Nothing here is built during composition: the tile store is opened off the main thread and the panel
 * draws an empty box until it is up — see [MapHost] for what that buys and what it costs.
 *
 * The cache sits under `cacheDir`, which is what keeps this free of any storage permission and lets
 * the platform reclaim it. The user agent is mandatory: the OSM tile servers refuse an unnamed client.
 *
 * The track NEVER leaves the phone — no wire field carries it, and it is not sent anywhere by drawing
 * it here. What does leave, if the user exports one, is the archive; see `ArchiveWriter`.
 */
@Composable
fun ExerciseMap(track: List<TrackPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.primary.toArgb()

    val host = remember(context) { MapHost(context) }
    DisposableEffect(host) { onDispose { host.release() } }
    LaunchedEffect(host) { host.open() }

    val points = remember(track) { track.map { GeoPoint(it.lat, it.lon) } }
    val map = host.map
    if (map == null) {
        // The tile store is still opening. An empty box, not a spinner: the card is already sized, and
        // what is being waited on is a local file open rather than anything that can fail visibly.
        Box(modifier)
        return
    }

    // Constructed WITHOUT the map: handed one, osmdroid attaches a default info window and inflates its
    // own bubble layout for it. The track is not tappable, so that is a layout and a resource dependency
    // bought for nothing — and resource shrinking is the release variant's job to get right.
    val line = remember(map) {
        Polyline().also {
            it.outlinePaint.isAntiAlias = true
            map.overlays.add(it)
        }
    }

    // A camera fitted before the view has been measured lands on a zero-sized viewport, so the first fit
    // is deferred to the layout that gives it one. Registered HERE, once per map, rather than from the
    // update lambda: `update` runs on every recomposition and the view is still unmeasured over the
    // first of them, so a listener added there was added again on each pass — one fit per pass, each to
    // the points captured when it was registered. It reads the current points instead, so a track that
    // arrived while the view was still unmeasured is the one it fits.
    val latest by rememberUpdatedState(points)
    DisposableEffect(map) {
        val fit = MapView.OnFirstLayoutListener { _, _, _, _, _ -> fitTo(map, latest) }
        map.addOnFirstLayoutListener(fit)
        onDispose { map.removeOnFirstLayoutListener(fit) }
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
            line.setPoints(points)
            line.outlinePaint.color = ink
            line.outlinePaint.strokeWidth = TRACK_WIDTH_PX
            if (view.width > 0 && view.height > 0) fitTo(view, points)
            view.invalidate()
        },
    )
}

/**
 * The map and the tile provider under it, built off the composition and owned until the panel is gone.
 *
 * The PROVIDER is what touches the disk. It resolves and creates the tile-cache directory, opens
 * osmdroid's SQLite tile store and scans the base path for archives — all of it inside the `MapView`
 * constructor if the constructor is left to build its own, which put a database open on the frame that
 * opens a bout review. Built on IO and handed over instead. The `MapView` itself is still built on the
 * main thread and cannot be otherwise: its tile-complete `Handler` and its `GestureDetector` each take
 * the Looper of whichever thread constructs them.
 *
 * The handover runs under the same lock as [release] because the two race, and losing that race leaks:
 * `withContext` discards its result when its caller has already been cancelled — the review closed
 * while the store was opening — and a provider dropped that way keeps three broadcast receivers
 * registered on the application context for the life of the process.
 */
private class MapHost(private val context: Context) {
    var map by mutableStateOf<MapView?>(null)
        private set

    private var provider: MapTileProviderBasic? = null
    private var released = false

    suspend fun open() {
        withContext(Dispatchers.IO) { adopt(newTileProvider(context.applicationContext)) }
        // The view is built under the lock too, so it and [release] cannot both end up detaching the
        // same provider: either this finds one and the view owns it from here, or [release] already
        // took it and there is nothing left to build.
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

/** Every disk touch the map needs, in one place so one thread hop covers all of it. */
private fun newTileProvider(app: Context): MapTileProviderBasic {
    Configuration.getInstance().apply {
        userAgentValue = app.packageName
        val base = File(app.cacheDir, "osmdroid")
        osmdroidBasePath = base
        osmdroidTileCache = File(base, "tiles").also { it.mkdirs() }
    }
    return MapTileProviderBasic(app, TileSourceFactory.MAPNIK)
}

/** The tile source rides on the provider — the view adopts whatever the provider it is handed carries,
 *  so setting it again here would only clear a cache that was never filled. */
private fun newMapView(context: Context, tiles: MapTileProviderBasic) = MapView(context, tiles).apply {
    setMultiTouchControls(true)
    // The ± buttons are a second zoom control over a pinch surface, drawn in osmdroid's own style in
    // the middle of a themed panel.
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
}

private fun fitTo(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    val box = BoundingBox.fromGeoPoints(points)
    // A bout that never moved is one point, and a box with no span asks osmdroid for infinite zoom.
    if (box.latitudeSpan <= 0.0 || box.longitudeSpanWithDateLine <= 0.0) {
        map.controller.setZoom(STILL_ZOOM)
        map.controller.setCenter(points.first())
    } else {
        map.zoomToBoundingBox(box, false, TRACK_PAD_PX)
    }
}

/** Street level — what a track of a few metres is legible at. */
private const val STILL_ZOOM = 17.0

/** Keeps the ends of a route off the card's edge. */
private const val TRACK_PAD_PX = 48

/** osmdroid paints in raw pixels, not dp — this is a line thick enough to follow over street tiles. */
private const val TRACK_WIDTH_PX = 8f
