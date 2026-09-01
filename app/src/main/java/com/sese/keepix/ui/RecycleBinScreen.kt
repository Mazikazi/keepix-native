package com.sese.keepix.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.ui.components.*
import com.sese.keepix.ui.theme.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    items: List<BinItemEntity>,
    isSessionMode: Boolean,
    onRestore: (BinItemEntity) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteSelected: (List<BinItemEntity>) -> Unit,
    onItemTap: (BinItemEntity) -> Unit,
    onBack: () -> Unit
) {
    var showEmptyConfirmation by remember { mutableStateOf(false) }

    // Multi-select state. Ids only (not entities) so a row that mutates or is
    // recreated elsewhere doesn't desync the "is this selected" check; the
    // LaunchedEffect below prunes against the live `items` list so a cleanup
    // pass removing rows out from under an open selection can't leave a
    // dangling id that later resolves to nothing (or, worse, a different row
    // that reused the id).
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteSelectedConfirmation by remember { mutableStateOf(false) }

    // Keep the selection in sync with the live list: a cleanup/expiry pass or
    // a restore from elsewhere can remove bin rows while this screen is open.
    // Drop any selected id that no longer exists, and fall out of selection
    // mode entirely if that empties the selection -- otherwise the "N
    // selected" bar would linger at 0 with both actions doing nothing.
    LaunchedEffect(items) {
        val currentIds = items.mapTo(mutableSetOf()) { it.id }
        if (selectedIds.any { it !in currentIds }) {
            selectedIds = selectedIds.intersect(currentIds)
        }
        if (selectionMode && selectedIds.isEmpty()) {
            selectionMode = false
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
    }

    // Back exits selection mode instead of leaving the screen.
    BackHandler(enabled = selectionMode) {
        exitSelectionMode()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { exitSelectionMode() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel selection",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                // Reuse the existing per-item restore path for each
                                // selected row rather than adding a batch ViewModel
                                // method -- restoreItem() is cheap (a single-row
                                // delete + splice) and already handles its own
                                // coroutine per call.
                                items.filter { it.id in selectedIds }.forEach { onRestore(it) }
                                exitSelectionMode()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Restore selected", tint = TextPrimary)
                        }
                        IconButton(
                            onClick = { showDeleteSelectedConfirmation = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = DeleteRed)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            } else {
                TopAppBar(
                    title = { Text("Recycle Bin", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { showEmptyConfirmation = true }) {
                                Text("Empty Bin", color = DeleteRed, fontWeight = FontWeight.Medium)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Session mode banner
                if (isSessionMode && items.isNotEmpty()) {
                    Surface(
                        color = BadgeOrange.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "⚠️ These items will be deleted when you reopen the app",
                            color = BadgeOrange,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (items.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🗑️", fontSize = 64.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Your bin is empty",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Deleted photos will appear here",
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items, key = { it.id }) { binItem ->
                            val isSelected = binItem.id in selectedIds
                            BinGridItem(
                                item = binItem,
                                isSelected = isSelected,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (isSelected) {
                                            selectedIds - binItem.id
                                        } else {
                                            selectedIds + binItem.id
                                        }
                                    } else {
                                        onItemTap(binItem)
                                    }
                                },
                                onLongClick = {
                                    selectionMode = true
                                    selectedIds = if (isSelected) {
                                        selectedIds - binItem.id
                                    } else {
                                        selectedIds + binItem.id
                                    }
                                },
                                onRestore = { onRestore(binItem) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Empty Bin confirmation dialog
    if (showEmptyConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirmation = false },
            title = { Text("Empty Bin?", color = TextPrimary) },
            text = {
                Text(
                    "Permanently delete all ${items.size} items? This cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyConfirmation = false
                    onDeleteConfirmed()
                }) {
                    Text("Delete All", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Delete-selected confirmation dialog
    if (showDeleteSelectedConfirmation) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmation = false },
            title = { Text("Delete Selected?", color = TextPrimary) },
            text = {
                Text(
                    "Permanently delete $count item${if (count == 1) "" else "s"}? This cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelectedConfirmation = false
                    onDeleteSelected(items.filter { it.id in selectedIds })
                    exitSelectionMode()
                }) {
                    Text("Delete", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BinGridItem(
    item: BinItemEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRestore: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .glassmorphism(cornerRadius = 12.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = Uri.parse(item.mediaUri),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AccentPurple.copy(alpha = 0.35f))
            )
        }

        // Glass overlay at bottom for badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .glassmorphism(cornerRadius = 0.dp, tintAlpha = 0.4f)
                .padding(4.dp)
        ) {
            Text(
                text = getBadgeText(item),
                color = getBadgeColor(item),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Video indicator
        if (item.mediaType == "VIDEO") {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        if (selectionMode) {
            // Decorative only (onCheckedChange = null): the whole tile is
            // already clickable above and toggles this same selection, so a
            // second, independent click target here would just be a second
            // way to do the identical thing and could desync from it.
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentPurple,
                    uncheckedColor = Color.White
                )
            )
        } else {
            IconButton(
                onClick = onRestore,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), shape = CircleShape)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Restore ${item.displayName}",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun getBadgeText(item: BinItemEntity): String {
    if (item.retentionMode == "SESSION") {
        return "Deletes on reopen"
    }

    val remainingMs = item.expiryAt - System.currentTimeMillis()
    if (remainingMs <= 0) return "Expired"

    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    return when {
        days > 1 -> "${days}d left"
        days == 1L -> "1 day left"
        else -> "< 1 day left"
    }
}

private fun getBadgeColor(item: BinItemEntity): Color {
    if (item.retentionMode == "SESSION") return BadgeOrange

    val remainingMs = item.expiryAt - System.currentTimeMillis()
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    return when {
        days <= 1 -> BadgeRedUrgent
        days <= 3 -> BadgeOrange
        else -> TextSecondary
    }
}
