package com.sese.keepix.core.logic

/**
 * How long a binned item stays recoverable. `0` means "session only" -- deleted
 * on next app launch, which is a startup task rather than a timer.
 *
 * Pure: every function takes `nowMs` rather than reading the clock, so the
 * expiry rules are testable without waiting or mocking time.
 */
@JvmInline
value class RetentionWindow(val days: Int) {

    val isSessionOnly: Boolean get() = days == 0

    /** When an item binned at [binnedAtMs] stops being recoverable. */
    fun expiryAtMs(binnedAtMs: Long): Long =
        if (isSessionOnly) 0L else binnedAtMs + days * MS_PER_DAY

    fun isExpired(binnedAtMs: Long, nowMs: Long): Boolean =
        !isSessionOnly && nowMs >= expiryAtMs(binnedAtMs)

    /**
     * Whole days left, for the bin tile's "4 days left" badge.
     *
     * Floors, but never below 1 while the item is still recoverable. Rounding up
     * would promise a day that isn't there (5 days and one millisecond would read
     * "6 days left"); plain flooring would read "0 days left" for the whole final
     * day, on an item the user can still restore. Neither is acceptable on a
     * countdown the user makes decisions against.
     */
    fun daysLeft(binnedAtMs: Long, nowMs: Long): Int {
        if (isSessionOnly) return 0
        val remaining = expiryAtMs(binnedAtMs) - nowMs
        return if (remaining <= 0) 0 else maxOf(1, (remaining / MS_PER_DAY).toInt())
    }

    companion object {
        const val MS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * The wheel picker's detents. The prototype shipped ten stops
         * (up to 365) because a browser has no trash to answer to; MediaStore's
         * own trash expires at ~30 days, so anything beyond that would need the
         * bytes duplicated into app-private storage -- which doubles storage
         * consumption inside an app whose entire purpose is reclaiming it.
         * Capped at 30 per the handoff addendum's decision A1.
         */
        val STOPS = listOf(0, 1, 3, 7, 14, 30)

        val DEFAULT = RetentionWindow(7)

        /** Badge turns clay at this many days left or fewer. */
        const val URGENT_DAYS_LEFT = 2

        /**
         * Snaps a stored value onto a valid stop.
         *
         * Rounds **up**, never down: shipped builds defaulted to 10 days, which
         * is not a stop any more, and quietly re-reading that as 7 would shorten
         * a live recovery window by three days without asking. 10 becomes 14.
         * The 30 cap is the one place this can shorten, and it has to -- past 30
         * the system trash has already dropped the bytes.
         */
        fun fromStoredDays(days: Int): RetentionWindow {
            val capped = days.coerceIn(0, STOPS.last())
            return RetentionWindow(STOPS.first { it >= capped })
        }
    }
}
