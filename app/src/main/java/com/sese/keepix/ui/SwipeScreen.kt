package com.sese.keepix.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sese.keepix.data.MediaItem
import com.sese.keepix.ui.components.*
import com.sese.keepix.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import android.media.MediaPlayer
import android.view.ViewGroup
import android.widget.VideoView

@Composable
fun SwipeScreen(
    mediaItems: List<MediaItem>,
    onSwipedLeft: (MediaItem) -> Unit,
    onSwipedRight: (MediaItem) -> Unit,
    onNavigateToBin: () -> Unit,
    onNavigateToKept: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTapCard: (MediaItem) -> Unit,
    onCardBoundsChanged: (MediaTransitionBounds) -> Unit = {},
    binCount: Int,
    keptCount: Int,
    deletedCount: Int,
    error: String? = null,
    onErrorDismiss: () -> Unit = {},
    // Plumbed through for Task 5's empty-state-vs-loading treatment; not
    // otherwise consumed here.
    isLoading: Boolean = false
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val swipeThreshold = with(LocalDensity.current) { (screenWidth * 0.4f).toPx() }
    var stackProgress by remember { mutableFloatStateOf(0f) }
    var sideLightProgress by remember { mutableFloatStateOf(0f) }
    var outgoingCard by remember { mutableStateOf<OutgoingCard?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onErrorDismiss()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bin button with badge
            BadgedBox(
                badge = {
                    if (binCount > 0) {
                        Badge(containerColor = DeleteRed) {
                            Text("$binCount", fontSize = 10.sp)
                        }
                    }
                }
            ) {
                IconButton(onClick = onNavigateToBin) {
                    Icon(Icons.Default.Delete, contentDescription = "Bin", tint = TextPrimary)
                }
            }

            // Kept button with badge
            BadgedBox(
                badge = {
                    if (keptCount > 0) {
                        Badge(containerColor = KeepGreen) {
                            Text("$keptCount", fontSize = 10.sp)
                        }
                    }
                }
            ) {
                IconButton(onClick = onNavigateToKept) {
                    Icon(Icons.Default.Favorite, contentDescription = "Kept", tint = KeepGreen)
                }
            }

            // Remaining counter
            if (mediaItems.isNotEmpty()) {
                GlassCard(cornerRadius = 20.dp) {
                    Text(
                        text = "${mediaItems.size} remaining",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
            }
        }

        // Main content
        if (mediaItems.isNotEmpty()) {
            val currentItem = mediaItems[0]
            SideSwipeLights(progress = sideLightProgress)

            // Card stack - show up to 3 cards
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp, bottom = 100.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Behind cards (visual stack)
                for (i in minOf(3, mediaItems.size - 1) downTo 1) {
                    if (i < mediaItems.size) {
                        val stackDepth = (i - stackProgress).coerceAtLeast(0f)
                        val scale = 1f - (stackDepth * 0.08f)
                        val yOffset = stackDepth * 20f
                        val alpha = 1f - (stackDepth * 0.1f)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(0.75f)
                                .offset(y = yOffset.dp)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    alpha = alpha
                                )
                                .glassmorphism(cornerRadius = 24.dp)
                        ) {
                            MediaCardContent(
                                mediaItem = mediaItems[i],
                                autoplayVideo = false,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Dark glass overlay to make background cards recede visually
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f + (stackDepth * 0.1f)))
                            )
                        }
                    }
                }

                // Top card (interactive)
                key(currentItem.id) {
                    SwipeableCard(
                        mediaItem = currentItem,
                        swipeThreshold = swipeThreshold,
                        onBoundsChanged = onCardBoundsChanged,
                        onSwiped = { direction, startOffset ->
                            outgoingCard = OutgoingCard(currentItem, direction, startOffset)
                            stackProgress = 0f
                            sideLightProgress = 0f
                            if (direction < 0f) {
                            onSwipedLeft(currentItem)
                            } else {
                                onSwipedRight(currentItem)
                            }
                        },
                        onTap = { onTapCard(currentItem) },
                        onSwipeProgress = { progress ->
                            stackProgress = abs(progress)
                            sideLightProgress = progress
                        }
                    )
                }

                outgoingCard?.let { card ->
                    key("outgoing-${card.mediaItem.id}") {
                        OutgoingSwipeCard(
                            card = card,
                            onFinished = { outgoingCard = null }
                        )
                    }
                }
            }

            // Metadata bar at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                GlassCard(cornerRadius = 16.dp) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (currentItem.isVideo) "🎬 VIDEO" else "📷 PHOTO",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Text("•", color = TextMuted)
                        Text(
                            text = formatDate(currentItem.dateAdded),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (currentItem.isVideo && currentItem.durationMs > 0) {
                            Text("•", color = TextMuted)
                            Text(
                                text = formatDuration(currentItem.durationMs),
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        } else {
            // Empty state - all done
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("✨", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "All Done!",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You kept $keptCount and deleted $deletedCount photos",
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (binCount > 0) {
                    com.sese.keepix.ui.components.GlassButton(
                        onClick = onNavigateToBin,
                        cornerRadius = 16.dp,
                        tintColor = AccentPurple,
                        tintAlpha = 0.2f
                    ) {
                        Text("View Bin ($binCount items)")
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
}

@Composable
private fun BoxScope.SideSwipeLights(progress: Float) {
    val rightAlpha = progress.coerceAtLeast(0f).coerceIn(0f, 1f) * 0.55f
    val leftAlpha = (-progress).coerceAtLeast(0f).coerceIn(0f, 1f) * 0.55f

    Canvas(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(120.dp)
            .blur(18.dp)
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    DeleteRed.copy(alpha = leftAlpha),
                    DeleteRed.copy(alpha = leftAlpha * 0.55f),
                    Color.Transparent
                )
            ),
            cornerRadius = CornerRadius(72.dp.toPx(), 72.dp.toPx())
        )
    }

    Canvas(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(120.dp)
            .blur(18.dp)
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    KeepGreen.copy(alpha = rightAlpha * 0.55f),
                    KeepGreen.copy(alpha = rightAlpha)
                )
            ),
            cornerRadius = CornerRadius(72.dp.toPx(), 72.dp.toPx())
        )
    }
}

private data class OutgoingCard(
    val mediaItem: MediaItem,
    val direction: Float,
    val startOffset: Float
)

@Composable
private fun OutgoingSwipeCard(
    card: OutgoingCard,
    onFinished: () -> Unit
) {
    var targetOffset by remember(card.mediaItem.id) { mutableFloatStateOf(card.startOffset) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
        finishedListener = {
            if (targetOffset != card.startOffset) {
                onFinished()
            }
        },
        label = "outgoingOffsetX"
    )

    LaunchedEffect(card.mediaItem.id) {
        targetOffset = 1500f * card.direction
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(0.75f)
            .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
            .graphicsLayer(
                rotationZ = animatedOffsetX / 20f,
                alpha = 1f - (abs(animatedOffsetX) / 2000f).coerceIn(0f, 0.3f)
            )
            .glassmorphism(cornerRadius = 24.dp, tintAlpha = 0.02f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaCardContent(
                mediaItem = card.mediaItem,
                autoplayVideo = false,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            )
        }
    }
}

@Composable
fun SwipeableCard(
    mediaItem: MediaItem,
    swipeThreshold: Float,
    onBoundsChanged: (MediaTransitionBounds) -> Unit,
    onSwiped: (direction: Float, startOffset: Float) -> Unit,
    onTap: () -> Unit,
    onSwipeProgress: (Float) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var swipeHandled by remember(mediaItem.id) { mutableStateOf(false) }

    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "offsetX"
    )

    val displayOffsetX = if (isDragging) offsetX else animatedOffsetX
    val rotation = displayOffsetX / 20f
    LaunchedEffect(displayOffsetX, swipeThreshold) {
        onSwipeProgress((displayOffsetX / swipeThreshold).coerceIn(-1f, 1f))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(0.75f)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onBoundsChanged(
                    MediaTransitionBounds(
                        left = bounds.left,
                        top = bounds.top,
                        width = bounds.width,
                        height = bounds.height
                    )
                )
            }
            .offset {
                IntOffset(
                    (if (isDragging) offsetX else animatedOffsetX).roundToInt(),
                    0 // Hardcode Y to 0 for strict X-axis swiping
                )
            }
            .graphicsLayer(
                rotationZ = rotation,
                alpha = 1f - (abs(if (isDragging) offsetX else animatedOffsetX) / 2000f).coerceIn(0f, 0.3f)
            )
            .pointerInput(mediaItem.id, swipeHandled) {
                detectTapGestures(
                    onTap = {
                        if (!swipeHandled) {
                            onTap()
                        }
                    }
                )
            }
            .pointerInput(mediaItem) {
                detectDragGestures(
                    onDragStart = {
                        if (!swipeHandled) {
                            isDragging = true
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val swipedRight = offsetX > swipeThreshold
                        val swipedLeft = offsetX < -swipeThreshold
                        if ((swipedRight || swipedLeft) && !swipeHandled) {
                            swipeHandled = true
                            onSwipeProgress(0f)
                            onSwiped(if (swipedRight) 1f else -1f, offsetX)
                        } else {
                            offsetX = 0f
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        offsetX = 0f
                    }
                ) { change, dragAmount ->
                    if (!swipeHandled) {
                        change.consume()
                        offsetX += dragAmount.x
                    }
                }
            }
            .glassmorphism(cornerRadius = 24.dp, tintAlpha = 0.02f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaCardContent(
                mediaItem = mediaItem,
                autoplayVideo = mediaItem.isVideo,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
            )

            // Glass border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            )

            // Video indicator
            if (mediaItem.isVideo) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "▶ ${formatDuration(mediaItem.durationMs)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaCardContent(
    mediaItem: MediaItem,
    autoplayVideo: Boolean,
    modifier: Modifier = Modifier
) {
    if (mediaItem.isVideo && autoplayVideo) {
        AutoplayVideo(
            uri = mediaItem.uri,
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = mediaItem.uri,
            contentDescription = "Media",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@Composable
private fun AutoplayVideo(
    uri: android.net.Uri,
    modifier: Modifier = Modifier
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(uri) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setVideoURI(uri)
                setOnPreparedListener { player: MediaPlayer ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    start()
                }
                videoViewRef = this
            }
        },
        update = { videoView ->
            if (!videoView.isPlaying) {
                videoView.setVideoURI(uri)
                videoView.start()
            }
        }
    )
}

private fun formatDate(epochSeconds: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(epochSeconds * 1000))
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
