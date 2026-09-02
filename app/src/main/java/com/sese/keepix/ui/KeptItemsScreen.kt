package com.sese.keepix.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.ui.components.*
import com.sese.keepix.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeptItemsScreen(
    items: List<KeptItemEntity>,
    onUnkeep: (KeptItemEntity) -> Unit,
    onItemTap: (KeptItemEntity) -> Unit,
    onBack: () -> Unit
) {
    var showUnkeepConfirmation by remember { mutableStateOf<KeptItemEntity?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "💚",
                            fontSize = 28.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = "KEPT ITEMS",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
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
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💚", fontSize = 96.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "NO KEEPS YET!",
                            style = MaterialTheme.typography.displaySmall,
                            color = KeepGreen,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Swipe right on photos you love 💖",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { keptItem ->
                        KeptGridItem(
                            item = keptItem,
                            onClick = { onItemTap(keptItem) },
                            onLongClick = { showUnkeepConfirmation = keptItem }
                        )
                    }
                }
            }
        }
    }

    showUnkeepConfirmation?.let { item ->
        AlertDialog(
            onDismissRequest = { showUnkeepConfirmation = null },
            title = { Text("Unkeep Item?", color = TextPrimary) },
            text = {
                Text(
                    "Remove \"${item.displayName}\" from kept items? It will appear in your swipe queue again.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnkeepConfirmation = null
                    onUnkeep(item)
                }) {
                    Text("Unkeep", color = KeepGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnkeepConfirmation = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeptGridItem(
    item: KeptItemEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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

        // Kept badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(4.dp)
                .background(Color(0x66000000))
        ) {
            Text(
                text = formatDate(item.keptAt),
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

private fun formatDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}
