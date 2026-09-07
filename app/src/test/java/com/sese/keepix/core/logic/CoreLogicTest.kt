package com.sese.keepix.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2's pure rules. No Android, no clock reads, no coroutines. */
class CoreLogicTest {

    // ---- Retention ----------------------------------------------------------

    @Test fun `stored days snap up to a stop, never down`() {
        // 10 was the shipped default and is not a stop any more. Reading it as 7
        // would silently shorten a live recovery window by three days.
        assertEquals(14, RetentionWindow.fromStoredDays(10).days)
        assertEquals(3, RetentionWindow.fromStoredDays(2).days)
        assertEquals(1, RetentionWindow.fromStoredDays(1).days)
        assertEquals(0, RetentionWindow.fromStoredDays(0).days)
    }

    @Test fun `stored days cap at thirty`() {
        // The one place shortening is forced: past ~30 days MediaStore's trash
        // has already dropped the bytes, so a longer window is a lie.
        assertEquals(30, RetentionWindow.fromStoredDays(365).days)
        assertEquals(30, RetentionWindow.fromStoredDays(31).days)
    }

    @Test fun `session only never expires on a timer`() {
        val session = RetentionWindow(0)
        assertTrue(session.isSessionOnly)
        assertEquals(0L, session.expiryAtMs(1_000L))
        assertFalse(session.isExpired(0L, Long.MAX_VALUE))
    }

    @Test fun `expiry is exactly the window past binning`() {
        val w = RetentionWindow(7)
        val binned = 1_000_000L
        val expiry = binned + 7 * RetentionWindow.MS_PER_DAY
        assertEquals(expiry, w.expiryAtMs(binned))
        assertFalse(w.isExpired(binned, expiry - 1))
        assertTrue(w.isExpired(binned, expiry))
    }

    @Test fun `days left never overstates, and never reads zero while recoverable`() {
        val w = RetentionWindow(7)
        val binned = 0L
        assertEquals(7, w.daysLeft(binned, 0L))
        // 5 days + 1ms remain. Rounding up would promise 6.
        assertEquals(5, w.daysLeft(binned, 2 * RetentionWindow.MS_PER_DAY - 1))
        // Final day: less than 24h left but still restorable, so not "0 days left".
        assertEquals(1, w.daysLeft(binned, 7 * RetentionWindow.MS_PER_DAY - 1))
        assertEquals(0, w.daysLeft(binned, 7 * RetentionWindow.MS_PER_DAY))
        assertEquals(0, w.daysLeft(binned, Long.MAX_VALUE / 2))
    }

    // ---- Deck gestures ------------------------------------------------------

    private fun act(dx: Float, dy: Float, shrink: Boolean = true) =
        resolveDeckAction(dx, dy, horizontalThresholdPx = 92f, verticalThresholdPx = 110f, shrinkEnabled = shrink)

    @Test fun `horizontal drags keep and bin`() {
        assertEquals(DeckAction.KEEP, act(100f, 0f))
        assertEquals(DeckAction.BIN, act(-100f, 0f))
    }

    @Test fun `down shrinks and up does nothing`() {
        // Reversed from the glassmorphism build, where up favourited and down
        // was unused. Favourite is a button now and must never be a gesture.
        assertEquals(DeckAction.SHRINK, act(0f, 120f))
        assertNull(act(0f, -120f))
    }

    @Test fun `vertical threshold is higher than horizontal`() {
        // 100px commits across but not down: shrink rewrites bytes, so it asks
        // for a more deliberate drag.
        assertEquals(DeckAction.KEEP, act(100f, 0f))
        assertNull(act(0f, 100f))
    }

    @Test fun `horizontal wins ties`() {
        assertEquals(DeckAction.KEEP, act(120f, 120f))
    }

    @Test fun `video springs back instead of shrinking`() {
        assertNull(act(0f, 200f, shrink = false))
        // Keep and bin still work on a video card.
        assertEquals(DeckAction.BIN, act(-100f, 0f, shrink = false))
    }

    @Test fun `short drags spring back`() = assertNull(act(50f, 50f))

    @Test fun `card rotation clamps at twelve degrees`() {
        assertEquals(0f, cardRotationDegrees(0f), 0.001f)
        assertEquals(2f, cardRotationDegrees(44f), 0.001f)
        assertEquals(MAX_CARD_ROTATION, cardRotationDegrees(9999f), 0.001f)
        assertEquals(-MAX_CARD_ROTATION, cardRotationDegrees(-9999f), 0.001f)
    }

    // ---- Undo window --------------------------------------------------------

    @Test fun `undo window counts down and floors at zero`() {
        assertEquals(UndoWindow.MILLIS, UndoWindow.remainingMs(0L, 0L))
        assertEquals(2_000L, UndoWindow.remainingMs(0L, 3_000L))
        assertEquals(0L, UndoWindow.remainingMs(0L, UndoWindow.MILLIS))
        assertEquals(0L, UndoWindow.remainingMs(0L, 999_999L))
        // A clock that jumped backwards must not hand out a longer window.
        assertEquals(UndoWindow.MILLIS, UndoWindow.remainingMs(0L, -5_000L))
    }

    @Test fun `sweep runs a full circle down to nothing`() {
        assertEquals(360f, UndoWindow.sweepDegrees(0L, 0L), 0.001f)
        assertEquals(180f, UndoWindow.sweepDegrees(0L, UndoWindow.MILLIS / 2), 0.001f)
        assertEquals(0f, UndoWindow.sweepDegrees(0L, UndoWindow.MILLIS), 0.001f)
        assertFalse(UndoWindow.isOpen(0L, UndoWindow.MILLIS))
    }

    // ---- Tiers and streak ---------------------------------------------------

    @Test fun `free tier gets light three times a day and nothing else`() {
        assertTrue(canUseTier(QualityTier.LIGHT, isPro = false, lightUsesToday = 2, freeMaxCredits = 0))
        assertFalse(canUseTier(QualityTier.LIGHT, isPro = false, lightUsesToday = 3, freeMaxCredits = 0))
        assertFalse(canUseTier(QualityTier.BALANCED, isPro = false, lightUsesToday = 0, freeMaxCredits = 9))
    }

    @Test fun `a streak credit unlocks max but never balanced`() {
        assertTrue(canUseTier(QualityTier.MAX, isPro = false, lightUsesToday = 3, freeMaxCredits = 1))
        assertFalse(canUseTier(QualityTier.MAX, isPro = false, lightUsesToday = 0, freeMaxCredits = 0))
        assertFalse(canUseTier(QualityTier.BALANCED, isPro = false, lightUsesToday = 0, freeMaxCredits = 1))
    }

    @Test fun `pro unlocks every tier`() {
        QualityTier.entries.forEach {
            assertTrue(it.name, canUseTier(it, isPro = true, lightUsesToday = 99, freeMaxCredits = 0))
        }
    }

    @Test fun `streak increments on the first action of a day only`() {
        assertEquals(1, streakAfterAction(currentStreak = 0, lastActionEpochDay = null, todayEpochDay = 100))
        assertEquals(4, streakAfterAction(currentStreak = 3, lastActionEpochDay = 99, todayEpochDay = 100))
        // Second action the same day changes nothing.
        assertEquals(3, streakAfterAction(currentStreak = 3, lastActionEpochDay = 100, todayEpochDay = 100))
        // A skipped day resets to today, not to zero -- the user did act today.
        assertEquals(1, streakAfterAction(currentStreak = 9, lastActionEpochDay = 98, todayEpochDay = 100))
    }

    @Test fun `free max lands every seventh day`() {
        assertTrue(grantsFreeMax(7))
        assertTrue(grantsFreeMax(14))
        assertFalse(grantsFreeMax(8))
        assertFalse(grantsFreeMax(0))
    }
}
