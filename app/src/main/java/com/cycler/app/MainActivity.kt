package com.cycler.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cycler.app.data.CareItem
import com.cycler.app.data.Geo
import com.cycler.app.data.Prefs
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.net.BRouter
import com.cycler.app.net.Overpass
import com.cycler.app.net.Weather
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.track.locationFlow
import com.cycler.app.ui.ActivityScreen
import com.cycler.app.ui.Cy
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.Hairline
import com.cycler.app.ui.HeatmapScreen
import com.cycler.app.ui.ShareScreen
import com.cycler.app.ui.PlanScreen
import com.cycler.app.ui.RideScreen
import com.cycler.app.ui.RouteDetailScreen
import com.cycler.app.ui.RoutesScreen
import com.cycler.app.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import kotlin.math.abs
import kotlin.random.Random

class CyclerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    RIDE("Ride", Icons.AutoMirrored.Outlined.DirectionsBike),
    PLAN("Plan", Icons.Outlined.Route),
    ROUTES("Routes", Icons.Outlined.BookmarkBorder),
    ACTIVITY("Activity", Icons.Outlined.BarChart),
}

enum class PlanMode(val label: String) { AB("A to B"), LOOP("Loop"), PAST("My rides") }

/** A chosen place in the planner. */
class Stop(val pt: Pt, val label: String)

/** Which field the next long-press or search result fills. */
enum class Pick { START, END, VIA }

class RouteOption(
    val label: String,
    val pts: List<Pt>,
    val distanceM: Double,
    val ascentM: Double,
    val timeS: Long,
    val tags: List<String> = emptyList(),
)

class MainVm(app: Application) : AndroidViewModel(app) {
    val store = RouteStore.get(app)
    val prefs = Prefs(app)

    var tab by mutableStateOf(Tab.RIDE)
    var detailId by mutableStateOf<String?>(null)
    /** A saved route shown on the Ride screen to follow while recording. */
    var guide by mutableStateOf<Route?>(null)
        private set
    var showSettings by mutableStateOf(false)

    var themeMode by mutableStateOf(prefs.themeMode)
    var autoPause by mutableStateOf(prefs.autoPause)
    var offRouteAlert by mutableStateOf(prefs.offRouteAlert)
    var voice by mutableStateOf(prefs.voice)
    var weeklyGoalKm by mutableIntStateOf(prefs.weeklyGoalKm)

    var weather by mutableStateOf<Weather.Now?>(null)
        internal set
    private var weatherAt: Pt? = null
    private var weatherTime = 0L

    /** Follow [route] on the Ride screen (null stops following). */
    fun follow(route: Route?) {
        guide = route
        guideSignals = emptyList()
        TrackingService.setGuide(route?.pts)
        if (route != null) viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Overpass.signalsAlong(route.pts) }.getOrNull() }
            if (found != null && guide === route) {
                guideSignals = found
                TrackingService.signals = found
            }
        }
    }

    /** Traffic signals along the route being followed, once looked up. */
    var guideSignals by mutableStateOf(listOf<Pt>())
        private set

    /** Which full-screen page is open on top of the tabs, if any. */
    var shareId by mutableStateOf<String?>(null)
    var showHeatmap by mutableStateOf(false)
    var care by mutableStateOf(prefs.care)

    fun saveCare(items: List<CareItem>) {
        care = items
        prefs.care = items
    }

    /** Fetch the weather, at most every 15 minutes unless the rider has moved far. */
    fun refreshWeather(at: Pt) {
        val now = System.currentTimeMillis()
        val moved = weatherAt?.let { Geo.dist(it, at) > 5000 } ?: true
        if (!moved && now - weatherTime < 15 * 60_000) return
        weatherAt = at
        weatherTime = now
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { Weather.now(at) }.getOrNull() }?.let { weather = it }
        }
    }

    var profile by mutableStateOf(prefs.profile)
    var mapStyle by mutableStateOf(prefs.mapStyle)
    var keepScreenOn by mutableStateOf(prefs.keepScreenOn)

    // Planning
    var planMode by mutableStateOf(PlanMode.AB)
    /** null means "start from where I am". */
    var start by mutableStateOf<Stop?>(null)
    var end by mutableStateOf<Stop?>(null)
    var vias by mutableStateOf(listOf<Stop>())
    var pick by mutableStateOf(Pick.END)
    var loopKm by mutableFloatStateOf(20f)

    // Quick suggestions shown on the Routes tab
    var suggestions by mutableStateOf(listOf<RouteOption>())
    var suggestBusy by mutableStateOf(false)
    var suggestError by mutableStateOf<String?>(null)
    var options by mutableStateOf(listOf<RouteOption>())
    var selected by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (TrackingService.state.value.status == TrackStatus.IDLE) store.recoverUnfinished()
        }
    }

    fun clearPlan() {
        start = null
        end = null
        vias = emptyList()
        pick = Pick.END
        options = emptyList()
        selected = 0
        error = null
    }

    /** Up to three alternative routes through the chosen points. */
    fun findRoutes(points: List<Pt>) {
        if (points.size < 2 || busy) return
        busy = true
        error = null
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                (0..2).map { alt -> async { runCatching { BRouter.route(points, profile, alt) } } }.awaitAll()
            }
            val ok = results.mapNotNull { it.getOrNull() }
                .distinctBy { "${it.distanceM.toInt() / 50}-${it.ascentM.toInt() / 5}" }
            if (ok.isEmpty()) {
                error = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.message ?: "No route found"
                options = emptyList()
            } else {
                options = tag(ok.mapIndexed { i, r ->
                    RouteOption("Route ${i + 1}", r.pts, r.distanceM, r.ascentM, r.timeS)
                }, null)
            }
            selected = 0
            busy = false
        }
    }

    /** Round trips of about [loopKm] heading out in four directions from [start]. */
    fun findLoops(start: Pt) {
        if (busy) return
        busy = true
        error = null
        val target = loopKm * 1000.0
        val prof = profile
        viewModelScope.launch {
            val dirs = listOf(0.0 to "North", 90.0 to "East", 180.0 to "South", 270.0 to "West")
            val results = withContext(Dispatchers.IO) {
                dirs.map { (bearing, name) ->
                    async { runCatching { name to loop(start, bearing, target, prof) } }
                }.awaitAll()
            }
            val ok = results.mapNotNull { it.getOrNull() }
            if (ok.isEmpty()) {
                error = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.message ?: "No loops found here"
                options = emptyList()
            } else {
                options = tag(ok.map { (name, r) ->
                    RouteOption("$name loop", r.pts, r.distanceM, r.ascentM, r.timeS)
                }.sortedBy { abs(it.distanceM - target) }, target)
            }
            selected = 0
            busy = false
        }
    }

    /** A mix of short, medium and long loops from [from], in varied directions. */
    fun suggestNearby(from: Pt) {
        if (suggestBusy) return
        suggestBusy = true
        suggestError = null
        val prof = profile
        val base = Random.nextDouble(360.0)
        val plan = listOf(10.0 to "Short", 25.0 to "Medium", 50.0 to "Long").flatMapIndexed { i, (km, name) ->
            listOf(base + i * 60, base + i * 60 + 180).map { Triple(km * 1000, name, it % 360) }
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                plan.map { (target, name, bearing) ->
                    async {
                        runCatching {
                            val r = loop(from, bearing, target, prof)
                            RouteOption("$name loop · ${compass(bearing)}", r.pts, r.distanceM, r.ascentM, r.timeS)
                        }
                    }
                }.awaitAll()
            }
            val ok = results.mapNotNull { it.getOrNull() }.sortedBy { it.distanceM }
            suggestions = ok
            if (ok.isEmpty()) {
                suggestError = results.firstNotNullOfOrNull { it.exceptionOrNull() }?.message ?: "No routes found here"
            }
            suggestBusy = false
        }
    }

    private fun compass(bearing: Double): String =
        listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[
            (((bearing % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
        ]

    /** Show a suggestion in the planner, where it can be previewed, saved or ridden. */
    fun openSuggestion(index: Int) {
        clearPlan()
        planMode = PlanMode.LOOP
        options = suggestions
        selected = index
        tab = Tab.PLAN
    }

    /** Follow the selected option on the Ride screen without saving it first. */
    fun rideSelected() {
        val o = options.getOrNull(selected) ?: return
        val meta = RouteMeta(
            "", o.label, RouteType.PLANNED, System.currentTimeMillis(), o.distanceM, o.ascentM, o.timeS,
            o.pts.first().lat, o.pts.first().lon,
        )
        follow(Route(meta, o.pts))
        tab = Tab.RIDE
    }

    /** At most three routing requests at a time, to go easy on the shared public server. */
    private val routing = Semaphore(3)

    private suspend fun loop(start: Pt, bearing: Double, target: Double, prof: String): BRouter.Result = routing.withPermit {
        loopBlocking(start, bearing, target, prof)
    }

    private fun loopBlocking(start: Pt, bearing: Double, target: Double, prof: String): BRouter.Result {
        var best = BRouter.route(Geo.loopWaypoints(start, bearing, target), prof)
        val ratio = best.distanceM / target
        if (ratio > 1.15 || ratio < 0.85) {
            // Too long or short: resize the circle once and keep whichever is closer.
            runCatching { BRouter.route(Geo.loopWaypoints(start, bearing, target / ratio), prof) }
                .getOrNull()?.let { if (abs(it.distanceM - target) < abs(best.distanceM - target)) best = it }
        }
        return best
    }

    private fun tag(list: List<RouteOption>, target: Double?): List<RouteOption> {
        if (list.size < 2) return list
        val shortest = list.minByOrNull { it.distanceM }
        val flattest = list.minByOrNull { it.ascentM }
        val fastest = list.minByOrNull { it.timeS }
        val closest = target?.let { t -> list.minByOrNull { abs(it.distanceM - t) } }
        return list.map { o ->
            val tags = buildList {
                if (target == null && o === fastest) add("Fastest")
                if (target == null && o === shortest) add("Shortest")
                if (closest === o) add("Closest to target")
                if (o === flattest) add("Flattest")
            }
            RouteOption(o.label, o.pts, o.distanceM, o.ascentM, o.timeS, tags)
        }
    }

    fun saveSelected(name: String) {
        val o = options.getOrNull(selected) ?: return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                store.save(name, RouteType.PLANNED, o.pts, o.timeS, o.distanceM, o.ascentM)
            }
            clearPlan()
            detailId = id
        }
    }
}

fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainVm = viewModel()
            CyclerTheme(vm.themeMode) {
                // Status and navigation bar icons follow the app's theme, not just the system's.
                val dark = Cy.colors.isDark
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
                    else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose {}
                }
                App(vm)
            }
        }
    }
}

@Composable
fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Hairline()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)) {
                Tab.entries.forEach { t ->
                    val sel = t == tab
                    val color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        Text(
                            t.label, color = color, modifier = Modifier.padding(top = 3.dp),
                            style = if (sel) MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            else MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun App(vm: MainVm) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    var askedOnce by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(ctx)
        askedOnce = true
    }
    val request = {
        if (askedOnce && !hasLocationPermission(ctx)) {
            // Android stops showing the dialog after a refusal (or an "Approximate" answer),
            // so send the rider to the app's settings page to switch on precise location.
            Toast.makeText(ctx, "Set Location to Allow, with Precise on", Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Unit
        } else {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            askPermission.launch(perms.toTypedArray())
        }
    }
    // Coming back from the settings page: pick up a permission granted there.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = hasLocationPermission(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!granted) request() }

    val myLoc by produceState<Pt?>(initialValue = null, granted) {
        if (granted) locationFlow(ctx).collect {
            value = it
            vm.prefs.lastLoc = it
            vm.refreshWeather(it)
        }
    }

    val share = vm.shareId
    if (share != null) {
        BackHandler { vm.shareId = null }
        ShareScreen(vm, share)
        return
    }
    if (vm.showHeatmap) {
        BackHandler { vm.showHeatmap = false }
        HeatmapScreen(vm)
        return
    }
    val detail = vm.detailId
    if (detail != null) {
        BackHandler { vm.detailId = null }
        RouteDetailScreen(vm, detail)
        return
    }
    if (vm.showSettings) {
        BackHandler { vm.showSettings = false }
        SettingsScreen(vm)
        return
    }
    AppShell(vm, myLoc, granted, request)
}

@Composable
fun AppShell(vm: MainVm, myLoc: Pt?, granted: Boolean, request: () -> Unit) {
    Scaffold(
        bottomBar = { BottomBar(vm.tab) { vm.tab = it } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (vm.tab) {
                Tab.RIDE -> RideScreen(vm, myLoc, granted, request)
                Tab.PLAN -> PlanScreen(vm, myLoc)
                Tab.ROUTES -> RoutesScreen(vm, myLoc)
                Tab.ACTIVITY -> ActivityScreen(vm)
            }
        }
    }
}
