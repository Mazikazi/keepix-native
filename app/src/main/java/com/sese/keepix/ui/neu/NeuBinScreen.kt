package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sese.keepix.core.logic.binBadge
import com.sese.keepix.db.BinItemEntity

/**
 * The bin, restyled.
 *
 * Two selection states, exactly as the prototype: a plain state whose bulk row
 * is Restore all / Empty bin, and a select mode entered from the header button
 * (or a long press on a tile) whose row is Restore / Delete now over the picked
 * set. A picked tile flips from extruded to inset -- the tile does not tint.
 *
 * The countdown label on each tile comes from [RetentionWindow.daysLeft], the
 * same arithmetic the cleanup worker uses, so a tile never says "3d left" about
 * a row the next launch will delete.
 */
@Composable
fun NeuBinScreen(
    items: List<BinItemEntity>,
    isSessionMode: Boolean,
    onRestore: (BinItemEntity) -> Unit,
    onDelete: (List<BinItemEntity>) -> Unit,
    onOpenItem: (BinItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    var selecting by remember { mutableStateOf(false) }
    // Ids, not entities: the list is a Room Flow and re-emits new instances on
    // every unrelated write, which would silently empty an entity-keyed set.
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val liveIds = items.map { it.id }.toSet()
    val pickedLive = picked.filter { it in liveIds }.toSet()

    fun toggle(item: BinItemEntity) {
        picked = if (item.id in pickedLive) pickedLive - item.id else pickedLive + item.id
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NeuScreenTitle("Bin")
                    Text(
                        "${items.size} item${if (items.size == 1) "" else "s"}",
                        style = NeuType.metadata,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (items.isNotEmpty()) {
                        NeuTextButton(
                            label = if (selecting) "Done" else "Select",
                            onClick = { selecting = !selecting; picked = emptySet() },
                            color = if (selecting) c.accent else c.textSecondary,
                            selected = selecting,
                        )
                    }
                }
                Text(
                    if (isSessionMode) {
                        "These are deleted the next time you open Keepix. Restore anything now."
                    } else {
                        "Nothing is deleted from your phone until its countdown runs out."
                    },
                    style = NeuType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                if (items.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .neuInset(RoundedCornerShape(32.dp), offset = 10.dp, blur = 20.dp, strong = true)
                            .padding(horizontal = 22.dp, vertical = 46.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Bin's empty", style = NeuType.sectionHeader, color = c.textPrimary)
                            Text(
                                "Anything you swipe left lands here first.",
                                style = NeuType.body,
                                color = c.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                    }
                }
            }
        }

        items(items, key = { it.id }) { entity ->
            BinTile(
                item = entity,
                selecting = selecting,
                picked = entity.id in pickedLive,
                onTap = { if (selecting) toggle(entity) else onOpenItem(entity) },
                onLongPress = {
                    if (!selecting) { selecting = true; picked = setOf(entity.id) } else toggle(entity)
                },
            )
        }

        if (items.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    if (selecting) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .neuInset(RoundedCornerShape(16.dp), offset = 4.dp, blur = 8.dp)
                                .padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (pickedLive.isEmpty()) "Tap items to select"
                                else "${pickedLive.size} selected",
                                style = NeuType.buttonLabel,
                                color = if (pickedLive.isEmpty()) c.textSecondary else c.accent,
                                modifier = Modifier.weight(1f),
                            )
                            NeuTextButton(
                                label = if (pickedLive.size == items.size) "Clear" else "Select all",
                                onClick = {
                                    picked = if (pickedLive.size == items.size) emptySet() else liveIds
                                },
                                color = c.accent,
                                shape = RoundedCornerShape(13.dp),
                                paddingH = 14.dp, paddingV = 11.dp,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val chosen = items.filter { it.id in pickedLive }
                            NeuAccentButton(
                                label = "Restore",
                                onClick = { chosen.forEach(onRestore); picked = emptySet() },
                                enabled = chosen.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            )
                            NeuTextButton(
                                label = "Delete now",
                                onClick = { onDelete(chosen); picked = emptySet() },
                                enabled = chosen.isNotEmpty(),
                                color = c.clay,
                                shape = RoundedCornerShape(16.dp),
                                offset = 9.dp, blur = 16.dp, paddingV = 16.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NeuTextButton(
                                label = "Restore all",
                                onClick = { items.forEach(onRestore) },
                                color = c.textPrimary,
                                shape = RoundedCornerShape(16.dp),
                                offset = 9.dp, blur = 16.dp, paddingV = 16.dp,
                                modifier = Modifier.weight(1f),
                            )
                            NeuTextButton(
                                label = "Empty bin",
                                onClick = { onDelete(items) },
                                color = c.clay,
                                shape = RoundedCornerShape(16.dp),
                                offset = 9.dp, blur = 16.dp, paddingV = 16.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BinTile(
    item: BinItemEntity,
    selecting: Boolean,
    picked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = neu
    val badge = binBadge(
        item.retentionMode, item.deletedAt, item.expiryAt, System.currentTimeMillis()
    )
    val labelColor = if (badge.urgent) c.clay else c.textSecondary

    Box(
        Modifier
            .aspectRatio(3f / 4f)
            .then(
                if (selecting && picked) {
                    Modifier.neuInset(RoundedCornerShape(20.dp), offset = 5.dp, blur = 10.dp, strong = true)
                } else {
                    Modifier.neuExtruded(RoundedCornerShape(20.dp), offset = 5.dp, blur = 10.dp)
                }
            )
            .pointerInput(item.id, selecting) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .padding(7.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .neuInsetOver(RoundedCornerShape(14.dp), offset = 3.dp, blur = 6.dp),
        ) {
            AsyncImage(
                model = item.mediaUri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            badge.short,
            style = NeuType.microBadge,
            color = labelColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(5.dp)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(if (picked) c.accent else c.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) Text("✓", style = NeuType.buttonLabel, color = c.onAccent)
            }
        }
    }
}
