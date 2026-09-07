package com.sese.keepix.ui.neu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sese.keepix.core.logic.DeckAction
import com.sese.keepix.core.logic.DragAxis
import com.sese.keepix.core.logic.dragAxis
import com.sese.keepix.core.logic.UndoWindow
import com.sese.keepix.core.logic.cardRotationDegrees
import com.sese.keepix.core.logic.resolveDeckAction
import com.sese.keepix.data.MediaItem
import com.sese.keepix.utils.formatSizeShort
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The swipe deck, restyled. Geometry and motion come from the prototypes in
 * docs/design/, which disagree with the prose handoff in three places worth
 * knowing about:
 *
 * - The drag is **1:1**. The addendum says it is damped ("the prototype scales
 *   dx before applying"); the prototype's own onMove is
 *   `dx = clientX - startX` with no scaling.
 * - Card exit is `.3s ease-out`, not the 300ms fast-out-linear-in the handoff's
 *   motion table lists.
 * - The shrink overlay has a 24dp dead zone before it starts fading in, which
 *   no prose doc mentions. Without it the overlay flickers on during a
 *   horizontal drag that wobbles slightly downward.
 */
private val CardExitEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f) // CSS ease-out
private const val CARD_EXIT_MS = 300

/** Commit thresholds. Down asks for more travel: shrink is the only action that rewrites bytes. */
private val HorizontalCommit = 92.dp
private val VerticalCommit = 110.dp

/**
 * Travel before the drag locks to an axis. Under this the card does not move at
 * all, so resting a thumb on it and shifting slightly leaves it where it is.
 */
private val AxisLockSlop = 12.dp

/** A card mid-flight. Rendered above the stack so the incoming card is never animated. */
private data class ExitingCard(val item: MediaItem, val action: DeckAction, val from: Offset)

/**
 * An action the user has taken but that has not been written yet.
 *
 * The undo window is the commit deadline, not a cosmetic affordance: nothing
 * reaches MediaStore or WorkManager until it closes. One level, no stack -- a
 * second action flushes the first immediately rather than queueing it.
 */
private data class PendingCommit(
    val item: MediaItem,
    val action: DeckAction,
    val startedAtMs: Long,
)

/** Where a committed card flies to, and where a rewound one springs back from. */
private fun exitOffset(action: DeckAction, density: Density): Offset =
    with(density) {
        when (action) {
            DeckAction.KEEP -> Offset(540.dp.toPx(), 60.dp.toPx())
            DeckAction.BIN -> Offset((-540).dp.toPx(), 60.dp.toPx())
            DeckAction.SHRINK -> Offset(0f, 600.dp.toPx())
        }
    }

@Composable
fun NeuDeckScreen(
    items: List<MediaItem>,
    onCommit: (MediaItem, DeckAction) -> Unit,
    modifier: Modifier = Modifier,
    onTapCard: (MediaItem) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onToggleFavorite: (MediaItem) -> Unit = {},
    isFavorite: (MediaItem) -> Boolean = { false },
    streakDays: Int = 0,
    queuedCount: Int = 0,
    // Same names and defaults as the screen this replaces, so swapping the nav
    // over is a rename rather than a rewiring.
    isLoading: Boolean = false,
    hasLoadedOnce: Boolean = true,
    reachedEnd: Boolean = true,
    error: String? = null,
    onErrorDismiss: () -> Unit = {},
    onRetry: () -> Unit = {},
    hasOnlyPartialMediaAccess: Boolean = false,
) {
    val c = neu
    var pending by remember { mutableStateOf<PendingCommit?>(null) }
    var rewind by remember { mutableStateOf<DeckAction?>(null) }

    // The card leaves the deck the moment it is swiped; only the write waits.
    val visible = items.filterNot { it.id == pending?.item?.id }

    fun request(item: MediaItem, action: DeckAction) {
        // One level, no stack: taking a second action commits the first now.
        pending?.let { onCommit(it.item, it.action) }
        rewind = null
        pending = PendingCommit(item, action, System.currentTimeMillis())
    }

    // The deadline. Deliberately a delay against the wall clock rather than the
    // ring's animation: with ANIMATOR_DURATION_SCALE at 0 an animation finishes
    // instantly, and driving the deadline from it would silently destroy the
    // undo window for exactly the users who set that.
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(UndoWindow.remainingMs(p.startedAtMs, System.currentTimeMillis()))
        onCommit(p.item, p.action)
        pending = null
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface)
            .systemBarsPadding(),
    ) {
        DeckHeader(
            streakDays = streakDays,
            queuedCount = queuedCount,
            pending = pending,
            onUndo = {
                rewind = pending?.action
                pending = null
            },
            onOpenLibrary = onOpenLibrary,
        )

        if (error != null) ErrorStrip(error, onErrorDismiss)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                visible.isNotEmpty() -> CardStack(
                    items = visible,
                    onCommit = ::request,
                    onTapCard = onTapCard,
                    rewind = rewind,
                    onRewindDone = { rewind = null },
                )
                // Order matters. A cold start on a returning user composes with
                // no items, isLoading false and reachedEnd false on the very
                // first frame, because loadMedia only flips isLoading from
                // inside a coroutine that has not run yet. Without
                // hasLoadedOnce that frame reads as a load failure and flashes
                // an error on every launch.
                !hasLoadedOnce || isLoading -> CircularProgressIndicator(color = c.accent)
                reachedEnd -> DeckMessage(
                    glyph = "\u2713",
                    glyphColor = c.teal,
                    title = "Every photo's been seen",
                    body = if (hasOnlyPartialMediaAccess) {
                        "That is everything you shared with Keepix. Share more photos to keep going."
                    } else {
                        "New photos land here automatically."
                    },
                )
                else -> DeckMessage(
                    glyph = "\u21BA",
                    glyphColor = c.clayGlyph,
                    title = "Couldn't load your library",
                    body = "Nothing was changed. Try again?",
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
            }
        }

        ActionRow(
            front = visible.firstOrNull(),
            favorited = visible.firstOrNull()?.let(isFavorite) ?: false,
            onAction = ::request,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

@Composable
private fun CardStack(
    items: List<MediaItem>,
    onCommit: (MediaItem, DeckAction) -> Unit,
    onTapCard: (MediaItem) -> Unit,
    rewind: DeckAction?,
    onRewindDone: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val front = items.firstOrNull()

    // Keyed on the front item so a new card always starts at rest. This is also
    // why the outgoing card is a separate composable below: the handoff warns
    // that resetting the front card's offset after advancing the index lands one
    // frame late and shows a visible slide-back. Never resetting it at all is
    // simpler than getting that frame right.
    val drag = remember(front?.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    var exiting by remember { mutableStateOf<ExitingCard?>(null) }

    // The finger's own travel, before the axis lock is applied. The card follows
    // only the locked component of this, never the raw diagonal.
    var raw by remember(front?.id) { mutableStateOf(Offset.Zero) }
    var axis by remember(front?.id) { mutableStateOf<DragAxis?>(null) }
    val velocity = remember(front?.id) { VelocityTracker() }

    val hCommitPx = with(density) { HorizontalCommit.toPx() }
    val vCommitPx = with(density) { VerticalCommit.toPx() }
    val lockSlopPx = with(density) { AxisLockSlop.toPx() }

    fun endGesture() {
        raw = Offset.Zero
        axis = null
        velocity.resetTracking()
    }

    fun commit(item: MediaItem, action: DeckAction, from: Offset) {
        exiting = ExitingCard(item, action, from)
        onCommit(item, action)
    }

    // Undo replays the swipe backwards: the restored card is placed where it
    // flew to and springs home. Keyed on the front id as well as the flag so it
    // runs against the freshly-restored card's own Animatable.
    LaunchedEffect(front?.id, rewind) {
        val action = rewind ?: return@LaunchedEffect
        drag.snapTo(exitOffset(action, density))
        drag.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 380f))
        onRewindDone()
    }

    Box(Modifier.fillMaxSize()) {
        // Back to front: the third card paints first so the front card sits on
        // top. Indexed rather than indexOf -- two rows can share a display name
        // and identity here is positional, not structural.
        for (depth in minOf(2, items.lastIndex) downTo 1) {
            DeckCard(
                item = items[depth],
                modifier = Modifier.graphicsLayer {
                    translationY = (if (depth == 1) 14.dp else 28.dp).toPx()
                    val s = if (depth == 1) 0.945f else 0.89f
                    scaleX = s
                    scaleY = s
                },
            )
        }

        if (front != null) {
            val dxDp = with(density) { drag.value.x.toDp().value }
            val dyDp = with(density) { drag.value.y.toDp().value }
            DeckCard(
                item = front,
                keepProgress = (dxDp / 100f).coerceIn(0f, 1f),
                binProgress = (-dxDp / 100f).coerceIn(0f, 1f),
                // The 24dp dead zone is the prototype's, not an invention.
                shrinkProgress = if (front.isVideo) 0f else ((dyDp - 24f) / 90f).coerceIn(0f, 1f),
                modifier = Modifier
                    .graphicsLayer {
                        translationX = drag.value.x
                        translationY = drag.value.y
                        rotationZ = cardRotationDegrees(dxDp)
                    }
                    .pointerInput(front.id) {
                        detectTapGestures { onTapCard(front) }
                    }
                    .pointerInput(front.id) {
                        detectDragGestures(
                            onDragStart = {
                                // Kill any rewind in flight, or its longer spring
                                // bleeds into this swipe.
                                onRewindDone()
                                endGesture()
                            },
                            onDragCancel = {
                                endGesture()
                                scope.launch { drag.animateTo(Offset.Zero) }
                            },
                            onDragEnd = {
                                val v = velocity.calculateVelocity()
                                val action = resolveDeckAction(
                                    dx = drag.value.x,
                                    dy = drag.value.y,
                                    horizontalThresholdPx = hCommitPx,
                                    verticalThresholdPx = vCommitPx,
                                    velocityX = v.x,
                                    velocityY = v.y,
                                    axis = axis,
                                    // Video shrink is out of v1: a downward drag
                                    // on a video springs back instead.
                                    shrinkEnabled = !front.isVideo,
                                )
                                val released = drag.value
                                endGesture()
                                if (action != null) {
                                    commit(front, action, released)
                                } else {
                                    scope.launch { drag.animateTo(Offset.Zero) }
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            raw += amount
                            // Latched: once an axis is chosen it holds for the
                            // rest of the gesture, so the card cannot wander
                            // between keep and shrink mid-drag.
                            if (axis == null) axis = dragAxis(raw.x, raw.y, lockSlopPx)
                            // Velocity is tracked in the finger's own space. The
                            // node's local coordinates travel with the card, so
                            // change.position would report a flick as nearly
                            // stationary.
                            velocity.addPosition(change.uptimeMillis, raw)
                            val locked = when (axis) {
                                DragAxis.HORIZONTAL -> Offset(raw.x, 0f)
                                DragAxis.VERTICAL -> Offset(0f, raw.y)
                                null -> Offset.Zero
                            }
                            scope.launch { drag.snapTo(locked) }
                        }
                    },
            )
        }

        exiting?.let { flight ->
            ExitingCardOverlay(flight) { exiting = null }
        }
    }
}

/** Animates a committed card off screen, then removes itself. */
@Composable
private fun ExitingCardOverlay(flight: ExitingCard, onDone: () -> Unit) {
    val density = LocalDensity.current
    val offset = remember(flight) { Animatable(flight.from, Offset.VectorConverter) }
    val extra = remember(flight) { Animatable(0f) }

    LaunchedEffect(flight) {
        launch { extra.animateTo(1f, tween(CARD_EXIT_MS, easing = CardExitEasing)) }
        offset.animateTo(exitOffset(flight.action, density), tween(CARD_EXIT_MS, easing = CardExitEasing))
        onDone()
    }

    val t = extra.value
    DeckCard(
        item = flight.item,
        modifier = Modifier.graphicsLayer {
            translationX = offset.value.x
            translationY = offset.value.y
            when (flight.action) {
                DeckAction.KEEP -> rotationZ = 24f * t
                DeckAction.BIN -> rotationZ = -24f * t
                DeckAction.SHRINK -> {
                    val s = 1f - 0.22f * t
                    scaleX = s
                    scaleY = s
                    alpha = 1f - t
                }
            }
        },
    )
}

@Composable
private fun DeckCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    keepProgress: Float = 0f,
    binProgress: Float = 0f,
    shrinkProgress: Float = 0f,
) {
    val c = neu
    Box(
        modifier
            .fillMaxSize()
            .neuExtruded(RoundedCornerShape(32.dp), offset = 12.dp, blur = 20.dp, strong = true)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                // The well's shadow falls across the photo, so it draws over the
                // image rather than behind it.
                .neuInsetOver(RoundedCornerShape(24.dp)),
        ) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (item.isVideo) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                        .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("▶", style = NeuType.microBadge, color = c.accent)
                    Text(formatDuration(item.durationMs), style = NeuType.buttonLabel, color = c.textPrimary)
                }
            }

            // Metadata is an overlay inside the well, not a strip beneath the
            // image -- the prose handoff has this the other way round.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .neuExtruded(RoundedCornerShape(16.dp), offset = 5.dp, blur = 10.dp)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        style = NeuType.itemName,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDate(item.dateAdded),
                        style = NeuType.metadata,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .neuInset(RoundedCornerShape(12.dp), offset = 3.dp, blur = 6.dp)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                ) {
                    Text(formatSizeShort(item.sizeBytes), style = NeuType.microBadge, color = c.textPrimary)
                }
            }

            DragStamp(keepProgress, Color(0x6138B2AC), "KEEP", -9f, c.tealDeep)
            DragStamp(binProgress, Color(0x5CC4665C), "BIN", 9f, c.clay)
            ShrinkOverlay(shrinkProgress, item)
        }
    }
}

@Composable
private fun DragStamp(progress: Float, wash: Color, label: String, rotation: Float, labelColor: Color) {
    if (progress <= 0f) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(wash),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer { rotationZ = rotation }
                .neuExtruded(RoundedCornerShape(16.dp))
                .padding(horizontal = 26.dp, vertical = 16.dp),
        ) {
            Text(label, style = NeuType.screenTitle, color = labelColor)
        }
    }
}

@Composable
private fun ShrinkOverlay(progress: Float, item: MediaItem) {
    if (progress <= 0f) return
    val c = neu
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(Color(0x576C63FF)),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .neuInset(CircleShape, offset = 10.dp, blur = 20.dp, strong = true),
            contentAlignment = Alignment.Center,
        ) { Text("↓", style = NeuType.screenTitle, color = c.accent) }
        Column(
            Modifier
                .neuExtruded(RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SHRINK IT", style = NeuType.sectionHeader, color = c.textPrimary)
            // ponytail: the prototype prints a predicted "-> ~1.8 MB" from a
            // hardcoded 0.42 ratio. The addendum forbids shipping invented
            // savings, and the real figure needs the compressor's header scan,
            // which is far too heavy to run mid-drag. Shows the current size
            // only until that estimate can be precomputed for the front card.
            Text(
                formatSizeShort(item.sizeBytes),
                style = NeuType.metadata,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DeckHeader(
    streakDays: Int,
    queuedCount: Int,
    pending: PendingCommit?,
    onUndo: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val c = neu
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Inset, not extruded: the streak chip is carved, per the prototype.
        Row(
            Modifier
                .neuInset(CircleShape, offset = 3.dp, blur = 6.dp)
                .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("◆", style = NeuType.buttonLabel, color = c.accent)
            Text("$streakDays", style = NeuType.sectionHeader, color = c.textPrimary)
            Text("day streak", style = NeuType.metadata, color = c.textSecondary)
        }

        Box(Modifier.weight(1f))

        if (queuedCount > 0) {
            Row(
                Modifier
                    .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(c.accent, CircleShape))
                Text("Shrinking $queuedCount", style = NeuType.buttonLabel, color = c.textPrimary)
            }
        }

        if (pending != null) UndoRing(pending, onUndo)

        // 2x2 grid of 5dp squares with 3dp gaps -- CSS shapes in the prototype,
        // so there is no icon asset to port.
        Box(
            Modifier
                .size(44.dp)
                .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                .clickable(onClick = onOpenLibrary),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(c.textSecondary, RoundedCornerShape(1.5.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The countdown. A violet arc sweeping 360 -> 0, and no numerals -- the arc is
 * the only indicator the design gives.
 */
@Composable
private fun UndoRing(pending: PendingCommit, onUndo: () -> Unit) {
    val c = neu
    // Driven off the wall clock rather than an Animatable, for the same reason
    // the deadline is: an animation can be scaled to zero duration, and the ring
    // must keep telling the truth about how long is left.
    val sweep by produceState(360f, pending) {
        while (true) {
            value = UndoWindow.sweepDegrees(pending.startedAtMs, System.currentTimeMillis())
            if (value <= 0f) break
            withFrameMillis { }
        }
    }
    Box(
        Modifier
            .size(46.dp)
            .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
            .clickable(onClick = onUndo),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = c.inactiveDot.copy(alpha = 0.22f),
                startAngle = -90f, sweepAngle = 360f, useCenter = true,
            )
            drawArc(color = c.accent, startAngle = -90f, sweepAngle = sweep, useCenter = true)
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .neuInset(CircleShape, offset = 3.dp, blur = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text("\u21BA", style = NeuType.itemName, color = c.accent) }
    }
}

/**
 * The app's signature object: three nested layers, extruded around inset around
 * extruded. Shared by onboarding and the exhausted deck at the same proportions.
 */
@Composable
private fun Medallion(glyph: String, glyphColor: Color) {
    Box(
        Modifier.size(120.dp).neuExtruded(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(86.dp).neuInset(CircleShape, offset = 10.dp, blur = 20.dp, strong = true),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(50.dp).neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, style = NeuType.screenTitle, color = glyphColor) }
        }
    }
}

@Composable
private fun DeckMessage(
    glyph: String,
    glyphColor: Color,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = neu
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Medallion(glyph, glyphColor)
        Text(title, style = NeuType.screenTitle, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(
            body,
            style = NeuType.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 276.dp),
        )
        if (actionLabel != null) {
            Box(
                Modifier
                    .neuExtruded(RoundedCornerShape(16.dp))
                    .background(c.accent, RoundedCornerShape(16.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 26.dp, vertical = 17.dp),
            ) { Text(actionLabel, style = NeuType.itemName, color = c.onAccent) }
        }
    }
}

/** Dismissible, and never covers the deck: a failed top-up must not hide the cards. */
@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    val c = neu
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .neuInset(RoundedCornerShape(16.dp), offset = 3.dp, blur = 6.dp)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = NeuType.metadata, color = c.clay, modifier = Modifier.weight(1f))
        Box(Modifier.clickable(onClick = onDismiss)) {
            Text("\u2715", style = NeuType.buttonLabel, color = c.textSecondary)
        }
    }
}

@Composable
private fun ActionRow(
    front: MediaItem?,
    favorited: Boolean,
    onAction: (MediaItem, DeckAction) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
) {
    val c = neu
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionCircle("✕", 62.dp, c.clayGlyph, enabled = front != null) {
            front?.let { onAction(it, DeckAction.BIN) }
        }
        ActionCircle(
            glyph = if (favorited) "♥" else "♡",
            size = 46.dp,
            // Favourite is a button and never a gesture: a mis-swiped favourite
            // writes to MediaStore behind a consent dialog.
            tint = if (favorited) c.favorite else c.textSecondary,
            inset = favorited,
            enabled = front != null,
        ) { front?.let(onToggleFavorite) }
        Box(
            Modifier
                .size(56.dp)
                .neuExtruded(CircleShape)
                .background(
                    // Disabled keeps the extruded rest shape and drops the fill to
                    // the inactive tone: on video in v1, and on an empty deck,
                    // where an accent-filled button still reads as pressable.
                    if (front == null || front.isVideo) c.inactiveDot else c.accent,
                    CircleShape,
                )
                .clickable(enabled = front != null && !front.isVideo) {
                    front?.let { onAction(it, DeckAction.SHRINK) }
                },
            contentAlignment = Alignment.Center,
        ) { Text("↓", style = NeuType.itemName, color = c.onAccent) }
        ActionCircle("✓", 62.dp, c.teal, enabled = front != null) {
            front?.let { onAction(it, DeckAction.KEEP) }
        }
    }
}

@Composable
private fun ActionCircle(
    glyph: String,
    size: Dp,
    tint: Color,
    enabled: Boolean = true,
    inset: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .let {
                if (inset) it.neuInset(CircleShape, offset = 3.dp, blur = 6.dp)
                else it.neuExtruded(CircleShape)
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Disabled keeps the extruded rest shape and drops the glyph to secondary.
        Text(glyph, style = NeuType.screenTitle, color = if (enabled) tint else neu.textSecondary)
    }
}

private fun formatDate(epochSeconds: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochSeconds * 1000))

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
