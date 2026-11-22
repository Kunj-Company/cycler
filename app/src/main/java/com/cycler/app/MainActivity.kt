package com.cycler.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cycler.app.data.Geo
import com.cycler.app.data.Prefs
import com.cycler.app.data.Pt
import com.cycler.app.data.Route
import com.cycler.app.data.RouteStore
import com.cycler.app.data.RouteType
import com.cycler.app.net.BRouter
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.track.locationFlow
import com.cycler.app.ui.PlanScreen
import com.cycler.app.ui.RideScreen
import com.cycler.app.ui.RouteDetailScreen
import com.cycler.app.ui.RoutesScreen
import com.cycler.app.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import kotlin.math.abs

class CyclerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    RIDE("Ride", Icons.AutoMirrored.Filled.DirectionsBike),
    PLAN("Plan", Icons.Filled.Route),
    ROUTES("Routes", Icons.Filled.Bookmarks),
    SETTINGS("Settings", Icons.Filled.Settings),
}

enum class PlanMode(val label: String) { AB("A to B"), LOOP("Loop"), PAST("My rides") }

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

    var profile by mutableStateOf(prefs.profile)
    var mapStyle by mutableStateOf(prefs.mapStyle)
    var keepScreenOn by mutableStateOf(prefs.keepScreenOn)

    // Planning
    var planMode by mutableStateOf(PlanMode.AB)
    var planPoints by mutableStateOf(listOf<Pt>())
    var fromMyLocation by mutableStateOf(true)
    var loopKm by mutableFloatStateOf(20f)
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
        planPoints = emptyList()
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

    private fun loop(start: Pt, bearing: Double, target: Double, prof: String): BRouter.Result {
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
        setContent { CyclerTheme { App() } }
    }
}

@Composable
fun CyclerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF2E7D32))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainVm = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(ctx)
    }
    val request = {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        askPermission.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) { if (!granted) request() }

    val myLoc by produceState<Pt?>(initialValue = null, granted) {
        if (granted) locationFlow(ctx).collect {
            value = it
            vm.prefs.lastLoc = it
        }
    }

    val detail = vm.detailId
    if (detail != null) {
        BackHandler { vm.detailId = null }
        RouteDetailScreen(vm, detail)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (vm.tab) {
                Tab.RIDE -> RideScreen(vm, myLoc, granted, request)
                Tab.PLAN -> PlanScreen(vm, myLoc)
                Tab.ROUTES -> RoutesScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
