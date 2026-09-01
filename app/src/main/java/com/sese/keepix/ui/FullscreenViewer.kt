package com.sese.keepix.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
// Explicit: the material3.* wildcard also exposes an (internal) SliderRange
// .isSpecified, which otherwise wins and fails to resolve for Offset.
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.sese.keepix.ui.components.GlassCard
import com.sese.keepix.ui.theme.*
import kotlinx.coroutines.Job
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

/**
 * Where the fullscreen viewer was opened from. Drives the action bar's labels
 * and what the two buttons mean (AppFlow §Screen 5, TRD §3.3).
 *
 * This replaces the old `isBinMode: Boolean`, which could not express the third
 * ("kept grid") case: MainActivity already tracked the distinction as a
 * `"swipe"`/`"bin"`/`"kept"` string, so this enum simply gives that string a
 * type and carries the labels next to it.
 */
enum class ViewerMode(
    val routeKey: String,
    val primaryLabel: String,
    val secondaryLabel: String
) {
    /** Opened from the swipe queue: KEEP / DELETE (delete = move to bin). */
    SWIPE("swipe", "KEEP", "DELETE"),

    /** Opened from the recycle bin: RESTORE / DELETE NOW (permanent delete). */
    BIN("bin", "RESTORE", "DELETE NOW"),

    /** Opened from the kept grid: UNKEEP / DELETE (delete = move to bin). */
    KEPT("kept", "UNKEEP", "DELETE");

    companion object {
        fun fromRouteKey(key: String?): ViewerMode =
            entries.firstOrNull { it.routeKey.equals(key, ignoreCase = true) } ?: SWIPE
    }
}

/** Zoom limits (PRD §5.3 / TRD §3.3). */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/** Controls fade after this long without interaction (AppFlow §Screen 5). */
private const val CONTROLS_FADE_DELAY_MS = 3_000L

/** How long a single tap waits to see whether it is really a double tap. */
private const val DOUBLE_TAP_WINDOW_MS = 250L

/** Video position poll interval while playing (TRD §3.3). */
private const val VIDEO_POLL_INTERVAL_MS = 200L

/**
 * How long a horizontal drag on a zoomable page is withheld from the pager, to
 * cover the skew between the two fingers of a pinch landing.
 */
private const val PAGER_HANDOFF_GRACE_MS = 40L

/**
 * Vertical room reserved at the bottom of a media page for the viewer's own
 * chrome, so the video control pill stacks above it instead of underneath it.
 *
 * Two values because the gallery carries a page indicator above the action bar
 * and the single-item path does not -- one shared constant made the pill float
 * needlessly high on the single-item screen. Still hand-tuned rather than
 * measured; see the report's concerns.
 */
private val VIDEO_CONTROLS_INSET_SINGLE = 132.dp
private val VIDEO_CONTROLS_INSET_GALLERY = 176.dp

@Composable
fun FullscreenViewer(
    mediaUri: Uri,
    isVideo: Boolean,
    mode: ViewerMode = ViewerMode.SWIPE,
    transitionBounds: MediaTransitionBounds? = null,
    onKeepOrRestore: (Uri) -> Unit,
    onDeleteOrDeleteNow: (Uri) -> Unit,
    onDismiss: () -> Unit,
    tutorialComplete: Boolean = true,
    onTutorialDismiss: () -> Unit = {},
    // Gallery mode params
    galleryItems: List<GalleryItem> = emptyList(),
    initialIndex: Int = 0
) {
    val isGalleryMode = galleryItems.size > 1

    var opened by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    // Bumped by any control interaction so the auto-fade timer restarts.
    var interactionTick by remember { mutableIntStateOf(0) }

    // The page the pager has actually settled on / is closest to. Sourced from
    // `pagerState.currentPage` (not the static `initialIndex` prop) so the page
    // indicator and the action bar's target both track what is on screen.
    var currentPage by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (galleryItems.size - 1).coerceAtLeast(0)))
    }

    // True while the visible page is zoomed in. Locks out the pager's own
    // horizontal scrolling so a pan can never turn into a page change.
    var pageZoomed by remember { mutableStateOf(false) }

    // Dismiss swipe state
    var dismissOffsetY by remember { mutableFloatStateOf(0f) }
    var isDismissDragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current

    // Immersive mode: hidden on enter, restored on *every* exit path because the
    // restore hangs off onDispose rather than any particular callback.
    ImmersiveModeEffect()

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

    // Auto-fade for the *transient* chrome only — the top bar and the video
    // control pill. AppFlow §Screen 5's layout annotates only the top bar with
    // "fades out after 2s of inactivity"; the bottom action bar carries no such
    // annotation, and TRD §3.3 scopes its 3s fade to the video controls. The
    // action bar therefore stays put (see `actionsVisible` below) — it is the
    // only route to RESTORE, so hiding it would bury a core feature.
    //
    // `entryComplete` is a key so the timer starts when the chrome actually
    // becomes visible rather than at first composition, which was costing the
    // top bar the 260ms of the entry transition.
    val entryComplete = transitionProgress >= 1f
    LaunchedEffect(showControls, interactionTick, closing, entryComplete) {
        if (showControls && !closing && entryComplete) {
            delay(CONTROLS_FADE_DELAY_MS)
            showControls = false
        }
    }

    // The item the action bar acts on. In gallery mode that is whatever page is
    // showing, *not* the item that was originally tapped — without this, paging
    // to a different bin item and hitting RESTORE would restore the wrong row.
    val currentItem: GalleryItem = remember(galleryItems, currentPage, mediaUri, isVideo) {
        galleryItems.getOrNull(currentPage) ?: GalleryItem(mediaUri, isVideo)
    }

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
                    androidx.compose.ui.unit.IntOffset(
                        animatedLeft.roundToInt(),
                        (animatedTop + displayDismissY).roundToInt()
                    )
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
                    controlsVisible = showControls && !closing,
                    onIndexChanged = { currentPage = it },
                    zoomLocked = pageZoomed,
                    onZoomLockChanged = { pageZoomed = it },
                    onDismissDrag = { dy ->
                        // Only the upward drag dismisses (matches the tutorial
                        // overlay and the single-item viewer).
                        isDismissDragging = true
                        dismissOffsetY = dy.coerceAtMost(0f)
                    },
                    onDismissEnd = {
                        isDismissDragging = false
                        if (dismissOffsetY < -150f) {
                            startDismiss()
                        }
                        dismissOffsetY = 0f
                    },
                    onTap = { showControls = !showControls },
                    onInteraction = { interactionTick++ }
                )
            } else {
                // Single item mode (swipe screen tap) — original behavior
                SingleItemViewer(
                    mediaUri = mediaUri,
                    isVideo = isVideo,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    transitionProgress = transitionProgress,
                    isFlyingOff = remember { mutableStateOf(false) },
                    controlsVisible = showControls && !closing,
                    onShowControlsToggle = { showControls = !showControls },
                    onZoomChanged = { pageZoomed = it },
                    onKeepOrRestore = { onKeepOrRestore(mediaUri) },
                    onDeleteOrDeleteNow = { onDeleteOrDeleteNow(mediaUri) },
                    onDismiss = { startDismiss() },
                    onSetClosing = { closing = true; showControls = false },
                    onInteraction = { interactionTick++ }
                )
            }
        }

        // Bottom chrome: page indicator (gallery only) stacked above the action
        // bar. Rendered here rather than inside GalleryPager/SingleItemViewer so
        // it sits outside the media container's dismiss/fly-off transform and
        // stays put while the media itself animates away.
        //
        // Deliberately NOT gated on showControls — see the auto-fade effect
        // above. The indicator rides along with the bar so the cluster's height
        // never changes underneath it.
        if (entryComplete && !closing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .graphicsLayer(alpha = controlsAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isGalleryMode) {
                    GlassCard(cornerRadius = 20.dp) {
                        Text(
                            text = "${currentPage + 1} / ${galleryItems.size}",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                ViewerActionBar(
                    mode = mode,
                    onPrimary = {
                        interactionTick++
                        onKeepOrRestore(currentItem.uri)
                    },
                    onSecondary = {
                        interactionTick++
                        onDeleteOrDeleteNow(currentItem.uri)
                    }
                )
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
        GestureTutorialOverlay(
            mode = mode,
            isGalleryMode = isGalleryMode,
            onDismiss = onTutorialDismiss
        )
    }
}

/**
 * Hides the status and navigation bars while the viewer is composed and puts
 * them back when it leaves (TRD §3.3, AppFlow §Screen 5 "System UI").
 *
 * The restore lives in `onDispose`, so it runs on every exit path — the action
 * bar's buttons, the back arrow, the swipe-up dismiss, a system back press, and
 * a config change that recreates the Activity. Process death needs no handling:
 * the new process starts with the system bars at their defaults.
 */
@Composable
private fun ImmersiveModeEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findHostActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { systemBarsBehavior = it }
            }
        }
    }
}

/** Unwraps a possibly-wrapped [Context] to the [Activity] hosting it. */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

// ---------------------------------------------------------------------------
// Action bar
// ---------------------------------------------------------------------------

/**
 * The glassmorphism action pill at the bottom of the viewer (AppFlow §Screen 5,
 * TRD §3.3). Shared by both the pager and the single-item viewer — this is what
 * makes RESTORE reachable from a bin holding two or more items, which the
 * pager-only path previously made impossible.
 */
@Composable
private fun ViewerActionBar(
    mode: ViewerMode,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryIcon: ImageVector = when (mode) {
        ViewerMode.SWIPE -> Icons.Default.Check
        // RESTORE and UNKEEP both put the item back into the review queue.
        ViewerMode.BIN, ViewerMode.KEPT -> Icons.Default.Refresh
    }
    val secondaryIcon: ImageVector = when (mode) {
        ViewerMode.BIN -> Icons.Default.Delete
        ViewerMode.SWIPE, ViewerMode.KEPT -> Icons.Default.Close
    }

    GlassCard(modifier = modifier, cornerRadius = 36.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewerAction(
                icon = secondaryIcon,
                label = mode.secondaryLabel,
                tint = DeleteRedOverlay,
                onClick = onSecondary
            )
            ViewerAction(
                icon = primaryIcon,
                label = mode.primaryLabel,
                tint = KeepGreenOverlay,
                onClick = onPrimary
            )
        }
    }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(tint, CircleShape)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------
// Zoom
// ---------------------------------------------------------------------------

/**
 * Pinch/pan state for one photo (PRD §5.3).
 *
 * Pan is clamped to the *rendered image's* edges, not the viewport's: with
 * `ContentScale.Fit` a portrait photo on a landscape screen has letterbox bars,
 * and clamping to the viewport would let the user drag empty space into view.
 *
 * The aspect ratio arrives late, from Coil's decoded drawable. Until then
 * [fittedSize] falls back to the viewport, which *over*-estimates the pannable
 * area for a letterboxed photo — an optimistic stand-in, not a conservative
 * one, so a pan or double-tap in that window can briefly reach into the
 * letterbox bars. [updateContentAspect] re-clamps the moment the real ratio lands.
 */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(MIN_SCALE)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Viewport size in px. */
    var containerSize by mutableStateOf(Size.Zero)

    /** Intrinsic width/height of the decoded image; 0 until known. */
    var contentAspect by mutableFloatStateOf(0f)
        private set

    /**
     * Records the decoded image's real aspect ratio and immediately re-clamps
     * the current offset against it. Without the re-clamp, a pan or double-tap
     * performed before Coil finished decoding would leave an offset that the
     * (now stricter) bounds disallow, and it would stay wrong until the user's
     * next pan happened to re-clamp it.
     */
    fun updateContentAspect(aspect: Float) {
        if (aspect <= 0f || aspect == contentAspect) return
        contentAspect = aspect
        offset = clamp(offset, scale)
    }

    /**
     * Deliberately a hair above 1f: float drift from a pinch that ends near 1x
     * must not leave the pager locked out forever.
     */
    val isZoomed: Boolean get() = scale > MIN_SCALE + 0.01f

    private val center: Offset
        get() = Offset(containerSize.width / 2f, containerSize.height / 2f)

    /** Size the image actually occupies inside the viewport at scale 1. */
    private fun fittedSize(): Size {
        val c = containerSize
        if (c.width <= 0f || c.height <= 0f) return Size.Zero
        val ar = contentAspect
        if (ar <= 0f) return c
        return if (c.width / c.height > ar) {
            Size(c.height * ar, c.height)
        } else {
            Size(c.width, c.width / ar)
        }
    }

    private fun clamp(raw: Offset, atScale: Float): Offset {
        val fitted = fittedSize()
        if (fitted.width <= 0f || fitted.height <= 0f) return Offset.Zero
        val maxX = ((fitted.width * atScale - containerSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * atScale - containerSize.height) / 2f).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    /**
     * Applies one pinch frame, keeping the content point under the fingers
     * under the fingers.
     *
     * [centroid] MUST be the *previous* frame's centroid
     * (`calculateCentroid(useCurrent = false)`), because [pan] is already
     * `currentCentroid - previousCentroid`. Anchoring on the current centroid
     * *and* adding pan double-counts the movement by `pan * (1 - new/old)` per
     * frame — sub-pixel at a steady zoom, but visible drift on a fast pinch.
     *
     * Derivation: the content point under the previous centroid is
     * `p = (focus - offset) / old`; requiring it to land under the current
     * centroid gives `offset' = focus + pan - (focus - offset) * (new / old)`.
     */
    fun transform(zoomFactor: Float, centroid: Offset, pan: Offset) {
        if (!centroid.isSpecified || !pan.isSpecified) return
        val old = scale
        val new = (old * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val focus = centroid - center
        val next = focus + pan - (focus - offset) * (new / old)
        scale = new
        offset = if (new <= MIN_SCALE) Offset.Zero else clamp(next, new)
    }

    /** Single-finger pan while zoomed. Stops dead at the image edges. */
    fun panBy(delta: Offset) {
        if (!isZoomed) return
        offset = clamp(offset + delta, scale)
    }

    /** Double-tap toggle between 1x and 2.5x, anchored on the tap point. */
    fun toggleDoubleTap(position: Offset) {
        if (isZoomed) {
            reset()
            return
        }
        val old = scale
        val focus = position - center
        val next = focus - (focus - offset) * (DOUBLE_TAP_SCALE / old)
        scale = DOUBLE_TAP_SCALE
        offset = clamp(next, DOUBLE_TAP_SCALE)
    }

    fun reset() {
        scale = MIN_SCALE
        offset = Offset.Zero
    }
}

// ---------------------------------------------------------------------------
// Gallery pager
// ---------------------------------------------------------------------------

@Composable
private fun GalleryPager(
    items: List<GalleryItem>,
    initialIndex: Int,
    controlsVisible: Boolean,
    onIndexChanged: (Int) -> Unit,
    zoomLocked: Boolean,
    onZoomLockChanged: (Boolean) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    onTap: () -> Unit,
    onInteraction: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size }
    )

    // Report page changes
    LaunchedEffect(pagerState.currentPage) {
        onIndexChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        // Belt-and-braces with the per-page gesture handler below: while the
        // visible page is zoomed the pager must not scroll at all, so a pan
        // that reaches the image edge stops there instead of rubber-banding
        // into a page change.
        userScrollEnabled = !zoomLocked
    ) { page ->
        val isActive = page == pagerState.currentPage
        ViewerMediaContent(
            item = items[page],
            isActive = isActive,
            controlsVisible = controlsVisible,
            // The pager owns horizontal drags at scale 1; leaving them
            // unconsumed here is what lets it page.
            allowHorizontalDrag = false,
            onZoomChanged = { zoomed -> if (isActive) onZoomLockChanged(zoomed) },
            onDrag = { _, dy -> onDismissDrag(dy) },
            onDragEnd = { _, _ -> onDismissEnd() },
            onTap = onTap,
            onInteraction = onInteraction,
            videoControlsBottomPadding = VIDEO_CONTROLS_INSET_GALLERY,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------------------------------------------------------------------
// Single item viewer
// ---------------------------------------------------------------------------

@Composable
private fun SingleItemViewer(
    mediaUri: Uri,
    isVideo: Boolean,
    screenWidthPx: Float,
    screenHeightPx: Float,
    transitionProgress: Float,
    isFlyingOff: MutableState<Boolean>,
    controlsVisible: Boolean,
    onShowControlsToggle: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onKeepOrRestore: () -> Unit,
    onDeleteOrDeleteNow: () -> Unit,
    onDismiss: () -> Unit,
    onSetClosing: () -> Unit,
    onInteraction: () -> Unit
) {
    val swipeThresholdRatio = 0.3f
    val flyOffDistance = 2000f
    val swipeThreshold = screenWidthPx * swipeThresholdRatio

    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    var swipeOffsetY by remember { mutableFloatStateOf(0f) }

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

    ViewerMediaContent(
        item = GalleryItem(mediaUri, isVideo),
        isActive = true,
        controlsVisible = controlsVisible,
        // No pager here, so this viewer keeps its own left/right
        // keep/delete swipes.
        allowHorizontalDrag = true,
        onZoomChanged = onZoomChanged,
        onDrag = { dx, dy ->
            if (!isFlyingOff.value && transitionProgress >= 1f) {
                swipeOffsetX = dx
                swipeOffsetY = dy
            }
        },
        onDragEnd = { _, _ ->
            if (!isFlyingOff.value) {
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
        },
        onTap = onShowControlsToggle,
        onInteraction = onInteraction,
        videoControlsBottomPadding = VIDEO_CONTROLS_INSET_SINGLE,
        modifier = Modifier.fillMaxSize(),
        mediaTransform = {
            translationX = swipeOffsetX + animatedFlyOffX
            translationY = swipeOffsetY + animatedFlyOffY
            if (isFlyingOff.value && flyOffOffsetY < 0f) {
                alpha = 1f - (abs(animatedFlyOffY) / screenHeightPx).coerceIn(0f, 1f)
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Media page: zoom + pan + drag arbitration + tap
// ---------------------------------------------------------------------------

/**
 * One page of media with the whole gesture stack on it.
 *
 * Everything is arbitrated inside a single [awaitEachGesture] rather than
 * stacked `pointerInput` blocks, because the four gesture systems in this file
 * all want the same drags and only an explicit priority order keeps them apart:
 *
 *  1. **Two or more pointers** -> pinch zoom. Always wins, always consumes.
 *  2. **One pointer while zoomed** -> pan. Consumes, so neither the pager nor
 *     the dismiss handler ever sees it; the pan clamps at the image edge and
 *     simply stops.
 *  3. **One pointer at 1x, mostly vertical** -> reported as a drag (dismiss in
 *     the pager, keep/delete/dismiss in the single-item viewer). Consumes.
 *  4. **One pointer at 1x, mostly horizontal** -> consumed only when
 *     [allowHorizontalDrag] is set (single-item viewer). In the pager it is
 *     left *unconsumed* so the enclosing `HorizontalPager` picks it up and
 *     changes page.
 *
 * Videos are not zoomable (a `VideoView` is a real Android view; scaling it is
 * not what `ContentScale.Fit` on an image does), so rule 1 and 2 are skipped
 * for them and their drags fall straight through to rules 3 and 4.
 */
@Composable
private fun ViewerMediaContent(
    item: GalleryItem,
    isActive: Boolean,
    controlsVisible: Boolean,
    allowHorizontalDrag: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: (dx: Float, dy: Float) -> Unit,
    onTap: () -> Unit,
    onInteraction: () -> Unit,
    videoControlsBottomPadding: Dp,
    modifier: Modifier = Modifier,
    /**
     * Applied to the media surface ONLY, never to the video control pill.
     * SingleItemViewer passes its keep/delete fly-off through here: wrapping
     * this whole composable in that graphicsLayer instead would slide the
     * scrubber off-screen along with the photo.
     */
    mediaTransform: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null
) {
    val zoomable = !item.isVideo
    val zoom = remember(item.uri) { ZoomState() }
    val video = remember(item.uri) { VideoPlayerState() }
    val scope = rememberCoroutineScope()
    var singleTapJob by remember { mutableStateOf<Job?>(null) }

    // The gesture block below is keyed on things that almost never change, so
    // it can outlive several recompositions. Reading the callbacks through
    // rememberUpdatedState guarantees it always calls the *current* ones —
    // without this, a handler captured during the entry animation would keep
    // seeing that frame's `transitionProgress` forever and drags would be
    // silently dropped.
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnTap by rememberUpdatedState(onTap)

    // Paging away resets zoom — the conventional behaviour, and it also
    // guarantees the pager can never be left scroll-locked by a page the user
    // has moved off of.
    LaunchedEffect(isActive) {
        if (!isActive) zoom.reset()
    }

    // derivedStateOf, not a bare `zoom.isZoomed` read: reading the scale during
    // composition would recompose this page on every single pinch frame, where
    // the graphicsLayer lambda below deliberately defers that read to the draw
    // phase. Only the boolean flipping needs to reach composition.
    val isZoomed by remember(zoom) { derivedStateOf { zoom.isZoomed } }

    // Publish zoom state upward so the pager can lock its own scrolling.
    LaunchedEffect(isZoomed, isActive) {
        onZoomChanged(isZoomed && isActive)
    }

    /**
     * A completed tap. Single tap toggles the controls; a second tap inside
     * [DOUBLE_TAP_WINDOW_MS] cancels that and zooms instead. The delay is why
     * a double tap does not first flash the controls on and off.
     */
    /** Drops a queued single tap once the gesture turns out to be a drag/pinch. */
    fun cancelPendingSingleTap() {
        singleTapJob?.cancel()
        singleTapJob = null
    }

    fun onTapCompleted(position: Offset) {
        if (!zoomable) {
            // No double-tap zoom on video, so no reason to make the tap wait.
            latestOnTap()
            return
        }
        val pending = singleTapJob
        if (pending != null && pending.isActive) {
            pending.cancel()
            singleTapJob = null
            zoom.toggleDoubleTap(position)
        } else {
            singleTapJob = scope.launch {
                delay(DOUBLE_TAP_WINDOW_MS)
                singleTapJob = null
                latestOnTap()
            }
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (mediaTransform != null) Modifier.graphicsLayer(mediaTransform)
                    else Modifier
                )
                .onSizeChanged {
                    zoom.containerSize = Size(it.width.toFloat(), it.height.toFloat())
                }
                .pointerInput(zoomable, allowHorizontalDrag) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val touchSlop = viewConfiguration.touchSlop

                        var multiTouch = false
                        var decided = false
                        var panning = false
                        var draggingVertical = false
                        var draggingHorizontal = false
                        var moved = false
                        var reportedDrag = false
                        var totalX = 0f
                        var totalY = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // (1) Pinch zoom.
                            if (zoomable && pressed.size >= 2) {
                                if (reportedDrag) {
                                    // A drag that turned into a pinch. Zero the
                                    // drag out *before* ending it: a plain
                                    // onDragEnd here would be indistinguishable
                                    // from a finger-up, so a drag that had
                                    // already crossed the swipe threshold would
                                    // commit a KEEP/DELETE the moment a second
                                    // finger landed. Reporting (0, 0) first
                                    // makes the end read as "below threshold",
                                    // i.e. a cancel that springs home.
                                    totalX = 0f
                                    totalY = 0f
                                    latestOnDrag(0f, 0f)
                                    latestOnDragEnd(0f, 0f)
                                    reportedDrag = false
                                }
                                multiTouch = true
                                if (!moved) {
                                    moved = true
                                    cancelPendingSingleTap()
                                }
                                zoom.transform(
                                    zoomFactor = event.calculateZoom(),
                                    // useCurrent = false: pan is already the
                                    // centroid delta, so the anchor must be
                                    // the PREVIOUS centroid (see transform).
                                    centroid = event.calculateCentroid(useCurrent = false),
                                    pan = event.calculatePan()
                                )
                                pressed.forEach(PointerInputChange::consume)
                                continue
                            }

                            // (2) One finger left over from a pinch: keep
                            // panning rather than reinterpreting it as a fresh
                            // swipe. positionChange() is a per-pointer delta, so
                            // it does not matter WHICH finger survived.
                            //
                            // Consumes unconditionally, including when the pinch
                            // ended back at 1x: releasing the leftover finger to
                            // the pager mid-gesture would page the gallery off
                            // the back of a pinch, which is not what the latch
                            // is supposed to mean.
                            if (multiTouch) {
                                if (zoom.isZoomed) {
                                    zoom.panBy(pressed[0].positionChange())
                                }
                                pressed.forEach(PointerInputChange::consume)
                                continue
                            }

                            // totalX/totalY are measured from `down`, so they are
                            // only meaningful for that same pointer. pressed[0]
                            // is NOT guaranteed to be it: on a video (never
                            // zoomable, so the pinch branch above never latches)
                            // putting two fingers down and lifting the first
                            // would slide pressed[0] onto the second pointer and
                            // produce a large spurious delta -- enough to clear
                            // swipeThreshold and fire an unintended KEEP/DELETE.
                            val change = pressed.firstOrNull { it.id == down.id } ?: break

                            totalX = change.position.x - down.position.x
                            totalY = change.position.y - down.position.y

                            if (!decided &&
                                (abs(totalX) > touchSlop || abs(totalY) > touchSlop)
                            ) {
                                decided = true
                                moved = true
                                // The gesture is a drag, not a tap: drop the
                                // single-tap that would otherwise toggle the
                                // controls 250ms into the drag.
                                cancelPendingSingleTap()
                                when {
                                    zoomable && zoom.isZoomed -> panning = true
                                    abs(totalY) > abs(totalX) -> draggingVertical = true
                                    else -> draggingHorizontal = true
                                }
                            }

                            if (decided) {
                                when {
                                    // (2) Pan while zoomed.
                                    panning -> {
                                        zoom.panBy(change.positionChange())
                                        change.consume()
                                    }
                                    // (3) Vertical drag.
                                    draggingVertical -> {
                                        reportedDrag = true
                                        latestOnDrag(0f, totalY)
                                        change.consume()
                                    }
                                    // (4) Horizontal drag, ours to handle.
                                    draggingHorizontal && allowHorizontalDrag -> {
                                        reportedDrag = true
                                        latestOnDrag(totalX, 0f)
                                        change.consume()
                                    }
                                    // (4) Horizontal drag the pager wants --
                                    // but withhold it briefly on a zoomable
                                    // page. If one finger crosses slop just
                                    // before the second lands, releasing it
                                    // immediately lets HorizontalPager claim
                                    // and settle the drag; the pinch then
                                    // starts on a page that is already moving,
                                    // and the settle can land on the next page,
                                    // whose activation resets the zoom the user
                                    // was starting. Holding it for the width of
                                    // a two-finger touch-down closes that
                                    // window; paging is delayed by a frame or
                                    // two at most, and not at all for video.
                                    draggingHorizontal && zoomable &&
                                        change.uptimeMillis - down.uptimeMillis <
                                        PAGER_HANDOFF_GRACE_MS -> {
                                        change.consume()
                                    }
                                    // (4) Otherwise leave it unconsumed so
                                    // HorizontalPager takes it.
                                }
                            }
                        }

                        if (reportedDrag) {
                            latestOnDragEnd(totalX, totalY)
                        } else if (!moved) {
                            onTapCompleted(down.position)
                        }
                    }
                }
        ) {
            if (item.isVideo) {
                VideoSurface(
                    uri = item.uri,
                    state = video,
                    isActive = isActive,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = "Fullscreen media",
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val d = state.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            zoom.updateContentAspect(
                                d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y
                        }
                )
            }
        }

        // Video control pill. A sibling of (and drawn above) the gesture Box, so
        // the scrubber's own drags reach the Slider instead of being read as a
        // page change — and so the rest of the page still taps through to the
        // gesture handler.
        if (item.isVideo && controlsVisible) {
            VideoControlsOverlay(
                state = video,
                onInteraction = onInteraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = videoControlsBottomPadding)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Video
// ---------------------------------------------------------------------------

/**
 * Playback state for one [VideoView], shared between the surface that owns the
 * view and the control pill that drives it.
 */
@Stable
private class VideoPlayerState {
    var videoView by mutableStateOf<VideoView?>(null)
    var player by mutableStateOf<MediaPlayer?>(null)
    var isPlaying by mutableStateOf(false)
    var isMuted by mutableStateOf(false)
    var durationMs by mutableIntStateOf(0)
    var positionMs by mutableIntStateOf(0)
    var isScrubbing by mutableStateOf(false)
    var scrubMs by mutableFloatStateOf(0f)

    /**
     * What the user last asked for. Kept separate from [isPlaying] so that
     * paging away (which pauses) and paging back (which resumes) does not
     * override an explicit pause.
     */
    var playWhenActive by mutableStateOf(true)

    /**
     * Whether this page is the one on screen.
     *
     * Written from `AndroidView`'s update block, which runs during
     * applyChanges — i.e. *before* `MediaPlayer` can possibly finish preparing.
     * That timing is the whole point: `onPrepared` fires hundreds of ms after
     * the factory, long after the pause `LaunchedEffect` has already run and
     * found nothing to pause (an unprepared VideoView reports `isPlaying ==
     * false`). Without this flag to read at prepare time, an off-screen
     * neighbour composed by `beyondViewportPageCount` would start itself,
     * unmuted, and nothing would ever correct it because no effect key changes.
     */
    var isActivePage by mutableStateOf(false)

    fun applyVolume() {
        val v = if (isMuted) 0f else 1f
        player?.setVolume(v, v)
    }

    fun togglePlayPause() {
        val vv = videoView ?: return
        if (vv.isPlaying) {
            vv.pause()
            isPlaying = false
            playWhenActive = false
        } else {
            vv.start()
            isPlaying = true
            playWhenActive = true
        }
    }
}

@Composable
private fun VideoSurface(
    uri: Uri,
    state: VideoPlayerState,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Keyed on Unit, not uri, deliberately. AndroidView's factory runs once per
    // node, so `uri` is baked into this VideoView for its whole life — and it
    // never has to change: HorizontalPager keys pages by index over a list that
    // is fixed for the viewer's lifetime, and the single-item path takes its URI
    // from an immutable nav argument. Keying on `uri` would suggest a re-key
    // path that cannot happen and whose disposal could race the next factory
    // call into nulling out the *new* view's reference.
    DisposableEffect(Unit) {
        onDispose {
            state.videoView?.stopPlayback()
            state.videoView = null
            state.player = null
            state.isPlaying = false
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
                    state.player = player
                    state.durationMs = duration.coerceAtLeast(0)
                    // Fullscreen playback is audible by default — unlike the
                    // swipe card's muted autoplay. Which is exactly why the
                    // isActivePage guard below matters: an unguarded start()
                    // here plays a neighbouring page's audio over the visible
                    // one.
                    state.applyVolume()
                    if (state.playWhenActive && state.isActivePage) {
                        start()
                        state.isPlaying = true
                    }
                }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START &&
                        state.isActivePage
                    ) {
                        state.isPlaying = this@apply.isPlaying
                    }
                    true
                }
                state.videoView = this
            }
        },
        // Runs during applyChanges, so isActivePage is correct well before
        // onPrepared can fire, and is re-run whenever isActive changes.
        update = { state.isActivePage = isActive }
    )

    // Only the visible page plays. `beyondViewportPageCount = 1` composes the
    // neighbours, so without this every adjacent video would autoplay at once.
    LaunchedEffect(isActive, state.videoView) {
        val vv = state.videoView ?: return@LaunchedEffect
        state.isActivePage = isActive
        if (!isActive) {
            // Unconditional, not `if (vv.isPlaying)`. VideoView.pause() sets
            // mTargetState = STATE_PAUSED even when it is not yet in a playback
            // state, so this also stops a not-yet-prepared player from
            // auto-starting on prepare — defence in depth behind the
            // isActivePage guard above.
            vv.pause()
            state.isPlaying = false
        } else if (state.playWhenActive) {
            vv.start()
            state.isPlaying = true
        }
    }

    // Position polling (TRD §3.3). It is a LaunchedEffect, so it is cancelled
    // when the page leaves composition or playback stops — it cannot outlive
    // the viewer or keep ticking against a released VideoView. Gated on
    // isActivePage as well so an off-screen page neither plays nor polls.
    LaunchedEffect(state.videoView, state.isPlaying, state.isScrubbing, state.isActivePage) {
        val vv = state.videoView ?: return@LaunchedEffect
        while (state.isPlaying && !state.isScrubbing && state.isActivePage) {
            state.positionMs = vv.currentPosition.coerceAtLeast(0)
            if (state.durationMs <= 0) {
                state.durationMs = vv.duration.coerceAtLeast(0)
            }
            delay(VIDEO_POLL_INTERVAL_MS)
        }
    }
}

/**
 * Play/pause, a draggable scrubber that seeks, mute, and elapsed/duration text
 * (AppFlow §Screen 5 "Video behavior", TRD §3.3).
 */
@Composable
private fun VideoControlsOverlay(
    state: VideoPlayerState,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationF = state.durationMs.coerceAtLeast(1).toFloat()
    val sliderValue = if (state.isScrubbing) state.scrubMs else state.positionMs.toFloat()

    GlassCard(modifier = modifier, cornerRadius = 28.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = {
                    onInteraction()
                    state.togglePlayPause()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = if (state.isPlaying) "⏸" else "▶",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }

            Text(
                text = formatPlaybackTime(sliderValue.toInt()),
                color = TextSecondary,
                fontSize = 12.sp
            )

            Slider(
                value = sliderValue.coerceIn(0f, durationF),
                valueRange = 0f..durationF,
                onValueChange = { v ->
                    onInteraction()
                    state.isScrubbing = true
                    state.scrubMs = v
                },
                onValueChangeFinished = {
                    val target = state.scrubMs.toInt().coerceIn(0, state.durationMs)
                    state.videoView?.seekTo(target)
                    state.positionMs = target
                    state.isScrubbing = false
                    onInteraction()
                },
                enabled = state.durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = AccentPurple,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            )

            Text(
                text = formatPlaybackTime(state.durationMs),
                color = TextSecondary,
                fontSize = 12.sp
            )

            IconButton(
                onClick = {
                    onInteraction()
                    state.isMuted = !state.isMuted
                    state.applyVolume()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = if (state.isMuted) "🔇" else "🔊",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}

private fun formatPlaybackTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${if (seconds < 10) "0$seconds" else "$seconds"}"
}

// ---------------------------------------------------------------------------
// Tutorial overlay
// ---------------------------------------------------------------------------

@Composable
private fun GestureTutorialOverlay(
    mode: ViewerMode,
    isGalleryMode: Boolean,
    onDismiss: () -> Unit
) {
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
                    text = "Gestures",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isGalleryMode) {
                    // Left/right pages through the gallery here, so advertising
                    // them as keep/delete would be a lie — the action bar is
                    // what performs those.
                    GestureHint("↔", "Swipe sideways", "Browse", TextSecondary)
                } else {
                    GestureHint("←", "Swipe left", mode.secondaryLabel, DeleteRed)
                    GestureHint("→", "Swipe right", mode.primaryLabel, KeepGreen)
                }
                GestureHint("↑", "Swipe up", "Go back", TextSecondary)
                GestureHint("⤢", "Pinch / double tap", "Zoom photo", AccentPurple)

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
