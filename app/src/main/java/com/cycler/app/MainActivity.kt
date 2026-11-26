package com.cycler.app

import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.outlined.ViewAgenda
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
import com.cycler.app.data.Workout
import com.cycler.app.net.BRouter
import com.cycler.app.net.Overpass
import com.cycler.app.net.Weather
import com.cycler.app.track.TrackStatus
import com.cycler.app.track.TrackingService
import com.cycler.app.track.locationFlow
import com.cycler.app.ui.ActivityScreen
import com.cycler.app.ui.BikeCareScreen
import com.cycler.app.ui.Cy
import com.cycler.app.ui.CyclerTheme
import com.cycler.app.ui.Hairline
import com.cycler.app.ui.HeatmapScreen
import com.cycler.app.ui.Onboarding
import com.cycler.app.ui.reloadSettings
import com.cycler.app.ui.ShareScreen
import com.cycler.app.ui.OfflineScreen
import com.cycler.app.ui.PlanScreen
import com.cycler.app.ui.RideScreen
import com.cycler.app.ui.RideScreenEditor
import com.cycler.app.ui.SegmentCreateScreen
import com.cycler.app.ui.SegmentDetailScreen
import com.cycler.app.ui.SegmentsScreen
import com.cycler.app.ui.RouteDetailScreen
import com.cycler.app.ui.EditRideScreen
import com.cycler.app.ui.RidesScreen
import com.cycler.app.ui.SettingsScreen
import com.cycler.app.widget.WidgetLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
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
    RIDES("Rides", Icons.Outlined.ViewAgenda),
    ACTIVITY("Activity", Icons.Outlined.BarChart),
}

enum class PlanMode(val label: String) { AB("A to B"), LOOP("Loop"), SAVED("Saved") }

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
    /** A Settings page to open directly, such as "privacy" from Health Connect. */
    var settingsPage by mutableStateOf<String?>(null)

    var themeMode by mutableStateOf(prefs.themeMode)
    var autoPause by mutableStateOf(prefs.autoPause)
    var offRouteAlert by mutableStateOf(prefs.offRouteAlert)
    var voice by mutableStateOf(prefs.voice)
    var weeklyGoalKm by mutableIntStateOf(prefs.weeklyGoalKm)
    var advanced by mutableStateOf(prefs.advancedStats)
    var weightKg by mutableIntStateOf(prefs.weightKg)
    var turnCues by mutableStateOf(prefs.turnCues)
    var voiceEveryKm by mutableIntStateOf(prefs.voiceEveryKm)
    var autoPauseLevel by mutableIntStateOf(prefs.autoPauseLevel)
    var autoLapKm by mutableIntStateOf(prefs.autoLapKm)
    var ridePages by mutableStateOf(prefs.ridePages)
    var showFieldEditor by mutableStateOf(false)
    /** The interval workout picked for the next ride, if any. */
    var workout by mutableStateOf<Workout?>(null)
    var showSegments by mutableStateOf(false)
    var segmentId by mutableStateOf<String?>(null)
    /** The ride a new segment is being cut from. */
    var segmentFromRide by mutableStateOf<String?>(null)
    var showOffline by mutableStateOf(false)

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
    /** The ride whose title, notes, feeling, tags and photos are being edited. */
    var editId by mutableStateOf<String?>(null)
    /** True when [editId] is a ride that has just been finished. */
    var editIsNew by mutableStateOf(false)
    var showHeatmap by mutableStateOf(false)
    var care by mutableStateOf(prefs.care)
    /** False until the first-launch setup is done; riders who already have rides never see it. */
    var onboarded by mutableStateOf(prefs.onboarded || store.metas.value.isNotEmpty())
    var showCare by mutableStateOf(false)
    /** The support card on the Rides tab. Decided once per app start, so it never pops up mid-session. */
    var supportCard by mutableStateOf(false)
    private var supportChecked = false

    fun maybeShowSupport(rideCount: Int) {
        if (supportChecked || !BuildConfig.SUPPORT_PROMPT || !onboarded) return
        supportChecked = true
        val now = System.currentTimeMillis()
        if (com.cycler.app.data.SupportPrompt.due(rideCount, prefs.supportAskedAt, now)) {
            supportCard = true
            prefs.supportAskedAt = now
        }
    }
    /** Period, sort, search and tag chosen on the Rides tab; kept while the app is open. */
    var rideFilter by mutableStateOf(com.cycler.app.data.RideFilter())

    /** Experimental: the rider's bikes. One implicit bike until a second is added. */
    var bikes by mutableStateOf(prefs.bikes)
    fun saveBikes(list: com.cycler.app.data.BikeList) {
        bikes = list
        prefs.bikes = list
    }

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
    /** Re-runs the last planning / suggestion request, for the Retry buttons. */
    var retryPlan: (() -> Unit)? = null
    var retrySuggest: (() -> Unit)? = null
    /** Traffic signals already looked up, per ride id, so reopening a ride does not query again. */
    val signalsCache = com.cycler.app.ui.SignalsCache()

    init {
        // The UI was recreated while a ride is recording: pick the followed route and workout back up from the service.
        if (TrackingService.state.value.status != TrackStatus.IDLE) {
            restoreFromService()?.let { (g, w) ->
                guide = g
                workout = w
                guideSignals = TrackingService.signals
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Never while a ride is being recorded (the file is the live ride), and never allowed to crash the app.
            if (TrackingService.state.value.status != TrackStatus.IDLE) return@launch
            val ctx = getApplication<Application>()
            runCatching {
                when (val r = store.recoverUnfinishedResult()) {
                    is RouteStore.Recovery.Saved -> {
                        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.interruptedAt))
                        val name = store.metas.value.firstOrNull { it.id == r.id }?.name ?: ""
                        // The ride page opens with the message, so the rider can check, rename or delete it.
                        TrackingService.state.update {
                            if (it.status != TrackStatus.IDLE) it
                            else it.copy(notice = ctx.getString(R.string.rec_interrupted, at, name), savedId = r.id)
                        }
                    }
                    RouteStore.Recovery.Failed ->
                        TrackingService.state.update { if (it.status != TrackStatus.IDLE) it else it.copy(notice = ctx.getString(R.string.rec_recover_failed)) }
                    RouteStore.Recovery.None -> {}
                }
            }
        }
    }

    private fun restoreFromService(): Pair<Route?, Workout?>? {
        val pts = TrackingService.guide
        val w = TrackingService.workout
        if (pts.isNullOrEmpty() && w == null) return null
        return rebuildGuide(pts) to w
    }

    /** The flyover video export. Runs here so a rotation keeps it going; the share screen re-attaches to [video]. */
    val video = kotlinx.coroutines.flow.MutableStateFlow<com.cycler.app.ui.VideoState>(com.cycler.app.ui.VideoState.Idle)
    private var videoJob: kotlinx.coroutines.Job? = null
    @Volatile private var videoCancel = false
    @Volatile private var videoSilent = false

    fun startVideo(meta: RouteMeta, pts: List<Pt>, ground: com.cycler.app.share.Ground?, dark: Boolean, out: java.io.File, caption: String) {
        if (videoJob?.isActive == true) return
        videoCancel = false
        videoSilent = false
        video.value = com.cycler.app.ui.VideoState.Running(0f)
        val app = getApplication<Application>()
        videoJob = viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    com.cycler.app.share.VideoExport.export(
                        com.cycler.app.share.FlyoverScene(app, meta, pts, ground, dark), out,
                        { video.value = com.cycler.app.ui.VideoState.Running(it) }, { videoCancel },
                    )
                }.getOrDefault(false)
            }
            video.value = when {
                ok && !videoCancel -> com.cycler.app.ui.VideoState.Done(out, caption)
                videoCancel && videoSilent -> com.cycler.app.ui.VideoState.Idle
                videoCancel -> com.cycler.app.ui.VideoState.Cancelled
                else -> com.cycler.app.ui.VideoState.Failed
            }
        }
    }

    /** Stop the export; [silent] when the rider has left the share screen, so no message is shown later. */
    fun cancelVideo(silent: Boolean = false) {
        if (videoJob?.isActive != true) return
        videoSilent = silent
        videoCancel = true
    }

    /** True while a backup is being restored; the Settings screen shows a blocking "Restoring…" dialog. */
    var restoring by mutableStateOf(false)
        private set

    /** Restore a backup in the view model's scope, so leaving Settings does not skip the settings and care parts. */
    fun restoreBackup(uri: Uri) {
        if (restoring) return
        restoring = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            var pendingCare: String? = null
            var pendingPrefs: String? = null
            var pendingBikes: String? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use {
                        store.importAll(it) { name, text -> if (name == "care.json") pendingCare = text else if (name == "bikes.json") pendingBikes = text else if (name == "prefs.json") pendingPrefs = text }
                    }
                }
            }
            val n = result.getOrNull()
            val bad = result.exceptionOrNull()
            pendingBikes?.let { saveBikes(com.cycler.app.data.BikeList.restore(bikes, it)) }
            pendingCare?.let { saveCare(CareItem.parse(it)) }
            pendingPrefs?.let { if (prefs.importJson(it) > 0) reloadSettings() }
            restoring = false
            val msg = when {
                n != null && n > 0 -> "Restored $n files from the backup"
                bad is java.util.zip.ZipException || (bad == null && n == 0) -> "That file isn't a Cycler backup"
                else -> "Couldn't read the backup file. Check it and try again."
            }
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
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
        retryPlan = { findRoutes(points) }
        busy = true
        error = null
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                (0..2).map { alt -> async { runCatching { BRouter.route(points, profile, alt) } } }.awaitAll()
            }
            val ok = results.mapNotNull { it.getOrNull() }
                .distinctBy { "${it.distanceM.toInt() / 50}-${it.ascentM.toInt() / 5}" }
            if (ok.isEmpty()) {
                error = com.cycler.app.ui.RouteErrors.message(results.firstNotNullOfOrNull { it.exceptionOrNull() })
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
        retryPlan = { findLoops(start) }
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
                error = com.cycler.app.ui.RouteErrors.message(results.firstNotNullOfOrNull { it.exceptionOrNull() })
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
        retrySuggest = { suggestNearby(from) }
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
                suggestError = com.cycler.app.ui.RouteErrors.message(results.firstNotNullOfOrNull { it.exceptionOrNull() })
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

/** A followed route rebuilt from the points the recorder holds (name and totals are not kept there). */
fun rebuildGuide(pts: List<Pt>?): Route? {
    if (pts.isNullOrEmpty()) return null
    var d = 0.0
    for (i in 1 until pts.size) d += Geo.dist(pts[i - 1], pts[i])
    val meta = RouteMeta("", "Route", RouteType.PLANNED, System.currentTimeMillis(), d, 0.0, 0L, pts.first().lat, pts.first().lon)
    return Route(meta, pts)
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
        // A tap on a home-screen widget. Not on a restored instance (rotation, back from recents): the
        // intent then is the original launch one and must not start another ride.
        if (savedInstanceState == null && ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleWidgetIntent(intent)
        }
        handlePrivacyIntent(intent, savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
        handlePrivacyIntent(intent, null)
    }

    /** Health Connect's "privacy policy" link: open the Privacy page in Settings. */
    private fun handlePrivacyIntent(intent: Intent?, saved: android.os.Bundle?) {
        if (saved != null || ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        val action = intent?.action ?: return
        if (action != "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" && action != "android.intent.action.VIEW_PERMISSION_USAGE") return
        val vm = androidx.lifecycle.ViewModelProvider(this)[MainVm::class.java]
        vm.settingsPage = "privacy"
        vm.showSettings = true
    }

    private fun handleWidgetIntent(intent: Intent?) {
        runCatching { WidgetLaunch.handle(this, intent, androidx.lifecycle.ViewModelProvider(this)[MainVm::class.java]) }
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
                        Modifier.weight(1f).fillMaxSize().selectable(
                            selected = sel, role = androidx.compose.ui.semantics.Role.Tab,
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
    // Listen for position only while the app is on screen (the recorder has its own listener).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var started by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            started = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    var askedOnce by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasLocationPermission(ctx)
        askedOnce = true
    }
    val request = {
        if (askedOnce && !hasLocationPermission(ctx)) {
            // Android stops showing the dialog after a refusal (or an "Approximate" answer),
            // so send the rider to the app's settings page to switch on precise location.
            Toast.makeText(ctx, "Open Settings and set Location to Allow with Precise on", Toast.LENGTH_LONG).show()
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
    LaunchedEffect(Unit) { if (vm.onboarded && !granted) request() }

    val myLoc by produceState<Pt?>(initialValue = null, granted, started) {
        if (granted && started) {
            var savedAt = 0L
            var saved: Pt? = null
            locationFlow(ctx).collect {
                value = it
                val now = System.currentTimeMillis()
                if (com.cycler.app.ui.shouldPersistLoc(saved, savedAt, it, now)) {
                    vm.prefs.lastLoc = it
                    saved = it
                    savedAt = now
                }
                vm.refreshWeather(it)
            }
        }
    }

    if (!vm.onboarded) {
        Onboarding(vm, granted, request) { vm.onboarded = true; vm.prefs.onboarded = true }
        return
    }
    val share = vm.shareId
    if (share != null) {
        BackHandler { vm.shareId = null }
        ShareScreen(vm, share)
        return
    }
    val cut = vm.segmentFromRide
    if (cut != null) {
        BackHandler { vm.segmentFromRide = null }
        SegmentCreateScreen(vm, cut)
        return
    }
    val seg = vm.segmentId
    if (seg != null) {
        BackHandler { vm.segmentId = null }
        SegmentDetailScreen(vm, seg)
        return
    }
    if (vm.showSegments) {
        BackHandler { vm.showSegments = false }
        SegmentsScreen(vm)
        return
    }
    if (vm.showCare) {
        BackHandler { vm.showCare = false }
        BikeCareScreen(vm)
        return
    }
    if (vm.showOffline) {
        BackHandler { vm.showOffline = false }
        OfflineScreen(vm, myLoc)
        return
    }
    if (vm.showFieldEditor) {
        BackHandler { vm.showFieldEditor = false }
        RideScreenEditor(vm)
        return
    }
    val edit = vm.editId
    if (edit != null) {
        BackHandler { vm.editId = null; if (vm.editIsNew) { vm.editIsNew = false; vm.tab = Tab.RIDES } }
        EditRideScreen(vm, edit)
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
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.settingsPage = null } }
        SettingsScreen(vm, startPage = vm.settingsPage)
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
                Tab.RIDES -> RidesScreen(vm)
                Tab.ACTIVITY -> ActivityScreen(vm)
            }
        }
    }
}
