package com.cycler.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Pt
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.Locale
import kotlin.math.cos

/** The running map download. Lives outside the screen so a rotation or leaving the page does not lose it. */
private object OfflineDownload {
    /** -1 when nothing is downloading, else 0..1. */
    var progress by mutableFloatStateOf(-1f)
    var message by mutableStateOf<String?>(null)
    var tick by mutableIntStateOf(0)
    var region: OfflineRegion? = null
}

private const val DOWNLOAD_FAILED = "Couldn't download the map. Check your connection and try again."

private class Saved(val region: OfflineRegion, val name: String, val bytes: Long, val complete: Boolean)

/** Download the map for an area so it shows without mobile data. */
@Composable
fun OfflineScreen(vm: MainVm, myLoc: Pt?) {
    val ctx = LocalContext.current
    val inspection = LocalInspectionMode.current
    var regions by remember { mutableStateOf(listOf<Saved>()) }
    var radius by remember { mutableIntStateOf(1) }
    val progress = OfflineDownload.progress
    var reload by remember { mutableIntStateOf(0) }
    var listFailed by remember { mutableStateOf(false) }
    val radii = listOf(10, 25, 50)
    val here = myLoc ?: vm.prefs.lastLoc
    // Only the vector styles can be saved; CyclOSM's servers don't allow bulk downloads.
    val styleKey = if (vm.mapStyle == "cyclosm") "liberty" else vm.mapStyle

    LaunchedEffect(reload, OfflineDownload.tick) {
        if (inspection) return@LaunchedEffect
        runCatching {
            OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    listFailed = false
                    val list = offlineRegions?.toList() ?: emptyList()
                    if (list.isEmpty()) { regions = emptyList(); return }
                    val out = ArrayList<Saved>()
                    var left = list.size
                    list.forEach { r ->
                        val name = runCatching { JSONObject(String(r.metadata)).optString("name") }.getOrDefault("").ifBlank { "Saved area" }
                        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                out.add(Saved(r, name, status?.completedResourceSize ?: 0, status?.isComplete == true))
                                if (--left == 0) regions = out.sortedBy { it.name }
                            }
                            override fun onError(error: String?) {
                                out.add(Saved(r, name, 0, false))
                                if (--left == 0) regions = out.sortedBy { it.name }
                            }
                        })
                    }
                }
                override fun onError(error: String) { listFailed = true }
            })
        }.onFailure { listFailed = true }
    }

    fun download() {
        val c = here ?: return
        val km = radii[radius]
        val dLat = km * 1000.0 / 110540.0
        val dLon = km * 1000.0 / (111320.0 * cos(Math.toRadians(c.lat)))
        val bounds = LatLngBounds.Builder()
            .include(LatLng(c.lat - dLat, c.lon - dLon)).include(LatLng(c.lat + dLat, c.lon + dLon)).build()
        val def = OfflineTilePyramidRegionDefinition(
            "https://tiles.openfreemap.org/styles/$styleKey", bounds, 5.0, 14.0, ctx.resources.displayMetrics.density,
        )
        // Two decimals (about a kilometre): the same spot again is recognised as the same area.
        val label = String.format(Locale.US, "%d km around %.2f, %.2f", km, c.lat, c.lon)
        val meta = JSONObject().put("name", label).toString().toByteArray()
        val app = ctx.applicationContext
        fun create() {
            OfflineDownload.progress = 0f
            OfflineDownload.message = null
            runCatching {
                val manager = OfflineManager.getInstance(app)
                manager.setOfflineMapboxTileCountLimit(100_000)
                manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        OfflineDownload.region = offlineRegion
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                OfflineDownload.progress =
                                    if (status.requiredResourceCount > 0) status.completedResourceCount.toFloat() / status.requiredResourceCount else 0f
                                if (status.isComplete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    OfflineDownload.progress = -1f
                                    OfflineDownload.region = null
                                    OfflineDownload.tick++
                                    Toast.makeText(app, "Map saved for offline use", Toast.LENGTH_SHORT).show()
                                }
                            }
                            override fun onError(error: OfflineRegionError) {
                                // Individual tiles can fail and be retried; keep going, say it once, in plain words.
                                OfflineDownload.message = DOWNLOAD_FAILED
                            }
                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                OfflineDownload.progress = -1f
                                Toast.makeText(app, "That area is too large. Try a smaller radius.", Toast.LENGTH_LONG).show()
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }
                    override fun onError(error: String) {
                        OfflineDownload.progress = -1f
                        OfflineDownload.message = "Couldn't start the download. Try again in a moment."
                    }
                })
            }.onFailure { OfflineDownload.progress = -1f; OfflineDownload.message = DOWNLOAD_FAILED }
        }
        val dup = regions.firstOrNull { it.name == label }
        when {
            OfflineDownload.progress >= 0f -> {}
            dup != null && dup.complete ->
                Toast.makeText(ctx, "This area is already saved.", Toast.LENGTH_SHORT).show()
            dup != null -> {
                // An unfinished copy of the same area: replace it rather than keep two.
                OfflineDownload.progress = 0f
                runCatching {
                    dup.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { create() }
                        override fun onError(error: String) { create() }
                    })
                }.onFailure { create() }
            }
            else -> create()
        }
    }

    fun cancelDownload() {
        val r = OfflineDownload.region
        OfflineDownload.progress = -1f
        OfflineDownload.region = null
        OfflineDownload.message = null
        if (r != null) runCatching {
            r.setDownloadState(OfflineRegion.STATE_INACTIVE)
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { OfflineDownload.tick++ }
                override fun onError(error: String) { OfflineDownload.tick++ }
            })
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.showOffline = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Offline maps", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "Save the map for an area and it shows with no signal. Recording and following a saved route already " +
                        "work offline; planning a new route still needs a connection.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                SectionLabel("Save the area around me")
                Spacer(Modifier.height(8.dp))
                Segmented(radii.map { "$it km" }, radius, { radius = it }, Modifier.fillMaxWidth())
                Text(
                    "Uses the ${(MapStyles.all.firstOrNull { it.first == styleKey } ?: MapStyles.all.first()).second} map style. Larger areas take longer and more storage; use Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                if (progress >= 0f) {
                    Progress(progress)
                    Text(
                        "Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    CyButton("Cancel", { cancelDownload() }, Modifier.fillMaxWidth(), Btn.SECONDARY, height = 44.dp)
                } else {
                    CyButton(
                        if (here == null) "Waiting for your location…" else "Download", { download() },
                        Modifier.fillMaxWidth(), enabled = here != null, height = 50.dp,
                    )
                }

                OfflineDownload.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Cy.colors.danger, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(28.dp))
                SectionLabel("Saved areas")
                if (listFailed) {
                    Text(
                        "Couldn't load your saved areas.", style = MaterialTheme.typography.bodyMedium, color = Cy.colors.danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.material3.TextButton({ reload++ }) { Text("Retry") }
                }
                if (regions.isEmpty()) {
                    Text(
                        "None yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                regions.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                String.format(Locale.US, "%.1f MB", s.bytes / 1_048_576.0) + if (s.complete) "" else " · incomplete",
                                style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CyButton("Remove", {
                            runCatching {
                                s.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                    override fun onDelete() { reload++ }
                                    override fun onError(error: String) { reload++ }
                                })
                            }
                        }, kind = Btn.SECONDARY, height = 36.dp)
                    }
                    Hairline()
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
