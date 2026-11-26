package com.cycler.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cycler.app.data.Bike
import com.cycler.app.data.BikeColours
import com.cycler.app.data.BikeList
import com.cycler.app.data.BikePhotos
import com.cycler.app.data.BikeTotals
import com.cycler.app.share.loadShareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun bikeColour(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

/** A bike's picture if it has one, else its first letter on its colour. Decorative: the name always sits next to it. */
@Composable
fun BikeAvatar(bike: Bike, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val file = remember(bike.id) { BikePhotos.file(ctx, bike.id) }
    // A new picture keeps the same name, so the file's time is what tells the avatar to reload.
    val stamp = file?.takeIf { it.exists() }?.lastModified() ?: 0L
    val bmp by produceState<Bitmap?>(null, bike.id, stamp) {
        value = if (stamp == 0L || file == null) null else withContext(Dispatchers.IO) { loadShareImage(file, 240) }
    }
    val shape = RoundedCornerShape(size / 4)
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).border(1.dp, Cy.colors.border, shape),
        )
    } else {
        Box(modifier.size(size).clip(shape).background(bikeColour(bike.colourHex)), contentAlignment = Alignment.Center) {
            Text(bike.initial, color = Color.White, style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium)
        }
    }
}

/** A small tag with the bike's colour and name, for ride cards and pages. */
@Composable
fun BikeChip(bike: Bike?, modifier: Modifier = Modifier) {
    if (bike == null) return
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Cy.colors.subtle)
            .semantics(mergeDescendants = true) { contentDescription = "Bike: ${bike.name}" }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(bikeColour(bike.colourHex)))
        Spacer(Modifier.width(5.dp))
        Text(
            bike.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

/** The swatches to choose a bike's colour from; tapping the chosen one again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColourSwatches(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BikeColours.all.forEachIndexed { i, hex ->
            val on = hex == selected
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(if (on) "" else hex) }
                    .semantics { contentDescription = BikeColours.names[i] },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(bikeColour(hex)), contentAlignment = Alignment.Center) {
                    if (on) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = Color.White)
                }
            }
        }
    }
}

/** One row of the bike picker: avatar, name, make and model, odometer. */
@Composable
private fun BikeRow(b: Bike, totals: BikeTotals?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (selected) Cy.colors.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BikeAvatar(b, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.subtitle.isNotEmpty()) {
                Text(
                    b.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (totals != null) {
            Text(
                "%,.0f km".format(totals.odometerKm), style = MaterialTheme.typography.bodySmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.padding(start = 10.dp).size(20.dp), tint = Cy.colors.accent)
    }
}

/** The list inside the picker sheet: the active bikes, the chosen one marked. */
@Composable
fun BikePickerList(list: BikeList, selectedId: String, totals: Map<String, BikeTotals>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        Text("Bike for this ride", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            list.active.forEach { b ->
                Hairline(Modifier.padding(horizontal = 20.dp))
                BikeRow(b, totals[b.id], b.id == selectedId) { onPick(b.id) }
            }
        }
    }
}

/** A bottom sheet to choose the bike for the ride about to start. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikePickerSheet(list: BikeList, selectedId: String, totals: Map<String, BikeTotals>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surface,
    ) {
        BikePickerList(list, selectedId, totals, onPick)
    }
}

/** Distance by bike for the period on show, as simple bars in each bike's colour. */
@Composable
fun BikeBreakdown(shares: List<com.cycler.app.data.BikeShare>) {
    Spacer(Modifier.height(26.dp))
    SectionLabel("By bike")
    Spacer(Modifier.height(6.dp))
    val top = shares.maxOf { it.km }.coerceAtLeast(0.1)
    shares.forEach { s ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {
                contentDescription = "${s.bike.name}: ${Fmt.kmShort(s.km * 1000)} kilometres, ${s.rides} ride${if (s.rides == 1) "" else "s"}"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.bike.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp))
            Box(Modifier.weight(1f).height(10.dp)) {
                Box(
                    Modifier.fillMaxWidth((s.km / top).toFloat().coerceIn(0.02f, 1f)).height(10.dp).clip(Radius)
                        .background(bikeColour(s.bike.colourHex)),
                )
            }
            Text(
                "${Fmt.kmShort(s.km * 1000)} km", style = MaterialTheme.typography.bodySmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** The compact "Bike: name" line on the Ride screen; a tap opens the picker. */
@Composable
fun RideBikeRow(bike: Bike, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).clip(Radius)
            .clickable(role = Role.Button, onClickLabel = "Choose the bike for this ride", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Bike for this ride: ${bike.name}" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BikeAvatar(bike, 28.dp)
        Spacer(Modifier.width(10.dp))
        Text("Bike", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(bike.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
