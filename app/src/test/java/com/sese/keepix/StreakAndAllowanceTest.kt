package com.sese.keepix

import android.content.Context
import com.sese.keepix.core.logic.FREE_LIGHT_USES_PER_DAY
import com.sese.keepix.core.logic.QualityTier
import com.sese.keepix.core.logic.STREAK_DAYS_PER_FREE_MAX
import com.sese.keepix.data.KeepixPreferences
import com.sese.keepix.testutil.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The progression state that used to live in memory (and therefore reset on
 * every process launch) now lives in preferences. These pin the three rules
 * that are easy to get subtly wrong: the allowance resets daily, the streak
 * counts a day once no matter how many cards were swiped, and a completed week
 * grants exactly one Max credit.
 *
 * Uses the real [FakeSharedPreferences] rather than a mock so the day-keyed
 * reads and writes actually interact, which is the whole point.
 */
class StreakAndAllowanceTest {

    private val store = FakeSharedPreferences()

    private fun prefs(): KeepixPreferences {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns store
        return KeepixPreferences(context)
    }

    private val today = LocalDate.now().toEpochDay()

    @Test
    fun `the light allowance survives within a day`() {
        val p = prefs()
        assertEquals(0, p.lightUsesToday)
        p.lightUsesToday = 2
        assertEquals(2, prefs().lightUsesToday)
    }

    @Test
    fun `the light allowance resets when the stored day is not today`() {
        val p = prefs()
        p.lightUsesToday = FREE_LIGHT_USES_PER_DAY
        // Rewind the stamp without touching the count -- what "yesterday's
        // process wrote this" looks like on disk.
        store.seed("light_uses_day", today - 1)
        assertEquals(0, prefs().lightUsesToday)
    }

    @Test
    fun `the first swipe ever starts the streak at one`() {
        assertEquals(1, prefs().recordSwipeToday())
    }

    @Test
    fun `a second swipe on the same day does not advance the streak`() {
        val p = prefs()
        assertEquals(1, p.recordSwipeToday())
        assertEquals(1, p.recordSwipeToday())
        assertEquals(1, p.recordSwipeToday())
    }

    @Test
    fun `a swipe the next day advances the streak`() {
        val p = prefs()
        p.recordSwipeToday()
        store.seed("last_action_day", today - 1)
        assertEquals(2, prefs().recordSwipeToday())
    }

    @Test
    fun `a missed day resets the streak to one`() {
        val p = prefs()
        p.recordSwipeToday()
        store.seed("streak_days", 6)
        store.seed("last_action_day", today - 2)
        assertEquals(1, prefs().recordSwipeToday())
    }

    @Test
    fun `completing a week grants exactly one free Max credit`() {
        store.seed("streak_days", STREAK_DAYS_PER_FREE_MAX - 1)
        store.seed("last_action_day", today - 1)
        val p = prefs()
        assertEquals(STREAK_DAYS_PER_FREE_MAX, p.recordSwipeToday())
        assertEquals(1, p.freeMaxCredits)
        // Swiping again the same day must not mint a second credit.
        p.recordSwipeToday()
        assertEquals(1, prefs().freeMaxCredits)
    }

    @Test
    fun `an unrecognised stored tier falls back to the free one`() {
        store.seed("quality_tier", "PLATINUM")
        assertEquals(QualityTier.LIGHT, prefs().qualityTier)
    }

    @Test
    fun `the tier round-trips by name`() {
        val p = prefs()
        p.qualityTier = QualityTier.MAX
        assertEquals(QualityTier.MAX, prefs().qualityTier)
    }
}
