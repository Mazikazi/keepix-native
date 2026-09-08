package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sese.keepix.core.logic.binBadge
import com.sese.keepix.db.BinItemEntity

/**
 * The bin item sheet: what it is, when it goes, and the two ways out.
 *
 * The countdown line is the load-bearing part -- it is the only place the user
 * is told, in words, that the file is still on the phone and exactly how long
 * that stays true.
 */
@Composable
fun NeuBinPreviewSheet(
    item: BinItemEntity,
    onRestore: () -> Unit,
    onDeleteNow: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    val badge = binBadge(
        item.retentionMode, item.deletedAt, item.expiryAt, System.currentTimeMillis()
    )

    Box(
        modifier
            .fillMaxSize()
            .background(c.surface.copy(alpha = 0.82f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .neuExtruded(
                    RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
                    offset = 12.dp, blur = 24.dp, strong = true,
                )
                .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeuGrabHandle(Modifier.align(Alignment.CenterHorizontally))

            Box(
                Modifier
                    .fillMaxWidth()
                    .neuInset(RoundedCornerShape(26.dp), offset = 8.dp, blur = 16.dp, strong = true)
                    .padding(10.dp),
            ) {
                AsyncImage(
                    model = item.mediaUri,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(212.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenFullscreen),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName.ifBlank { "Photo" },
                        style = NeuType.itemName,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (item.width > 0 && item.height > 0) append("${item.width} × ${item.height}")
                            if (item.mediaType == "VIDEO") {
                                if (isNotEmpty()) append(" · ")
                                append("Video")
                            }
                        }.ifBlank { "In the bin" },
                        style = NeuType.metadata,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .size(NeuTouchTarget)
                        .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", style = NeuType.sectionHeader, color = c.textSecondary)
                }
            }

            Text(
                badge.long,
                style = NeuType.buttonLabel,
                color = if (badge.urgent) c.clay else c.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .neuInset(RoundedCornerShape(16.dp), offset = 3.dp, blur = 6.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                NeuAccentButton("Restore to gallery", onRestore, Modifier.weight(1f))
                NeuTextButton(
                    label = "Delete now",
                    onClick = onDeleteNow,
                    color = c.clay,
                    shape = RoundedCornerShape(16.dp),
                    offset = 9.dp, blur = 16.dp, paddingH = 18.dp, paddingV = 17.dp,
                )
            }
        }
    }
}
