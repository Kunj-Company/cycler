package com.cycler.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Layers
import android.view.Gravity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cycler.app.data.Pt
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

class MapLine(val pts: List<Pt>, val color: String, val width: Float = 5f, val opacity: Float = 1f)
class MapDot(val pt: Pt, val color: String, val radius: Float = 7f)

/** One point = centre on it (optionally with zoom); several = fit them all. A new instance moves the camera. */
class CameraCmd(val pts: List<Pt>, val zoom: Double? = null)

object MapStyles {
    val all = listOf(
        "liberty" to "Standard",
        "bright" to "Bright",
        "positron" to "Light",
        "dark" to "Dark",
        "cyclosm" to "CyclOSM (bike lanes, contours)",
    )

    fun builder(key: String): Style.Builder =
        if (key == "cyclosm") Style.Builder().fromJson(CYCLOSM)
        else Style.Builder().fromUri("https://tiles.openfreemap.org/styles/$key")

    const val BLANK =
        """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#E9ECE6"}}]}"""

    private val CYCLOSM = """
        {"version":8,"sources":{"cyclosm":{"type":"raster","tiles":[
        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"],
        "tileSize":256,"maxzoom":19,
        "attribution":"© OpenStreetMap contributors · CyclOSM"}},
        "layers":[{"id":"cyclosm","type":"raster","source":"cyclosm"}]}
    """.trimIndent()
}

private const val SRC_LINES = "cycler-lines"
private const val SRC_DOTS = "cycler-dots"

private fun linesJson(lines: List<MapLine>): String {
    val feats = JSONArray()
    for (l in lines) {
        if (l.pts.size < 2) continue
        val coords = JSONArray()
        for (p in l.pts) coords.put(JSONArray().put(p.lon).put(p.lat))
        feats.put(
            JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("color", l.color).put("width", l.width.toDouble()).put("opacity", l.opacity.toDouble()))
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
}

private fun dotsJson(dots: List<MapDot>): String {
    val feats = JSONArray()
    for (d in dots) {
        feats.put(
            JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("color", d.color).put("radius", d.radius.toDouble()))
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(d.pt.lon).put(d.pt.lat)))
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
}

@Composable
fun CyclerMap(
    modifier: Modifier,
    styleKey: String,
    lines: List<MapLine>,
    dots: List<MapDot>,
    camera: CameraCmd?,
    initial: Pt? = null,
    initialZoom: Double = 13.0,
    onLongPress: ((Pt) -> Unit)? = null,
    onUserMove: (() -> Unit)? = null,
    // Full-screen maps with a bottom sheet over them move the OSM attribution to the top-end corner.
    attributionTopEnd: Boolean = false,
) {
    if (LocalInspectionMode.current) {
        // Previews and screenshot tests can't run the real map engine; sketch the content instead.
        MapPlaceholder(modifier, lines, dots)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val padPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleTick by remember { mutableIntStateOf(0) }
    val longPress by rememberUpdatedState(onLongPress)
    val userMove by rememberUpdatedState(onUserMove)

    DisposableEffect(lifecycle) {
        // Each start/resume is undone exactly once, whether by the lifecycle or by leaving the screen.
        var started = false
        var resumed = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            if (attributionTopEnd) {
                val gap = (12 * density.density).toInt()
                m.uiSettings.attributionGravity = Gravity.TOP or Gravity.END
                m.uiSettings.setAttributionMargins(0, statusTop + gap, gap, 0)
            }
            m.uiSettings.isTiltGesturesEnabled = false
            // An initial point off the globe (a damaged ride) would throw; start on the default view.
            val start = initial?.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(start?.lat ?: 20.6, start?.lon ?: 78.9))
                .zoom(if (start != null) initialZoom else 3.5)
                .build()
            m.addOnMapLongClickListener { ll ->
                longPress?.invoke(Pt(ll.latitude, ll.longitude))
                longPress != null
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMove?.invoke()
            }
            map = m
        }
    }

    // No signal: the map style can't download. Fall back to a plain background so the
    // recorded track and the route being followed are still drawn.
    var styleFailed by remember(styleKey) { mutableStateOf(false) }
    val statsCtx = LocalContext.current.applicationContext
    // Counted once per map shown, not for each automatic retry.
    var failCounted by remember { mutableStateOf(false) }
    DisposableEffect(mapView) {
        val l = MapView.OnDidFailLoadingMapListener {
            styleFailed = true
            if (!failCounted) {
                failCounted = true
                com.cycler.app.analytics.UsageStats.track(statsCtx, com.cycler.app.analytics.Feature.MAP_LOAD_FAILED)
            }
        }
        mapView.addOnDidFailLoadingMapListener(l)
        onDispose { mapView.removeOnDidFailLoadingMapListener(l) }
    }

    // While the real style is failing, try it again every 10 s once the phone has a network (this composable
    // only exists while its screen is shown, so nothing runs in the background).
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(styleFailed, styleKey) {
        while (styleFailed) {
            kotlinx.coroutines.delay(10_000)
            if (hasNetwork(appCtx)) styleFailed = false
        }
    }

    LaunchedEffect(map, styleKey, styleFailed) {
        val m = map ?: return@LaunchedEffect
        val builder = if (styleFailed) Style.Builder().fromJson(MapStyles.BLANK) else MapStyles.builder(styleKey)
        m.setStyle(builder) { style ->
            style.addSource(GeoJsonSource(SRC_LINES))
            style.addSource(GeoJsonSource(SRC_DOTS))
            style.addLayer(
                LineLayer("cycler-lines-casing", SRC_LINES).withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineColor("#ffffff"),
                    PropertyFactory.lineOpacity(Expression.product(Expression.get("opacity"), Expression.literal(0.8f))),
                    PropertyFactory.lineWidth(Expression.sum(Expression.get("width"), Expression.literal(3))),
                )
            )
            style.addLayer(
                LineLayer("cycler-lines", SRC_LINES).withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.lineOpacity(Expression.get("opacity")),
                    PropertyFactory.lineWidth(Expression.get("width")),
                )
            )
            style.addLayer(
                CircleLayer("cycler-dots", SRC_DOTS).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                )
            )
            styleTick++
        }
    }

    LaunchedEffect(styleTick, lines) {
        if (styleTick > 0) {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { linesJson(lines) }
            map?.style?.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(json)
        }
    }
    LaunchedEffect(styleTick, dots) {
        if (styleTick > 0) map?.style?.getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(dotsJson(dots))
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val c = camera ?: return@LaunchedEffect
        mapView.post {
            runCatching {
                if (c.pts.size == 1) {
                    val ll = LatLng(c.pts[0].lat, c.pts[0].lon)
                    val upd = if (c.zoom != null) CameraUpdateFactory.newLatLngZoom(ll, c.zoom)
                    else CameraUpdateFactory.newLatLng(ll)
                    m.animateCamera(upd, 600)
                } else if (c.pts.size >= 2) {
                    val b = LatLngBounds.Builder()
                    for (p in c.pts) b.include(LatLng(p.lat, p.lon))
                    m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), padPx), 600)
                }
            }
        }
    }

    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (styleFailed) {
            MapChip(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 22.dp)) {
                Text("Map unavailable offline", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** True when the phone reports a connection that can reach the internet. */
internal fun hasNetwork(ctx: android.content.Context): Boolean = runCatching {
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)

@Composable
private fun MapPlaceholder(modifier: Modifier, lines: List<MapLine>, dots: List<MapDot>) {
    val dark = Cy.colors.isDark
    androidx.compose.foundation.Canvas(
        modifier.background(if (dark) Color(0xFF20242A) else Color(0xFFE9ECE6))
    ) {
        val all = lines.flatMap { it.pts } + dots.map { it.pt }
        val road = if (dark) Color(0xFF2C3138) else Color(0xFFF7F8F5)
        for (i in 1..7) {
            drawLine(road, Offset(0f, size.height * i / 8f), Offset(size.width, size.height * i / 8f - 60f), 10f)
            drawLine(road, Offset(size.width * i / 8f, 0f), Offset(size.width * i / 8f + 40f, size.height), 10f)
        }
        if (all.isEmpty()) return@Canvas
        val minLat = all.minOf { it.lat }; val maxLat = all.maxOf { it.lat }
        val minLon = all.minOf { it.lon }; val maxLon = all.maxOf { it.lon }
        val w = (maxLon - minLon).coerceAtLeast(1e-4); val h = (maxLat - minLat).coerceAtLeast(1e-4)
        val scale = minOf(size.width * 0.7 / w, size.height * 0.5 / h)
        fun pos(p: Pt) = Offset(
            (size.width / 2 + (p.lon - (minLon + maxLon) / 2) * scale).toFloat(),
            (size.height * 0.38 - (p.lat - (minLat + maxLat) / 2) * scale).toFloat(),
        )
        for (l in lines) {
            val path = androidx.compose.ui.graphics.Path()
            l.pts.forEachIndexed { i, p -> val o = pos(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, Color(android.graphics.Color.parseColor(l.color)).copy(alpha = l.opacity), style = androidx.compose.ui.graphics.drawscope.Stroke(l.width * 2.6f))
        }
        for (d in dots) {
            drawCircle(Color.White, d.radius * 2.6f + 6f, pos(d.pt))
            drawCircle(Color(android.graphics.Color.parseColor(d.color)), d.radius * 2.6f, pos(d.pt))
        }
    }
}

/** A map control that lets the rider switch map style on the spot. */
@Composable
fun LayerButton(current: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        MapChip(onClick = { open = true }) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.Layers, "Map style",
                Modifier.size(20.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            shape = Radius, border = androidx.compose.foundation.BorderStroke(1.dp, Cy.colors.border),
        ) {
            MapStyles.all.forEach { (key, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        androidx.compose.material3.Text(
                            label,
                            color = if (key == current) Cy.colors.accent else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onPick(key) },
                )
            }
        }
    }
}
