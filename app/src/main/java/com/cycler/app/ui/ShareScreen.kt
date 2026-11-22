package com.cycler.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.data.Route
import com.cycler.app.share.FlyoverScene
import com.cycler.app.share.Ground
import com.cycler.app.share.ShareCard
import com.cycler.app.share.VideoExport
import com.cycler.app.share.loadShareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Make something to post: an animated flyover video of the ride, or a still card. */
@Composable
fun ShareScreen(vm: MainVm, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val metas by vm.store.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == id }
    val route by produceState<Route?>(null, id) { value = withContext(Dispatchers.IO) { vm.store.load(id) } }
    val tick by vm.store.mediaTick.collectAsState()
    val files = remember(id, tick) { vm.store.media(id) }
    val photos by produceState(emptyList<Bitmap>(), files) {
        value = withContext(Dispatchers.IO) { files.take(6).mapNotNull { loadShareImage(it) } }
    }
    var mode by remember { mutableIntStateOf(0) }
    val appDark = Cy.colors.isDark
    var dark by remember { mutableStateOf(appDark) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cancel by remember { mutableStateOf(false) }

    // The map under the flyover arrives a moment later; until then (or offline) a plain grid is used.
    val ground by produceState<Ground?>(null, route, vm.mapStyle, dark) {
        val r = route
        value = if (r == null || inspection) null
        else VideoExport.mapGround(ctx, if (dark) "dark" else if (vm.mapStyle == "dark") "liberty" else vm.mapStyle, r.pts)
    }
    val scene = remember(route, photos, ground, dark, meta?.name) {
        val r = route
        if (r == null || meta == null) null else FlyoverScene(ctx, meta, r.pts, photos, ground, dark)
    }
    val card by produceState<Bitmap?>(null, route, photos, dark, meta?.name) {
        val r = route
        value = if (r == null || meta == null) null
        else withContext(Dispatchers.Default) { ShareCard.render(ctx, meta, r.pts, photos, dark) }
    }

    // Live preview: the same drawing code as the export, at a smaller size, looping.
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scene, mode, exporting) {
        val s = scene ?: return@LaunchedEffect
        if (mode != 0 || exporting) return@LaunchedEffect
        val w = 405
        val h = 720
        if (inspection) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { s.draw(Canvas(it), w, h, s.introMs + s.flyMs / 3) }
            return@LaunchedEffect
        }
        val buffers = arrayOf(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        var k = 0
        val started = System.currentTimeMillis()
        while (isActive) {
            val t = (System.currentTimeMillis() - started) % (s.durationMs + 900)
            val target = buffers[k]
            withContext(Dispatchers.Default) { s.draw(Canvas(target), w, h, minOf(t, s.durationMs)) }
            frame = target
            k = 1 - k
            delay(12)
        }
    }

    // Leaving the screen stops an export in progress.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { cancel = true } }

    fun shareFile(name: String) = File(ctx.cacheDir, "share").apply { mkdirs() }.resolve(name)
    val caption = meta?.let { "${it.name} · ${Fmt.km(it.distanceM)} · ↑ ${it.ascentM.toInt()} m" } ?: ""

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (!exporting) vm.shareId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Share", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Segmented(listOf("Light", "Dark"), if (dark) 1 else 0, { if (!exporting) dark = it == 1 }, Modifier.width(150.dp))
            }
            Segmented(
                listOf("Flyover video", "Card"), mode, { if (!exporting) mode = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                val shown = if (mode == 0) frame else card
                if (shown != null) {
                    Image(
                        shown.asImageBitmap(), contentDescription = if (mode == 0) "Flyover preview" else "Share card",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RadiusLarge)
                            .border(1.dp, Cy.colors.border, RadiusLarge),
                    )
                } else {
                    Text("Preparing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (mode == 0) {
                    Text(
                        when {
                            exporting -> "Making the video… ${(progress * 100).toInt()}%. Keep this screen open."
                            photos.isEmpty() -> "Tip: add photos on the ride page and they appear after the flyover."
                            else -> "${photos.size} photo${if (photos.size > 1) "s" else ""} from this ride follow the flyover."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (exporting) {
                        Progress(progress)
                        Spacer(Modifier.height(12.dp))
                        CyButton("Cancel", { cancel = true }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                    } else {
                        CyButton(
                            "Make video and share",
                            {
                                val r = route
                                val m = meta
                                if (r != null && m != null) {
                                    exporting = true
                                    progress = 0f
                                    cancel = false
                                    val g = ground
                                    val out = shareFile("cycler-flyover.mp4")
                                    scope.launch {
                                        val ok = withContext(Dispatchers.Default) {
                                            // Its own scene: the preview's one is busy drawing on another thread.
                                            VideoExport.export(
                                                FlyoverScene(ctx, m, r.pts, photos, g, dark), out,
                                                { progress = it }, { cancel },
                                            )
                                        }
                                        exporting = false
                                        if (ok) VideoExport.share(ctx, listOf(out), "video/mp4", caption)
                                        else if (!cancel) Toast.makeText(ctx, "Couldn't make the video on this phone", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            Modifier.fillMaxWidth(), icon = Icons.Outlined.IosShare, enabled = scene != null, height = 52.dp,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        fun send(withMedia: Boolean) {
                            val bmp = card ?: return
                            scope.launch {
                                val out = shareFile("cycler-ride.png")
                                withContext(Dispatchers.IO) { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                                if (withMedia) VideoExport.share(ctx, listOf(out) + files, "*/*", caption)
                                else VideoExport.share(ctx, listOf(out), "image/png", caption)
                            }
                        }
                        CyButton("Share card", { send(false) }, Modifier.weight(1f), icon = Icons.Outlined.IosShare, enabled = card != null, height = 52.dp)
                        if (files.isNotEmpty()) {
                            CyButton("With ${files.size} photos/videos", { send(true) }, Modifier.weight(1f), Btn.SECONDARY, enabled = card != null, height = 52.dp)
                        }
                    }
                }
            }
        }
    }
}
