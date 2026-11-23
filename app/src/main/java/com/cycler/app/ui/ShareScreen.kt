package com.cycler.app.ui

import androidx.compose.foundation.selection.selectable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import com.cycler.app.data.RouteMeta
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
import com.cycler.app.share.CardSpec
import com.cycler.app.share.CardStats
import com.cycler.app.share.CardStyle
import com.cycler.app.share.Cards
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
fun ShareScreen(vm: MainVm, id: String, startMode: Int = 0, startEditing: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val metas by vm.store.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == id }
    var routeLoaded by remember(id) { mutableStateOf(false) }
    val route by produceState<Route?>(null, id) { value = withContext(Dispatchers.IO) { vm.store.load(id) }; routeLoaded = true }
    val tick by vm.store.mediaTick.collectAsState()
    val files by produceState(emptyList<java.io.File>(), id, tick) { value = withContext(Dispatchers.IO) { vm.store.media(id) } }
    val photos by produceState(emptyList<Bitmap>(), files) {
        value = withContext(Dispatchers.IO) { files.take(6).mapNotNull { loadShareImage(it) } }
    }
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startMode) }
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startEditing) }
    var spec by androidx.compose.runtime.saveable.rememberSaveable(id, stateSaver = CardSpecSaver) { mutableStateOf(CardSpec(meta?.name ?: "Ride")) }
    val styles = CardStyle.entries
    val pager = rememberPagerState { styles.size }
    val appDark = Cy.colors.isDark
    var dark by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(appDark) }
    val videoState by vm.video.collectAsState()
    val exporting = videoState is VideoState.Running
    val progress = (videoState as? VideoState.Running)?.progress ?: 0f
    // The result of an export that finished while this screen was (re)attached.
    LaunchedEffect(videoState) {
        when (val v = videoState) {
            is VideoState.Done -> { vm.video.value = VideoState.Idle; VideoExport.share(ctx, listOf(v.file), "video/mp4", v.caption) }
            VideoState.Failed -> { vm.video.value = VideoState.Idle; Toast.makeText(ctx, "Couldn't make the video on this phone", Toast.LENGTH_LONG).show() }
            VideoState.Cancelled -> { vm.video.value = VideoState.Idle; Toast.makeText(ctx, "Export cancelled", Toast.LENGTH_SHORT).show() }
            else -> {}
        }
    }

    // Map pictures arrive a moment later; until then (or offline) the flyover uses a plain grid
    // and the map cards fall back to the route's outline.
    val mapLight by produceState<Ground?>(null, route, vm.mapStyle) {
        val r = route
        value = if (r == null || inspection) null
        else VideoExport.mapGround(ctx, if (vm.mapStyle == "dark") "liberty" else vm.mapStyle, r.pts)
    }
    val mapDark by produceState<Ground?>(null, route) {
        val r = route
        value = if (r == null || inspection) null else VideoExport.mapGround(ctx, "dark", r.pts)
    }
    val ground = if (dark) mapDark else mapLight
    val scene = remember(route, ground, dark, meta?.name) {
        val r = route
        if (r == null || meta == null) null else FlyoverScene(ctx, meta, r.pts, ground, dark)
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

    // Leaving the screen stops an export in progress; a rotation (screen still open) does not.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (vm.shareId == null) vm.cancelVideo(silent = true) } }

    fun shareFile(name: String) = File(ctx.cacheDir, "share").apply { mkdirs() }.resolve(name)
    val caption = meta?.let { "${it.name} · ${Fmt.km(it.distanceM)} · ↑ ${it.ascentM.toInt()} m" } ?: ""

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (!exporting) vm.shareId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Share", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (mode == 0) {
                    Segmented(listOf("Light", "Dark"), if (dark) 1 else 0, { if (!exporting) dark = it == 1 }, Modifier.width(150.dp))
                }
            }
            Segmented(
                listOf("Flyover video", "Cards"), mode, { if (!exporting) mode = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )

            val r = route
            if (mode == 0) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val shown = frame
                    if (shown != null) {
                        Image(
                            shown.asImageBitmap(), contentDescription = "Flyover preview", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RadiusLarge)
                                .border(1.dp, Cy.colors.border, RadiusLarge),
                        )
                    } else if (routeLoaded && route == null) {
                        UnreadableTrack { vm.store.delete(id); vm.shareId = null; vm.detailId = null }
                    } else Text("Preparing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (r != null && meta != null) {
                // Swipe through the styles; each page draws the same ride with the same choices.
                HorizontalPager(
                    state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 56.dp), pageSpacing = 14.dp,
                ) { page ->
                    val style = styles[page]
                    val bmp by produceState<Bitmap?>(null, style, spec, photos, mapLight, mapDark) {
                        value = withContext(Dispatchers.Default) { Cards.render(ctx, style, spec, meta, r.pts, photos, mapLight, mapDark, 0.5f) }
                    }
                    Box(Modifier.fillMaxSize().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        bmp?.let {
                            Image(
                                it.asImageBitmap(), contentDescription = style.label, contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxHeight().aspectRatio(spec.w.toFloat() / spec.h).clip(RadiusLarge)
                                    // The see-through sticker needs something behind it to be visible here.
                                    .background(if (style == CardStyle.STICKER) Color(0xFF55606E) else Color.Transparent)
                                    .border(1.dp, Cy.colors.border, RadiusLarge),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(styles[pager.currentPage].label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${pager.currentPage + 1} of ${styles.size} · swipe for more", style = MaterialTheme.typography.bodySmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (editing) CardEditor(vm, id, meta, spec, photos.size) { spec = it }
            } else {
                Box(Modifier.weight(1f))
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (mode == 0) {
                    Text(
                        if (exporting) "Making the video… ${(progress * 100).toInt()}%. Keep this screen open."
                        else "The map flyover of your ride, about ${(scene?.durationMs ?: 0) / 1000} seconds.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (exporting) {
                        Progress(progress)
                        Spacer(Modifier.height(12.dp))
                        CyButton("Cancel", { vm.cancelVideo() }, Modifier.fillMaxWidth(), Btn.SECONDARY)
                    } else {
                        CyButton(
                            "Make video and share",
                            {
                                val r = route
                                val m = meta
                                if (r != null && m != null) {
                                    // Runs in the view model, with its own scene: the preview's one is busy drawing.
                                    vm.startVideo(m, r.pts, ground, dark, shareFile("cycler-flyover.mp4"), caption)
                                }
                            },
                            Modifier.fillMaxWidth(), icon = Icons.Outlined.IosShare, enabled = scene != null, height = 52.dp,
                        )
                    }
                } else {
                    val style = styles[pager.currentPage]
                    if (!editing) {
                        Text(
                            when {
                                style.usesPhoto && photos.isEmpty() -> "This style wants a photo. Tap Edit to add one."
                                style == CardStyle.STICKER -> "Saved with a see-through background, to place over your own photo or story."
                                style.usesMap && (if (style == CardStyle.MAP_DARK) mapDark else mapLight) == null -> "Loading the map…"
                                else -> "Tap Edit to change the title, the numbers shown, the photo and the size."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CyButton(if (editing) "Done" else "Edit", { editing = !editing }, Modifier.weight(1f), Btn.SECONDARY, height = 52.dp)
                        CyButton(
                            "Share",
                            {
                                val rt = route
                                val m = meta
                                if (rt != null && m != null) scope.launch {
                                    val out = shareFile("cycler-ride.png")
                                    withContext(Dispatchers.Default) {
                                        val full = Cards.render(ctx, style, spec, m, rt.pts, photos, mapLight, mapDark)
                                        out.outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    }
                                    VideoExport.share(ctx, listOf(out), "image/png", caption)
                                }
                            },
                            Modifier.weight(1.4f), icon = Icons.Outlined.IosShare, enabled = route != null, height = 52.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadableTrack(onDelete: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This ride's track couldn't be read.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.TextButton(onDelete) { Text("Delete", color = Cy.colors.danger) }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Cy.colors.border, RoundedCornerShape(50))
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Everything on the card that can be changed: words, which numbers, which photo, what shape. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardEditor(vm: MainVm, id: String, meta: RouteMeta, spec: CardSpec, photoCount: Int, onChange: (CardSpec) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        SectionLabel("Title")
        OutlinedTextField(
            spec.title, { onChange(spec.copy(title = it)) }, singleLine = true, shape = Radius,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        SectionLabel("Numbers, up to four", Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardStats.all.filter { CardStats.of(it.first, meta) != null }.forEach { (key, label) ->
                val on = key in spec.stats
                Pill(label, on) {
                    val next = if (on) spec.stats - key else if (spec.stats.size < 4) spec.stats + key else spec.stats
                    // Keep them in a steady order whatever order they were tapped in.
                    onChange(spec.copy(stats = CardStats.all.map { it.first }.filter { it in next }))
                }
            }
        }

        SectionLabel("Show", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Date", spec.showDate) { onChange(spec.copy(showDate = !spec.showDate)) }
            Pill("Route shape", spec.showRoute) { onChange(spec.copy(showRoute = !spec.showRoute)) }
            Pill("Cycler name", spec.showMark) { onChange(spec.copy(showMark = !spec.showMark)) }
        }

        SectionLabel("Size", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Segmented(
            listOf("Story 9:16", "Post 4:5"), if (spec.h == 1920) 0 else 1,
            { onChange(spec.copy(h = if (it == 0) 1920 else 1350)) }, Modifier.fillMaxWidth(),
        )

        Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (photoCount > 1) "Photo ${(spec.photo % photoCount) + 1} of $photoCount" else "Photos", Modifier.weight(1f))
            if (photoCount > 1) {
                CyButton("Use next photo", { onChange(spec.copy(photo = (spec.photo + 1) % photoCount)) }, kind = Btn.SECONDARY, height = 34.dp)
            }
        }
        MediaStrip(vm, id, tile = 60)
        Spacer(Modifier.height(8.dp))
    }
}
