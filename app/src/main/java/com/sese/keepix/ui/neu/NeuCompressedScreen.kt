package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sese.keepix.db.CompressedItemEntity
import com.sese.keepix.utils.formatSizeShort

/**
 * The Compressed tab: what shrinking has actually bought, and the receipt for
 * each file.
 *
 * Both numbers come from `compressed_items`, which is only written after
 * PhotoCompressor has verified the rewrite -- so nothing here is an estimate.
 * An install upgraded from before that table existed starts empty, which is the
 * honest answer rather than a back-filled guess.
 */
@Composable
fun NeuCompressedScreen(
    items: List<CompressedItemEntity>,
    totalSavedBytes: Long,
    modifier: Modifier = Modifier,
) {
    val c = neu
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            NeuScreenTitle("Compressed", Modifier.padding(top = 6.dp, bottom = 14.dp))
        }
        item {
            NeuPanel(padding = 24.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(
                        Modifier
                            .size(74.dp)
                            .neuInset(CircleShape, offset = 10.dp, blur = 20.dp, strong = true),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(34.dp).background(c.accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↓", style = NeuType.sectionHeader, color = c.onAccent)
                        }
                    }
                    Column {
                        NeuOverline("Saved so far")
                        Text(
                            formatSizeShort(totalSavedBytes),
                            style = NeuType.screenTitle,
                            color = c.textPrimary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 5.dp),
                        )
                        Text(
                            if (items.isEmpty()) {
                                "Swipe a photo down to shrink it. The file stays on your phone."
                            } else {
                                "${items.size} file${if (items.size == 1) "" else "s"} shrunk, all still on your phone."
                            },
                            style = NeuType.metadata,
                            color = c.textSecondary,
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .neuInset(RoundedCornerShape(32.dp), offset = 10.dp, blur = 20.dp, strong = true)
                        .padding(horizontal = 22.dp, vertical = 46.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nothing shrunk yet.",
                        style = NeuType.body,
                        color = c.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        items(items, key = { it.mediaUri }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuExtruded(RoundedCornerShape(24.dp), offset = 5.dp, blur = 10.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .width(52.dp)
                        .height(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .neuInsetOver(RoundedCornerShape(14.dp), offset = 3.dp, blur = 6.dp),
                ) {
                    AsyncImage(
                        model = entry.mediaUri,
                        contentDescription = entry.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayName,
                        style = NeuType.itemName,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            formatSizeShort(entry.beforeBytes),
                            style = NeuType.metadata,
                            color = c.textSecondary,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        Text("→", style = NeuType.metadata, color = c.textSecondary)
                        Text(
                            formatSizeShort(entry.afterBytes),
                            style = NeuType.buttonLabel,
                            color = c.tealDeep,
                        )
                    }
                }
                Text(
                    entry.tier,
                    style = NeuType.microBadge,
                    color = c.accent,
                    modifier = Modifier
                        .neuInset(CircleShape, offset = 3.dp, blur = 6.dp)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                )
            }
        }
    }
}
