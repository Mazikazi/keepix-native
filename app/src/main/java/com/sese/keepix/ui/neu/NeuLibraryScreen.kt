package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.formatSizeShort
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The library of kept photos, grouped by month.
 *
 * Reached from the header's grid button, not the tab bar -- it is a detail of
 * the deck, not a sixth destination. Long-pressing a tile enters select mode,
 * where the picked tiles flip to inset and the bulk row appears.
 */
@Composable
fun NeuLibraryScreen(
    items: List<KeptItemEntity>,
    remainingInDeck: Int,
    largestFirst: Boolean,
    onSortChanged: (Boolean) -> Unit,
    onOpenItem: (KeptItemEntity) -> Unit,
    onBin: (List<KeptItemEntity>) -> Unit,
    onShrink: (List<KeptItemEntity>) -> Unit,
    onBack: () -> Unit,
    onReviewBin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    val liveIds = items.map { it.id }.toSet()
    val pickedLive = picked.filter { it in liveIds }.toSet()

    // Grouped here rather than in the ViewModel: it is a presentation concern
    // and re-deriving it is cheap next to decoding the thumbnails.
    val months = remember(items, largestFirst) { groupByMonth(items, largestFirst) }

    fun toggle(item: KeptItemEntity) {
        picked = if (item.id in pickedLive) pickedLive - item.id else pickedLive + item.id
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(NeuTouchTarget)
                            .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("←", style = NeuType.sectionHeader, color = c.textPrimary)
                    }
                    NeuScreenTitle("Library")
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .neuExtruded(RoundedCornerShape(28.dp), offset = 9.dp, blur = 16.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val done = remainingInDeck == 0
                    NeuMedallion(if (done) "✓" else "⊞", if (done) c.teal else c.accent)
                    Column {
                        Text(
                            if (done) "Every photo's been seen" else "Your kept photos",
                            style = NeuType.sectionHeader,
                            color = c.textPrimary,
                        )
                        Text(
                            if (done) {
                                "Browse what you kept below — new photos land here automatically."
                            } else {
                                "$remainingInDeck photo${if (remainingInDeck == 1) "" else "s"} " +
                                    "still waiting in the deck. Tap back any time to keep sorting."
                            },
                            style = NeuType.metadata,
                            color = c.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NeuTextButton(
                        label = if (selecting) "Done" else "Select",
                        onClick = { selecting = !selecting; picked = emptySet() },
                        color = if (selecting) c.accent else c.textSecondary,
                        selected = selecting,
                    )
                    Row(
                        Modifier
                            .weight(1f)
                            .neuInset(CircleShape, offset = 4.dp, blur = 8.dp)
                            .padding(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(true to "Largest first", false to "Newest first").forEach { (value, label) ->
                            NeuTextButton(
                                label = label,
                                onClick = { onSortChanged(value) },
                                color = if (largestFirst == value) c.accent else c.textSecondary,
                                shape = CircleShape,
                                paddingH = 6.dp, paddingV = 10.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (items.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .neuInset(RoundedCornerShape(32.dp), offset = 10.dp, blur = 20.dp, strong = true)
                            .padding(horizontal = 22.dp, vertical = 46.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Nothing kept yet. Swipe a card right and it lands here.",
                            style = NeuType.body,
                            color = c.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        months.forEach { month ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        month.label,
                        style = NeuType.sectionHeader,
                        color = c.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${month.items.size} item${if (month.items.size == 1) "" else "s"}",
                        style = NeuType.metadata,
                        color = c.textSecondary,
                    )
                }
            }
            items(month.items, key = { it.id }) { entity ->
                LibraryTile(
                    item = entity,
                    selecting = selecting,
                    picked = entity.id in pickedLive,
                    onTap = { if (selecting) toggle(entity) else onOpenItem(entity) },
                    onLongPress = {
                        if (!selecting) { selecting = true; picked = setOf(entity.id) } else toggle(entity)
                    },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                if (selecting) {
                    val chosen = items.filter { it.id in pickedLive }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .neuInset(RoundedCornerShape(16.dp), offset = 4.dp, blur = 8.dp)
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (chosen.isEmpty()) "Tap items to select" else "${chosen.size} selected",
                            style = NeuType.buttonLabel,
                            color = if (chosen.isEmpty()) c.textSecondary else c.accent,
                            modifier = Modifier.weight(1f),
                        )
                        NeuTextButton(
                            label = if (chosen.size == items.size && items.isNotEmpty()) "Clear" else "Select all",
                            onClick = {
                                picked = if (chosen.size == items.size) emptySet() else liveIds
                            },
                            color = c.accent,
                            shape = RoundedCornerShape(13.dp),
                            paddingH = 14.dp, paddingV = 11.dp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        NeuAccentButton(
                            label = "Shrink",
                            onClick = { onShrink(chosen); picked = emptySet() },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                        NeuTextButton(
                            label = "Move to bin",
                            onClick = { onBin(chosen); picked = emptySet() },
                            enabled = chosen.isNotEmpty(),
                            color = c.clay,
                            shape = RoundedCornerShape(16.dp),
                            offset = 9.dp, blur = 16.dp, paddingV = 16.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                NeuTextButton(
                    label = "Review the bin",
                    onClick = onReviewBin,
                    color = c.textPrimary,
                    shape = RoundedCornerShape(16.dp),
                    offset = 9.dp, blur = 16.dp, paddingV = 16.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LibraryTile(
    item: KeptItemEntity,
    selecting: Boolean,
    picked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = neu
    Column(
        Modifier
            .then(
                if (selecting && picked) {
                    Modifier.neuInset(RoundedCornerShape(20.dp), offset = 6.dp, blur = 12.dp, strong = true)
                } else {
                    Modifier.neuExtruded(RoundedCornerShape(20.dp), offset = 6.dp, blur = 12.dp)
                }
            )
            .pointerInput(item.id, selecting) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .neuInset(RoundedCornerShape(14.dp), offset = 5.dp, blur = 10.dp, strong = true)
                .padding(4.dp),
        ) {
            AsyncImage(
                model = item.mediaUri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(11.dp)),
            )
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(if (picked) c.accent else c.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Text("✓", style = NeuType.buttonLabel, color = c.onAccent)
                }
            }
            if (item.mediaType == "VIDEO" && item.durationMs > 0) {
                Text(
                    formatClock(item.durationMs),
                    style = NeuType.microBadge,
                    color = c.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(c.surface, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            item.displayName,
            style = NeuType.microBadge,
            color = c.accent,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 7.dp, bottom = 2.dp),
        )
    }
}

private data class LibraryMonth(val label: String, val items: List<KeptItemEntity>)

private val MonthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/**
 * Buckets by the month the photo was TAKEN, not when it was kept -- the user
 * looking for "that photo from June" means June, not the evening they sorted it.
 * Rows with no dateTaken fall back to keptAt so nothing silently disappears
 * into a 1970 bucket.
 */
private fun groupByMonth(items: List<KeptItemEntity>, largestFirst: Boolean): List<LibraryMonth> {
    val cal = Calendar.getInstance()
    fun stamp(item: KeptItemEntity) = if (item.dateTaken > 0) item.dateTaken else item.keptAt
    return items
        .groupBy {
            cal.timeInMillis = stamp(it)
            cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
        }
        .toSortedMap(compareByDescending { it })
        .map { (_, group) ->
            LibraryMonth(
                label = MonthFormat.format(Date(stamp(group.first()))),
                // ponytail: no per-item byte size on kept_items, so "largest
                // first" falls back to pixel count -- the only size signal the
                // row carries. Swap to a real size column when one exists.
                items = if (largestFirst) {
                    group.sortedByDescending { it.width.toLong() * it.height.toLong() }
                } else {
                    group.sortedByDescending(::stamp)
                },
            )
        }
}

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
