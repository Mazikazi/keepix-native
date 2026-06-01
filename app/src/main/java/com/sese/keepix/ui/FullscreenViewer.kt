package com.sese.keepix.ui

import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.sese.keepix.ui.components.GlassCard
import com.sese.keepix.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Unified data for gallery items from any source (kept, bin, swipe).
 */
data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean
)

@Composable
fun FullscreenViewer(
    mediaUri: Uri,
    isVideo: Boolean,
    isBinMode: Boolean = false,
    transitionBounds: MediaTransitionBounds? = null,
    onKeepOrRestore: () -> Unit,
    onDeleteOrDeleteNow: () -> Unit,
    onDismiss: () -> Unit,
    tutorialComplete: Boolean = true,
    onTutorialDismiss: () -> Unit = {},
    // Gallery mode params
    galleryItems: List<GalleryItem> = emptyList(),
    initialIndex: Int = 0,
    onGalleryIndexChanged: (Int) -> Unit = {}
) {
    val isGalleryMode = galleryItems.size > 1

    var opened by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    // Dismiss swipe state
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Entry transition
    val transitionProgress by animateFloatAsState(
        targetValue = if (opened && !closing) 1f else 0f,
        animationSpec = if (closing) {
            tween(durationMillis = 180, easing = FastOutLinearInEasing)
        } else {
            tween(durationMillis = 260, easing = FastOutSlowInEasing)
        },
        label = "mediaSpotlight",
        finishedListener = {
            if (closing) onDismiss()
        }
    )
    val controlsAlpha = ((transitionProgress - 0.55f) / 0.45f).coerceIn(0f, 1f)

    // Dismiss drag produces scale/opacity feedback
    val dismissProgress = if (isDismissDragging) {
        (abs(dismissOffsetY) / 600f).coerceIn(0f, 1f)
    } else 0f
    val dismissScale = 1f - dismissProgress * 0.15f
    val dismissAlpha = 1f - dismissProgress * 0.5f
    val bgAlpha = transitionProgress * (1f - dismissProgress * 0.7f)

    // Animated dismiss return
    val animatedDismissY by animateFloatAsState(
        targetValue = if (isDismissDragging) dismissOffsetY else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dismissY"
    )
    val displayDismissY = if (isDismissDragging) dismissOffsetY else animatedDismissY

    fun startDismiss() {
        if (closing) return
        closing = true
        showControls = false
    }

    LaunchedEffect(mediaUri) { opened = true }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val startLeft = transitionBounds?.left ?: 0f
        val startTop = transitionBounds?.top ?: 0f
        val startWidth = transitionBounds?.width ?: screenWidthPx
        val startHeight = transitionBounds?.height ?: screenHeightPx
        val animatedLeft = lerp(startLeft, 0f, transitionProgress)
        val animatedTop = lerp(startTop, 0f, transitionProgress)
        val animatedWidth = lerp(startWidth, screenWidthPx, transitionProgress)
        val animatedHeight = lerp(startHeight, screenHeightPx, transitionProgress)
        val animatedCorner = 24f * (1f - transitionProgress)

        // Dismiss hint glow at top
        if (dismissOffsetY < -40f && isDismissDragging) {
            val glowAlpha = (abs(dismissOffsetY) / 300f).coerceIn(0f, 0.5f)
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = glowAlpha),
                            Color.White.copy(alpha = glowAlpha * 0.3f),
                            Color.Transparent
                        )
                    ),
                    cornerRadius = CornerRadius(72.dp.toPx(), 72.dp.toPx())
                )
            }
        }

        // Media content container
        Box(
            modifier = Modifier
                .offset { 
                    android.graphics.Point(
                        animatedLeft.roundToInt(), 
                        (animatedTop + displayDismissY).roundToInt()
                    ).let { androidx.compose.ui.unit.IntOffset(it.x, it.y) }
                }
                .size(
                    width = with(density) { animatedWidth.toDp() },
                    height = with(density) { animatedHeight.toDp() }
                )
                .clip(RoundedCornerShape(animatedCorner.dp))
                .graphicsLayer {
                    scaleX = dismissScale
                    scaleY = dismissScale
                    alpha = dismissAlpha
                }
        ) {
            if (isGalleryMode && transitionProgress >= 1f) {
                // Gallery mode: HorizontalPager for swipe left/right
                GalleryPager(
                    items = galleryItems,
                    initialIndex = initialIndex,
                    onIndexChanged = onGalleryIndexChanged,
                    onDismissDrag = { dy ->
                        isDismissDragging = true
                        dismissOffsetY = dy
                    },
                    onDismissEnd = {
                        isDismissDragging = false
                        if (dismissOffsetY < -150f) {
                            startDismiss()
                        }
                        dismissOffsetY = 0f
                    },
                    onTap = { showControls = !showControls }
                )
            } else {
                // Single item mode (swipe screen tap) — original behavior
                SingleItemViewer(
                    mediaUri = mediaUri,
                    isVideo = isVideo,
                    isBinMode = isBinMode,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    transitionProgress = transitionProgress,
                    isFlyingOff = remember { mutableStateOf(false) },
                    closing = closing,
                    showControls = showControls,
                    onShowControlsToggle = { showControls = !showControls },
                    onKeepOrRestore = onKeepOrRestore,
                    onDeleteOrDeleteNow = onDeleteOrDeleteNow,
                    onDismiss = { startDismiss() },
                    onSetClosing = { closing = true; showControls = false }
                )
            }
        }

        // Page indicator for gallery mode
        if (isGalleryMode && showControls && transitionProgress >= 1f && !closing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .graphicsLayer(alpha = controlsAlpha)
            ) {
                GlassCard(cornerRadius = 20.dp) {
                    Text(
                        text = "${initialIndex + 1} / ${galleryItems.size}",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Top bar - back button
        if (showControls && !closing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp)
                    .graphicsLayer(alpha = controlsAlpha)
            ) {
                IconButton(
                    onClick = { startDismiss() },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }
    }

    // Tutorial overlay
    if (!tutorialComplete) {
        GestureTutorialOverlay(onDismiss = onTutorialDismiss)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GalleryPager(
    items: List<GalleryItem>,
    initialIndex: Int,
    onIndexChanged: (Int) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    onTap: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { items.size }
    )

    // Report page changes
    LaunchedEffect(pagerState.currentPage) {
        onIndexChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val item = items[page]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalY = 0f
                        var totalX = 0f
                        var isVerticalDrag = false
                        var decided = false
                        val touchSlop = viewConfiguration.touchSlop
                        var dragged = false

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break

                            totalX = change.position.x - down.position.x
                            totalY = change.position.y - down.position.y

                            if (!decided && (abs(totalX) > touchSlop || abs(totalY) > touchSlop)) {
                                decided = true
                                isVerticalDrag = abs(totalY) > abs(totalX)
                            }

                            if (decided && isVerticalDrag && totalY < 0) {
                                // Swiping up — dismiss gesture
                                dragged = true
                                onDismissDrag(totalY)
                                change.consume()
                            }
                        } while (true)

                        if (dragged) {
                            onDismissEnd()
                        } else if (!decided) {
                            // Tap
                            onTap()
                        }
                    }
                }
        ) {
            if (item.isVideo) {
                FullscreenVideo(
                    uri = item.uri,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = "Fullscreen media",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SingleItemViewer(
    mediaUri: Uri,
    isVideo: Boolean,
    isBinMode: Boolean,
    screenWidthPx: Float,
    screenHeightPx: Float,
    transitionProgress: Float,
    isFlyingOff: MutableState<Boolean>,
    closing: Boolean,
    showControls: Boolean,
    onShowControlsToggle: () -> Unit,
    onKeepOrRestore: () -> Unit,
    onDeleteOrDeleteNow: () -> Unit,
    onDismiss: () -> Unit,
    onSetClosing: () -> Unit
) {
    val swipeThresholdRatio = 0.3f
    val flyOffDistance = 2000f
    val swipeThreshold = screenWidthPx * swipeThresholdRatio

    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    var swipeOffsetY by remember { mutableFloatStateOf(0f) }
    var isSwiping by remember { mutableStateOf(false) }

    var flyOffOffsetX by remember { mutableFloatStateOf(0f) }
    var flyOffOffsetY by remember { mutableFloatStateOf(0f) }

    val animatedFlyOffX by animateFloatAsState(
        targetValue = flyOffOffsetX,
        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing),
        label = "flyOffX",
        finishedListener = {
            if (isFlyingOff.value) {
                when {
                    flyOffOffsetX > 0f -> onKeepOrRestore()
                    flyOffOffsetX < 0f -> onDeleteOrDeleteNow()
                    flyOffOffsetY < 0f -> onDismiss()
                }
            }
        }
    )
    val animatedFlyOffY by animateFloatAsState(
        targetValue = flyOffOffsetY,
        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing),
        label = "flyOffY"
    )

    fun triggerFlyOff(direction: Float, isVertical: Boolean = false) {
        if (isFlyingOff.value) return
        isFlyingOff.value = true
        onSetClosing()
        if (isVertical) {
            flyOffOffsetY = -flyOffDistance
        } else {
            flyOffOffsetX = flyOffDistance * direction
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = swipeOffsetX + animatedFlyOffX
                translationY = swipeOffsetY + animatedFlyOffY
                if (isFlyingOff.value && flyOffOffsetY < 0f) {
                    alpha = 1f - (abs(animatedFlyOffY) / screenHeightPx).coerceIn(0f, 1f)
                }
            }
            .pointerInput(swipeThreshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var zoomStarted = false
                    var totalX = 0f
                    var totalY = 0f
                    var localSwiping = false

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.any { it.pressed }
                        if (!pressed) break

                        if (!zoomStarted && event.changes.size == 1) {
                            val change = event.changes[0]
                            totalX = change.position.x - down.position.x
                            totalY = change.position.y - down.position.y

                            if (!pastTouchSlop) {
                                if (abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                                    pastTouchSlop = true
                                    localSwiping = true
                                    isSwiping = true
                                }
                            }

                            if (pastTouchSlop && localSwiping && !isFlyingOff.value && transitionProgress >= 1f) {
                                swipeOffsetX = totalX
                                swipeOffsetY = if (abs(totalX) > abs(totalY)) 0f else totalY
                                change.consume()
                            }
                        }
                    } while (true)

                    if (localSwiping && !isFlyingOff.value) {
                        val swipedLeft = swipeOffsetX < -swipeThreshold
                        val swipedRight = swipeOffsetX > swipeThreshold
                        val swipedUp = swipeOffsetY < -swipeThreshold

                        when {
                            swipedLeft -> triggerFlyOff(-1f)
                            swipedRight -> triggerFlyOff(1f)
                            swipedUp -> triggerFlyOff(0f, isVertical = true)
                            else -> {
                                swipeOffsetX = 0f
                                swipeOffsetY = 0f
                            }
                        }
                    }
                    isSwiping = false

                    if (!pastTouchSlop && !zoomStarted && !isFlyingOff.value) {
                        onShowControlsToggle()
                    }
                }
            }
    ) {
        if (isVideo) {
            FullscreenVideo(uri = mediaUri, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = mediaUri,
                contentDescription = "Fullscreen media",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun GestureTutorialOverlay(onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }

    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(40.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Swipe gestures",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                GestureHint(emoji = "←", label = "Swipe left", description = "Delete", color = DeleteRed)
                GestureHint(emoji = "→", label = "Swipe right", description = "Keep", color = KeepGreen)
                GestureHint(emoji = "↑", label = "Swipe up", description = "Go back", color = TextSecondary)

                Spacer(modifier = Modifier.height(24.dp))

                Text(text = "Tap to dismiss", color = TextMuted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun GestureHint(emoji: String, label: String, description: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = emoji, fontSize = 32.sp, color = color,
            modifier = Modifier.width(48.dp), textAlign = TextAlign.Center
        )
        Column {
            Text(text = label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = color, fontSize = 14.sp)
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + ((stop - start) * fraction)
}

@Composable
private fun FullscreenVideo(uri: Uri, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(true) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var showPlayPause by remember { mutableStateOf(false) }

    DisposableEffect(uri) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setVideoURI(uri)
                    setOnPreparedListener { player: MediaPlayer ->
                        player.isLooping = true
                        start()
                        isPlaying = true
                    }
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            isPlaying = true
                        }
                        true
                    }
                    videoViewRef = this
                }
            },
            update = { }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showPlayPause = true })
                }
        )

        if (showPlayPause) {
            FilledIconButton(
                onClick = {
                    videoViewRef?.let { vv ->
                        if (vv.isPlaying) { vv.pause(); isPlaying = false }
                        else { vv.start(); isPlaying = true }
                    }
                    showPlayPause = false
                },
                modifier = Modifier.align(Alignment.Center).size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                )
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    color = Color.White, fontSize = 24.sp
                )
            }
        }
    }
}
