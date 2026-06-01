package com.sese.keepix.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    onItemTap: (BinItemEntity) -> Unit,
    onBack: () -> Unit
) {
    var showEmptyConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
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
                        items(items) { binItem ->
                            BinGridItem(
                                item = binItem,
                                onClick = { onItemTap(binItem) }
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
}

@Composable
private fun BinGridItem(
    item: BinItemEntity,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .glassmorphism(cornerRadius = 12.dp)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = Uri.parse(item.mediaUri),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

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
                color = Color.White,
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
