package com.sese.keepix.ui.neu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sese.keepix.core.logic.DeckAction
import com.sese.keepix.core.logic.cardRotationDegrees
import com.sese.keepix.core.logic.resolveDeckAction
import com.sese.keepix.data.MediaItem
import com.sese.keepix.utils.formatSizeShort
import kotlinx.coroutines.launch
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

/** A card mid-flight. Rendered above the stack so the incoming card is never animated. */
private data class ExitingCard(val item: MediaItem, val action: DeckAction, val from: Offset)

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
) {
    val c = neu
    Column(
        modifier
            .fillMaxSize()
            .background(c.surface)
            .systemBarsPadding(),
    ) {
        DeckHeader(streakDays, queuedCount, onOpenLibrary)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
        ) {
            CardStack(items, onCommit, onTapCard)
        }

        ActionRow(
            front = items.firstOrNull(),
            favorited = items.firstOrNull()?.let(isFavorite) ?: false,
            onAction = { item, action -> onCommit(item, action) },
            onToggleFavorite = onToggleFavorite,
        )
    }
}

@Composable
private fun CardStack(
    items: List<MediaItem>,
    onCommit: (MediaItem, DeckAction) -> Unit,
    onTapCard: (MediaItem) -> Unit,
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
    var dragging by remember(front?.id) { mutableStateOf(false) }
    var exiting by remember { mutableStateOf<ExitingCard?>(null) }

    val hCommitPx = with(density) { HorizontalCommit.toPx() }
    val vCommitPx = with(density) { VerticalCommit.toPx() }

    fun commit(item: MediaItem, action: DeckAction, from: Offset) {
        exiting = ExitingCard(item, action, from)
        onCommit(item, action)
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
                            onDragStart = { dragging = true },
                            onDragCancel = {
                                dragging = false
                                scope.launch { drag.animateTo(Offset.Zero) }
                            },
                            onDragEnd = {
                                dragging = false
                                val action = resolveDeckAction(
                                    dx = drag.value.x,
                                    dy = drag.value.y,
                                    horizontalThresholdPx = hCommitPx,
                                    verticalThresholdPx = vCommitPx,
                                    // Video shrink is out of v1: a downward drag
                                    // on a video springs back instead.
                                    shrinkEnabled = !front.isVideo,
                                )
                                if (action != null) {
                                    commit(front, action, drag.value)
                                } else {
                                    scope.launch { drag.animateTo(Offset.Zero) }
                                }
                            },
                        ) { change, amount ->
                            change.consume()
                            scope.launch { drag.snapTo(drag.value + amount) }
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

    androidx.compose.runtime.LaunchedEffect(flight) {
        val target = with(density) {
            when (flight.action) {
                DeckAction.KEEP -> Offset(540.dp.toPx(), 60.dp.toPx())
                DeckAction.BIN -> Offset((-540).dp.toPx(), 60.dp.toPx())
                DeckAction.SHRINK -> Offset(0f, 600.dp.toPx())
            }
        }
        launch { extra.animateTo(1f, tween(CARD_EXIT_MS, easing = CardExitEasing)) }
        offset.animateTo(target, tween(CARD_EXIT_MS, easing = CardExitEasing))
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
private fun DeckHeader(streakDays: Int, queuedCount: Int, onOpenLibrary: () -> Unit) {
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
                    // Shrink is disabled on video in v1: extruded rest shape kept,
                    // fill dropped to the inactive tone.
                    if (front != null && front.isVideo) c.inactiveDot else c.accent,
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
