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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.geometry.Offset

enum class SwipeAction { DELETE, KEEP, FAVORITE }

/**
 * Which action a released drag commits to, or null to spring back.
 *
 * Horizontal wins ties (`>=`): keep/delete are the common outcomes and favorite is
 * deliberate, so an ambiguous diagonal must never silently star an item. Only
 * upward vertical commits -- downward springs back, leaving room for a future
 * pull-down gesture without stealing it now.
 */
fun resolveSwipeAction(dx: Float, dy: Float, threshold: Float): SwipeAction? {
    val horizontal = abs(dx) >= abs(dy)
    return when {
        horizontal && dx >= threshold -> SwipeAction.KEEP
        horizontal && dx <= -threshold -> SwipeAction.DELETE
        !horizontal && dy <= -threshold -> SwipeAction.FAVORITE
        else -> null
    }
}

@Composable
fun SwipeScreen(
    mediaItems: List<MediaItem>,
    onSwipedLeft: (MediaItem) -> Unit,
    onSwipedRight: (MediaItem) -> Unit,
    onSwipedUp: (MediaItem) -> Unit = {},
    onNavigateToBin: () -> Unit,
    onNavigateToKept: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTapCard: (MediaItem) -> Unit,
    onCardBoundsChanged: (MediaTransitionBounds) -> Unit = {},
    binCount: Int,
    keptCount: Int,
    // Session-scoped count of items kept this session (Defect 12) — used only
    // in the empty-state summary below, alongside the also-session-scoped
    // deletedCount. keptCount above stays the all-time DB total; it's correct
    // as-is for the kept button's badge.
    sessionKeptCount: Int,
    deletedCount: Int,
    error: String? = null,
    onErrorDismiss: () -> Unit = {},
    // Distinguishes the initial-load spinner from the (post-load) empty
    // states below. A single flag is enough: the main content branch below
    // checks mediaItems.isNotEmpty() first, so a top-up flipping this while
    // cards are still on screen never reaches the loading/empty branches at
    // all -- it only matters when mediaItems is actually empty.
    isLoading: Boolean = false,
    // True once pagination has genuinely exhausted the device library.
    // False while nothing has loaded yet, and also false if a batch fetch
    // failed with cards still to come (Carried N3) -- both cases need to be
    // told apart from "really finished" in the empty state below, since only
    // one of them warrants a retry affordance. Defaults to true so a caller
    // that hasn't wired it up yet degrades to the old empty-state look
    // rather than showing a bogus error.
    reachedEnd: Boolean = true,
    // False until the very first loadMedia() call has completed (success or
    // failure). Cold start on a returning user (startDestination == "swipe")
    // composes this screen with mediaItems=[], isLoading=false, reachedEnd=
    // false on its very first frame -- loadMedia() only flips isLoading true
    // from inside a LaunchedEffect's coroutine body, which cannot have run
    // yet at that point. Without this flag that first frame fell into the
    // branch below meant for a genuine load FAILURE, flashing "Couldn't load
    // your library" for one frame on every cold start. Defaults to true so a
    // caller that hasn't wired it up degrades to the old behavior, matching
    // reachedEnd's own default.
    hasLoadedOnce: Boolean = true,
    onRetry: () -> Unit = {},
    // True while a fullscreen viewer is open above this screen. Used only to
    // pause the top card's autoplaying video -- otherwise it keeps playing
    // (silently) underneath the fullscreen one.
    isFullscreenOpen: Boolean = false,
    // True only on API 34+ when the user granted READ_MEDIA_VISUAL_USER_SELECTED
    // ("Select photos…") without the full pair. Shown only in the reachedEnd
    // empty state below: that's where the confusion this notice heads off
    // actually surfaces -- a small selection produces "All Done!" after just
    // a handful of swipes with no explanation otherwise.
    hasOnlyPartialMediaAccess: Boolean = false
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

            // Guards both input paths (gesture and the bottom buttons) against
            // firing twice for the same card -- e.g. a rapid double-tap on
            // KEEP, or a button tap immediately followed by a drag on the
            // still-visible card underneath. Resets automatically once a new
            // card reaches the top (removeSwipedItem/spliceIntoQueue land
            // asynchronously, so the old card can remain top-of-stack for a
            // moment after its swipe was already accepted).
            var swipeInProgress by remember(currentItem.id) { mutableStateOf(false) }

            fun performSwipe(action: SwipeAction, startOffset: Offset) {
                if (swipeInProgress) return
                swipeInProgress = true
                outgoingCard = OutgoingCard(currentItem, action, startOffset)
                stackProgress = 0f
                sideLightProgress = 0f
                when (action) {
                    SwipeAction.DELETE -> onSwipedLeft(currentItem)
                    SwipeAction.KEEP -> onSwipedRight(currentItem)
                    SwipeAction.FAVORITE -> onSwipedUp(currentItem)
                }
            }

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
                        autoplayVideo = currentItem.isVideo && !isFullscreenOpen,
                        interactive = !swipeInProgress,
                        onSwiped = { action, startOffset -> performSwipe(action, startOffset) },
                        onTap = { onTapCard(currentItem) },
                        onSwipeProgress = { progress, _ ->
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

            // Bottom action buttons + metadata bar, stacked above one another
            // and pinned to the bottom of the screen.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Bottom DELETE/KEEP buttons (AppFlow Screen 4). The PRD is
                // explicit these exist for users who aren't comfortable with
                // swipe gestures, so they must be a genuine equivalent, not a
                // degraded fallback: both route through the same
                // performSwipe() the gesture path uses, which animates the
                // card off-screen via OutgoingSwipeCard and fires the same
                // onSwipedLeft/onSwipedRight callback. swipeInProgress (reset
                // per-card above) guards rapid repeated taps the same way it
                // guards a tap racing a drag on the same card.
                GlassCard(cornerRadius = 40.dp) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { performSwipe(SwipeAction.DELETE, Offset.Zero) },
                            enabled = !swipeInProgress,
                            modifier = Modifier
                                .size(56.dp)
                                .background(DeleteRedOverlay, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete this item",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { performSwipe(SwipeAction.FAVORITE, Offset.Zero) },
                            enabled = !swipeInProgress,
                            modifier = Modifier
                                .size(56.dp)
                                .background(FavoriteGoldOverlay, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Favorite this item",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { performSwipe(SwipeAction.KEEP, Offset.Zero) },
                            enabled = !swipeInProgress,
                            modifier = Modifier
                                .size(56.dp)
                                .background(KeepGreenOverlay, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Keep this item",
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Metadata bar
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
        } else if (isLoading || !hasLoadedOnce) {
            // Initial load in flight -- nothing to show yet (Defect 8) -- OR
            // the very first loadMedia() hasn't even completed once yet
            // (!hasLoadedOnce), which covers the one-frame gap between this
            // screen's first composition and isLoading actually flipping
            // true. Kept separate from the mediaItems-empty branch below so
            // the two never get confused: this one is purely "no data yet",
            // the one below is "loaded, and the queue really is empty".
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = AccentPurple)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading your library…",
                    color = TextSecondary
                )
            }
        } else if (reachedEnd) {
            // Genuinely finished: pagination exhausted the library and the
            // queue is empty.
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
                    text = "You kept $sessionKeptCount and deleted $deletedCount photos",
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (hasOnlyPartialMediaAccess) {
                    Surface(
                        color = BadgeOrange.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "You've given Keepix access to a limited " +
                                "selection of photos. To see your full library, " +
                                "allow full access in system Settings.",
                            color = BadgeOrange,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (binCount > 0) {
                    com.sese.keepix.ui.components.GlassButton(
                        onClick = onNavigateToBin,
                        cornerRadius = 16.dp,
                        tintColor = AccentPurple,
                        tintAlpha = 0.2f
                    ) {
                        Text("View Bin ($binCount items)")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Carried N3: reachedEnd latches for the life of the process,
                // so it can go stale if new photos land on the device after
                // the library was exhausted. This is the user's way back in
                // without restarting the app -- it re-runs the same load path
                // as a cold start and will pick up anything new.
                TextButton(onClick = onRetry) {
                    Text("Check for new photos", color = TextSecondary)
                }
            }
        } else {
            // Carried N3: the queue is empty but pagination never reached the
            // end -- a batch fetch failed (e.g. mid-top-up) rather than the
            // library genuinely running out. Telling this apart from the
            // reachedEnd branch above matters: without it the user sees "All
            // Done!" for a library that isn't finished, with no way to
            // continue.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("⚠️", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Couldn't load your library",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Something went wrong while loading photos.",
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                com.sese.keepix.ui.components.GlassButton(
                    onClick = onRetry,
                    cornerRadius = 16.dp,
                    tintColor = AccentPurple,
                    tintAlpha = 0.2f
                ) {
                    Text("Retry")
                }
            }
        }

        // Defect 7: this must live on the root Box, as a sibling of the
        // mediaItems/isLoading/empty-state branches above, not nested inside
        // any one of them. It used to sit inside the empty-state `else`,
        // which meant it was only ever composed when the queue was empty --
        // showSnackbar(error) in the LaunchedEffect above would suspend
        // forever while cards were on screen, so a media-load error during
        // normal swiping was silently swallowed.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
    val action: SwipeAction,
    val startOffset: Offset
)

@Composable
private fun OutgoingSwipeCard(
    card: OutgoingCard,
    onFinished: () -> Unit
) {
    // The final resting offset for this action's exit direction -- horizontal
    // for KEEP/DELETE, straight up for FAVORITE.
    val finalOffset = remember(card.mediaItem.id) {
        when (card.action) {
            SwipeAction.KEEP -> Offset(1500f, 0f)
            SwipeAction.DELETE -> Offset(-1500f, 0f)
            SwipeAction.FAVORITE -> Offset(0f, -1500f)
        }
    }
    // A single 0f->1f "flight" animatable drives both axes together. Using
    // one shared progress value (rather than animating offsetX/offsetY
    // independently) guarantees the animation -- and therefore
    // finishedListener/onFinished -- always actually runs: a per-axis
    // animation would silently no-op (and never call onFinished) whenever
    // that axis's start and end value happen to coincide, e.g. X for a
    // FAVORITE flight that started from the button (startOffset = Offset.Zero)
    // where both start and target X are 0.
    var flight by remember(card.mediaItem.id) { mutableFloatStateOf(0f) }
    val animatedFlight by animateFloatAsState(
        targetValue = flight,
        animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing),
        finishedListener = {
            if (flight != 0f) {
                onFinished()
            }
        },
        label = "outgoingFlight"
    )

    LaunchedEffect(card.mediaItem.id) {
        flight = 1f
    }

    val animatedOffsetX = card.startOffset.x + (finalOffset.x - card.startOffset.x) * animatedFlight
    val animatedOffsetY = card.startOffset.y + (finalOffset.y - card.startOffset.y) * animatedFlight

    Card(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(0.75f)
            .offset { IntOffset(animatedOffsetX.roundToInt(), animatedOffsetY.roundToInt()) }
            .graphicsLayer(
                rotationZ = animatedOffsetX / 20f,
                alpha = 1f - (kotlin.math.hypot(animatedOffsetX, animatedOffsetY) / 2000f).coerceIn(0f, 0.3f)
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
    onSwiped: (action: SwipeAction, startOffset: Offset) -> Unit,
    onTap: () -> Unit,
    onSwipeProgress: (horizontal: Float, vertical: Float) -> Unit,
    autoplayVideo: Boolean = mediaItem.isVideo,
    // False once the caller has already accepted a swipe for this card (e.g.
    // via the bottom buttons) -- closes the multi-touch race where a second
    // finger completes a drag on the same card after a button tap already
    // fired performSwipe, which would otherwise fire onSwiped a second time.
    interactive: Boolean = true
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
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
    val animatedOffsetY by animateFloatAsState(
        targetValue = if (isDragging) offsetY else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "offsetY"
    )

    val displayOffsetX = if (isDragging) offsetX else animatedOffsetX
    val displayOffsetY = if (isDragging) offsetY else animatedOffsetY
    val rotation = displayOffsetX / 20f
    // Same -1f..1f drag progress reported to the parent via onSwipeProgress
    // (for the side lights / stack scale), computed locally too so the
    // KEEP/DELETE badges below can react to it directly without a round trip
    // through the caller.
    val progress = (displayOffsetX / swipeThreshold).coerceIn(-1f, 1f)
    // Upward-only progress (0f..1f) for the FAVORITE badge -- downward drag
    // reports 0f here since resolveSwipeAction never commits a downward
    // release, matching the "springs back" behavior for that direction.
    val verticalProgress = (-displayOffsetY / swipeThreshold).coerceIn(0f, 1f)
    LaunchedEffect(displayOffsetX, displayOffsetY, swipeThreshold) {
        onSwipeProgress(progress, verticalProgress)
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
                    (if (isDragging) offsetY else animatedOffsetY).roundToInt()
                )
            }
            .graphicsLayer(
                rotationZ = rotation,
                alpha = 1f - (kotlin.math.hypot(
                    if (isDragging) offsetX else animatedOffsetX,
                    if (isDragging) offsetY else animatedOffsetY
                ) / 2000f).coerceIn(0f, 0.3f)
            )
            .pointerInput(mediaItem.id, swipeHandled, interactive) {
                detectTapGestures(
                    onTap = {
                        if (!swipeHandled && interactive) {
                            onTap()
                        }
                    }
                )
            }
            .pointerInput(mediaItem, interactive) {
                detectDragGestures(
                    onDragStart = {
                        if (!swipeHandled && interactive) {
                            isDragging = true
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val action = resolveSwipeAction(offsetX, offsetY, swipeThreshold)
                        if (action != null && !swipeHandled) {
                            swipeHandled = true
                            onSwipeProgress(0f, 0f)
                            onSwiped(action, Offset(offsetX, offsetY))
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        offsetX = 0f
                        offsetY = 0f
                    }
                ) { change, dragAmount ->
                    if (!swipeHandled && interactive) {
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
            }
            .glassmorphism(cornerRadius = 24.dp, tintAlpha = 0.02f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaCardContent(
                mediaItem = mediaItem,
                autoplayVideo = autoplayVideo,
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

            // KEEP/DELETE badge overlays (PRD 5.1). Opacity tracks drag
            // distance via the same `progress` used for the side lights;
            // only one is ever visible since progress can't be both positive
            // and negative at once.
            if (progress > 0f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                        .graphicsLayer(rotationZ = -12f, alpha = progress),
                    color = KeepGreenOverlay,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "KEEP",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else if (progress < 0f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Extra top padding so this doesn't collide with the
                        // video-duration badge, which occupies the same corner.
                        .padding(top = if (mediaItem.isVideo) 64.dp else 24.dp, end = 24.dp)
                        .graphicsLayer(rotationZ = 12f, alpha = -progress),
                    color = DeleteRedOverlay,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "DELETE",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // FAVORITE badge, driven independently by upward drag progress --
            // it can appear alongside a KEEP/DELETE badge mid-drag (the two
            // axes aren't mutually exclusive while dragging, only at release,
            // via resolveSwipeAction's horizontal-wins-ties rule).
            if (verticalProgress > 0f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .graphicsLayer(alpha = verticalProgress),
                    color = FavoriteGoldOverlay,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "FAVORITE",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
