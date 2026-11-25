package com.cycler.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycler.app.MainVm
import com.cycler.app.Tab
import com.cycler.app.data.Feeling
import com.cycler.app.data.RideFilter
import com.cycler.app.data.RideFilters
import com.cycler.app.data.RidePeriod
import com.cycler.app.data.RideSort
import com.cycler.app.data.RouteMeta
import com.cycler.app.data.RouteType
import com.cycler.app.share.loadShareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Photos and videos attached to a ride, with a tile to add more. Tap one to remove it. */
@Composable
fun MediaStrip(vm: MainVm, id: String, tile: Int = 68) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by vm.store.mediaTick.collectAsState()
    val media by produceState(emptyList<File>(), id, tick) { value = withContext(Dispatchers.IO) { vm.store.media(id) } }
    val thumbs by produceState(emptyList<Pair<File, Bitmap>>(), media) {
        value = withContext(Dispatchers.IO) { media.mapNotNull { f -> loadShareImage(f, 240)?.let { f to it } } }
    }
    var remove by remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            withContext(Dispatchers.IO) { uris.forEach { vm.store.addMedia(id, ctx.contentResolver, it) } }
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        thumbs.forEach { (file, bmp) ->
            Box(Modifier.size(tile.dp).clip(Radius).clickable { remove = file }) {
                Image(bmp.asImageBitmap(), "Ride photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (file.name.endsWith(".mp4")) {
                    Icon(Icons.Outlined.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
                }
            }
        }
        Row(
            Modifier.height(tile.dp).clip(Radius).border(1.dp, Cy.colors.border, Radius)
                .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (media.isEmpty()) "Add photos or videos" else "Add", style = MaterialTheme.typography.labelLarge)
        }
    }
    remove?.let { f ->
        AlertDialog(
            onDismissRequest = { remove = null },
            shape = RadiusLarge,
            title = { Text("Remove from this ride?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("The copy kept with this ride is removed. Your gallery is not touched.") },
            confirmButton = { TextButton({ vm.store.removeMedia(f); remove = null }) { Text("Remove", color = Cy.colors.danger) } },
            dismissButton = { TextButton({ remove = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

/** Your finished rides as cards, with a period filter, sort, search and tags above them. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(vm: MainVm) {
    val metas by vm.store.metas.collectAsState()
    val loaded by vm.store.isLoaded.collectAsState()
    val rides = remember(metas) { metas.filter { it.type == RouteType.RECORDED } }
    val f = vm.rideFilter
    var searchOpen by remember { mutableStateOf(f.query.isNotEmpty()) }
    val shown = remember(rides, f) { RideFilters.apply(rides, f) }
    val tags = remember(rides) { RideFilters.tagCounts(rides) }
    var sortMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(loaded, rides.size) { if (loaded) vm.maybeShowSupport(rides.size) }
    LaunchedEffect(searchOpen) { if (searchOpen && f.query.isEmpty()) runCatching { focus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "head") {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rides", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (rides.isNotEmpty()) {
                        IconButton({
                            if (searchOpen) vm.rideFilter = f.copy(query = "")
                            searchOpen = !searchOpen
                        }) {
                            Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, if (searchOpen) "Close search" else "Search rides")
                        }
                    }
                }
                if (rides.isEmpty()) {
                    Column(Modifier.padding(end = 12.dp)) {
                        Text(
                            "Your finished rides will appear here, with their photos and notes. Press Start on the Ride tab to record the first one.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        CyButton("Go to Ride", { vm.tab = Tab.RIDE }, kind = Btn.SECONDARY)
                    }
                }
            }
        }
        if (vm.supportCard && rides.isNotEmpty()) {
            item(key = "support") { SupportCard(onClose = { vm.supportCard = false }) }
        }
        if (rides.isNotEmpty()) {
            if (searchOpen) {
                item(key = "search") {
                    OutlinedTextField(
                        f.query, { vm.rideFilter = f.copy(query = it) }, singleLine = true, shape = Radius,
                        placeholder = { Text("Search name, notes and tags") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (f.query.isNotEmpty()) IconButton({ vm.rideFilter = f.copy(query = "") }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(20.dp)) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).focusRequester(focus),
                    )
                }
            }
            stickyHeader(key = "filters") {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    RidePeriod.values().forEach { p ->
                        FilterPill(p.label, f.period == p, { vm.rideFilter = f.copy(period = p) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (shown.isEmpty()) "0 rides" else
                            "${shown.size} ride${if (shown.size > 1) "s" else ""} · ${Fmt.km(shown.sumOf { it.distanceM })} · ${Fmt.dur(shown.sumOf { it.durationS })}",
                        style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (tags.isNotEmpty()) {
                        Box {
                            MenuLabel(f.tag ?: "Tags", f.tag != null) { tagMenu = true }
                            DropdownMenu(tagMenu, { tagMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                                DropdownMenuItem(
                                    text = { Text("All tags", fontWeight = if (f.tag == null) FontWeight.SemiBold else null) },
                                    onClick = { vm.rideFilter = f.copy(tag = null); tagMenu = false },
                                )
                                tags.forEach { (t, n) ->
                                    DropdownMenuItem(
                                        text = { Text("$t  ·  $n", fontWeight = if (t.equals(f.tag, true)) FontWeight.SemiBold else null) },
                                        onClick = { vm.rideFilter = f.copy(tag = t); tagMenu = false },
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        MenuLabel(f.sort.label, f.sort != RideSort.NEWEST) { sortMenu = true }
                        DropdownMenu(sortMenu, { sortMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                            RideSort.values().forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == f.sort) FontWeight.SemiBold else null) },
                                    onClick = { vm.rideFilter = f.copy(sort = s); sortMenu = false },
                                )
                            }
                        }
                    }
                }
                }
            }
            if (!loaded) {
                item(key = "loading") {
                    Text(
                        "Loading rides…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 36.dp),
                    )
                }
            } else if (shown.isEmpty()) {
                item(key = "none") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp)) {
                        Text("No rides match", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Nothing fits the period, search or tag you picked.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        CyButton("Clear filters", {
                            vm.rideFilter = RideFilter(sort = f.sort); searchOpen = false
                        }, kind = Btn.SECONDARY)
                    }
                }
            }
        }
        items(shown, key = { it.id }) { m ->
            RideCard(vm, m)
            Hairline(Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** A quiet text control with a small arrow, for the sort and tag menus. */
@Composable
private fun MenuLabel(text: String, active: Boolean, onClick: () -> Unit) {
    val c = if (active) Cy.colors.accent else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.heightIn(min = 44.dp).clip(Radius).clickable(onClick = onClick).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = c)
    }
}

@Composable
private fun RideCard(vm: MainVm, m: RouteMeta) {
    val tick by vm.store.mediaTick.collectAsState()
    val photos by produceState(emptyList<Bitmap>(), m.id, tick) {
        value = withContext(Dispatchers.IO) { vm.store.media(m.id).take(4).mapNotNull { com.cycler.app.share.ThumbCache.load(it, 360) } }
    }
    Column(Modifier.fillMaxWidth().clickable { vm.detailId = m.id }.padding(vertical = 18.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.date(m.createdAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Feeling.emoji(m.feeling)?.let {
                    Text("$it ${Feeling.label(m.feeling)}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                m.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (m.notes.isNotBlank()) {
                Text(
                    m.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Distance", Fmt.kmShort(m.distanceM), unit = "km")
                Metric("Time", Fmt.dur(m.durationS))
                Metric("Climb", "${m.ascentM.toInt()}", unit = "m")
                Metric("Avg", if (m.durationS > 0) Fmt.kmh(m.distanceM / m.durationS) else "–", unit = "km/h")
            }
        }
        Spacer(Modifier.height(12.dp))
        // The route's shape first, then any photos, in one strip.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RouteThumb(
                m.thumb,
                Modifier.height(150.dp).width(if (photos.isEmpty()) 372.dp else 150.dp),
            )
            photos.forEach { bmp ->
                Image(
                    bmp.asImageBitmap(), "Ride photo", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(150.dp).clip(Radius),
                )
            }
        }
        if (m.tags.isNotEmpty()) {
            Row(
                Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DifficultyTag(m.difficulty)
                m.tags.forEach { Tag(it) }
            }
        }
    }
}

/**
 * The page after a ride: give it a title, say how it felt, write a few lines,
 * tag it and add photos. Also used to edit a ride later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditRideScreen(vm: MainVm, id: String) {
    val metas by vm.store.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == id }
    if (meta == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.editId = null }
        return
    }
    val isNew = vm.editIsNew
    var name by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(meta.name) }
    var notes by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(meta.notes) }
    var feeling by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(meta.feeling) }
    var tags by androidx.compose.runtime.saveable.rememberSaveable(id, stateSaver = TagListSaver) { mutableStateOf(meta.tags) }
    var newTag by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    fun close(save: Boolean) {
        if (save) vm.store.updateDetails(id, name, notes, feeling, tags)
        vm.editId = null
        if (isNew) {
            vm.editIsNew = false
            vm.tab = Tab.RIDES
            vm.detailId = id
        }
    }

    fun addTag() {
        val t = newTag.trim()
        if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags = tags + t
        newTag = ""
    }

    // Going back from a just-finished ride keeps what was typed; from an edit it discards.
    androidx.activity.compose.BackHandler { close(save = isNew) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ close(save = isNew) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(if (isNew) "Save ride" else "Edit ride", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ close(save = true) }) { Text("Save") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (isNew) {
                    Text(
                        "Nice ride. It's saved already; add what you'd like to remember.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Distance", Fmt.kmShort(meta.distanceM), unit = "km")
                    Metric("Moving", Fmt.dur(meta.durationS))
                    Metric("Climb", "${meta.ascentM.toInt()}", unit = "m")
                    Metric("Avg", if (meta.durationS > 0) Fmt.kmh(meta.distanceM / meta.durationS) else "–", unit = "km/h")
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Title")
                OutlinedTextField(
                    name, { name = it }, singleLine = true, shape = Radius,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                Spacer(Modifier.height(22.dp))
                SectionLabel("How did it feel?")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Feeling.all.forEachIndexed { i, (emoji, label) ->
                        val sel = feeling == i + 1
                        Column(
                            Modifier.weight(1f).clip(Radius)
                                .background(if (sel) Cy.colors.accentSoft else Color.Transparent)
                                .border(if (sel) 1.5.dp else 1.dp, if (sel) Cy.colors.accent else Cy.colors.border, Radius)
                                .selectable(selected = sel, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { feeling = if (sel) 0 else i + 1 }).padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                SectionLabel("Notes")
                OutlinedTextField(
                    notes, { notes = it }, shape = Radius, minLines = 3, maxLines = 8,
                    placeholder = { Text("How was the route, the weather, the legs?") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                Spacer(Modifier.height(22.dp))
                SectionLabel("Photos and videos")
                Spacer(Modifier.height(8.dp))
                MediaStrip(vm, id, tile = 84)

                Spacer(Modifier.height(22.dp))
                SectionLabel("Tags")
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (Feeling.suggestedTags + tags.filter { it !in Feeling.suggestedTags }).forEach { t ->
                        val sel = t in tags
                        Text(
                            t, style = MaterialTheme.typography.labelLarge,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(1.dp, if (sel) MaterialTheme.colorScheme.primary else Cy.colors.border, RoundedCornerShape(50))
                                .toggleable(value = sel, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { tags = if (sel) tags - t else tags + t })
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    newTag, { newTag = it }, singleLine = true, shape = Radius,
                    placeholder = { Text("Add your own tag") },
                    trailingIcon = { if (newTag.isNotBlank()) TextButton({ addTag() }) { Text("Add") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onDone = { addTag() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                CyButton(if (isNew) "Save ride" else "Save changes", { close(save = true) }, Modifier.fillMaxWidth(), height = 52.dp)
            }
        }
    }
}


/** A quiet, dismissible card asking for support. Never covers anything: it sits in the Rides list. */
@Composable
private fun SupportCard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .border(1.dp, Cy.colors.border, Radius).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enjoying Cycler?", style = MaterialTheme.typography.titleSmall)
            Text(
                "It is free, with no ads or tracking, and made by one person. If it helps your rides, you can buy me a coffee.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton({ openUrl(ctx, com.cycler.app.data.SupportPrompt.URL); onClose() }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Buy me a coffee")
            }
        }
        IconButton(onClose) { Icon(Icons.Outlined.Close, "Close", Modifier.size(20.dp)) }
    }
}
