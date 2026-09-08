package com.sese.keepix.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Replaces the old reflection-based RecycleBinBadgeTest. That one had to reach
 * into a private function on a Compose file's facade class because there was no
 * seam; [binBadge] is that seam, so this just calls it.
 *
 * The wording changed with the restyle ("3d left", not "< 1 day left"), but the
 * behaviour under test did not: session rows never claim a date, an item in its
 * final day never reads as already gone, and urgency tracks
 * [RetentionWindow.URGENT_DAYS_LEFT].
 */
class BinBadgeTest {

    private val now = 1_700_000_000_000L

    private fun timed(remaining: Long, window: Long = TimeUnit.DAYS.toMillis(7)): BinBadge {
        val deletedAt = now + remaining - window
        return binBadge("TIMED", deletedAt, deletedAt + window, now)
    }

    @Test
    fun `session rows never promise a date`() {
        val badge = binBadge("SESSION", now, 0L, now)
        assertEquals("on reopen", badge.short)
        assertEquals("Deleted when you reopen the app", badge.long)
        assertTrue(badge.urgent)
    }

    @Test
    fun `a timed row with a zero expiry is treated as session, not as expired`() {
        // Defensive: a row written before expiryAt was populated must not read
        // as "deleted today" and scare the user into restoring everything.
        assertEquals("on reopen", binBadge("TIMED", now, 0L, now).short)
    }

    @Test
    fun `the final day still reads as a day left, not zero`() {
        val badge = timed(TimeUnit.HOURS.toMillis(5))
        assertEquals("1d left", badge.short)
        assertEquals("Permanently deleted in 1 day", badge.long)
        assertTrue(badge.urgent)
    }

    @Test
    fun `an expired row reads as gone today`() {
        val badge = timed(-TimeUnit.HOURS.toMillis(1))
        assertEquals("today", badge.short)
        assertTrue(badge.urgent)
    }

    @Test
    fun `two days left is still urgent`() {
        val badge = timed(TimeUnit.DAYS.toMillis(2) + TimeUnit.HOURS.toMillis(1))
        assertEquals("2d left", badge.short)
        assertTrue(badge.urgent)
    }

    @Test
    fun `three days left is not urgent`() {
        val badge = timed(TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(1))
        assertEquals("3d left", badge.short)
        assertFalse(badge.urgent)
    }

    @Test
    fun `plural is correct on the long form`() {
        assertEquals("Permanently deleted in 5 days", timed(TimeUnit.DAYS.toMillis(5)).long)
    }
}
