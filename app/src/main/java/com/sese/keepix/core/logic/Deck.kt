package com.sese.keepix.core.logic

import kotlin.math.abs

/** The three decisions the deck offers. Favourite is a button, never a gesture. */
enum class DeckAction { KEEP, BIN, SHRINK }

/**
 * Which action a released drag commits to, or null to spring back.
 *
 * Horizontal wins ties, so an ambiguous diagonal resolves to the common,
 * reversible outcome rather than queueing a rewrite of the file.
 *
 * Thresholds are asymmetric on purpose -- 92dp across, 110dp down. Shrink is the
 * only action that rewrites bytes, so it asks for a more deliberate drag.
 *
 * Swipe down is context-aware across the app: it shrinks here, and closes the
 * full-screen viewer. Only the deck calls this.
 *
 * @param shrinkEnabled false for video cards. Media3 Transformer runs at roughly
 *   realtime/4, so video shrink is out of v1 (addendum A4) -- a downward drag on
 *   a video springs back rather than committing.
 */
fun resolveDeckAction(
    dx: Float,
    dy: Float,
    horizontalThresholdPx: Float,
    verticalThresholdPx: Float,
    shrinkEnabled: Boolean = true,
): DeckAction? {
    val horizontal = abs(dx) >= abs(dy)
    return when {
        horizontal && dx >= horizontalThresholdPx -> DeckAction.KEEP
        horizontal && dx <= -horizontalThresholdPx -> DeckAction.BIN
        !horizontal && shrinkEnabled && dy >= verticalThresholdPx -> DeckAction.SHRINK
        else -> null
    }
}

/**
 * Card tilt while dragging, in degrees. [dragXDp] is the horizontal drag in dp,
 * not pixels -- the prototype's `rotate(dx / 22)` is expressed against its own
 * dp-sized viewport, so converting first keeps the feel identical across
 * densities instead of tilting further on a denser screen.
 */
fun cardRotationDegrees(dragXDp: Float): Float =
    (dragXDp / 22f).coerceIn(-MAX_CARD_ROTATION, MAX_CARD_ROTATION)

const val MAX_CARD_ROTATION = 12f

/**
 * The undo window, and the commit deadline for everything it covers.
 *
 * This is not a cosmetic affordance. Binning does not write `IS_TRASHED` on
 * swipe -- it stages the trash request and fires it when this window closes,
 * which is also what lets consecutive bin swipes share one system consent
 * dialog instead of prompting per photo. Shrink enqueues to WorkManager with
 * this as the initial delay, so undo cancels the job before anything touches
 * disk.
 *
 * The ring animation, the trash delay and the WorkManager delay must all read
 * this one constant. If they drift apart the user can undo something already
 * written.
 */
object UndoWindow {
    const val MILLIS = 5_000L

    /** Milliseconds left, floored at 0. Drives the ring's 360 -> 0 sweep. */
    fun remainingMs(startedAtMs: Long, nowMs: Long): Long =
        (startedAtMs + MILLIS - nowMs).coerceIn(0L, MILLIS)

    /** Sweep angle for the countdown arc: full circle at the start, 0 at expiry. */
    fun sweepDegrees(startedAtMs: Long, nowMs: Long): Float =
        360f * remainingMs(startedAtMs, nowMs) / MILLIS

    fun isOpen(startedAtMs: Long, nowMs: Long): Boolean = remainingMs(startedAtMs, nowMs) > 0
}

/**
 * One level, no stack -- taking an action replaces any pending snapshot, which
 * is why the previous action must have committed by then (see [UndoWindow]).
 *
 * Counters are captured rather than recomputed because shrink's saving is only
 * known after the encoder runs; replaying the arithmetic backwards would drift.
 */
data class UndoSnapshot(
    val action: DeckAction,
    val index: Int,
    val keptCount: Int,
    val savedBytes: Long,
    val freedBytes: Long,
    val startedAtMs: Long,
    /** Bin ledger row to delete on undo, if the action was BIN. */
    val binEntryId: Long? = null,
    /** WorkManager job to cancel on undo, if the action was SHRINK. */
    val compressionJobId: String? = null,
)
