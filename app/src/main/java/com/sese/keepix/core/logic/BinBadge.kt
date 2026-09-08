package com.sese.keepix.core.logic

/**
 * The countdown a binned item shows: how long until the file is gone for good.
 *
 * One function rather than a copy in the tile and another in the sheet -- the
 * tile saying "2d left" while the sheet says "3 days" about the same photo is
 * exactly the kind of drift a user notices and stops trusting.
 *
 * Pure, taking `nowMs` rather than reading the clock, for the same reason
 * [RetentionWindow] does.
 */
data class BinBadge(
    /** Short form for the tile, e.g. "3d left". */
    val short: String,
    /** Long form for the sheet, e.g. "Permanently deleted in 3 days". */
    val long: String,
    /** True when the item is about to go, and the label should be clay. */
    val urgent: Boolean,
)

/**
 * [retentionMode] is the row's own, not the current preference: changing the
 * setting must not retroactively move an already-binned item's deadline. The
 * window is recovered from the row's own stored span for the same reason.
 */
fun binBadge(
    retentionMode: String,
    deletedAtMs: Long,
    expiryAtMs: Long,
    nowMs: Long,
): BinBadge {
    if (retentionMode == "SESSION" || expiryAtMs == 0L) {
        return BinBadge(
            short = "on reopen",
            long = "Deleted when you reopen the app",
            urgent = true,
        )
    }
    val storedDays = ((expiryAtMs - deletedAtMs) / RetentionWindow.MS_PER_DAY)
        .toInt().coerceAtLeast(1)
    val left = RetentionWindow(storedDays).daysLeft(deletedAtMs, nowMs)
    return if (left == 0) {
        BinBadge("today", "Deleted today", urgent = true)
    } else {
        BinBadge(
            short = "${left}d left",
            long = "Permanently deleted in $left day${if (left == 1) "" else "s"}",
            urgent = left <= RetentionWindow.URGENT_DAYS_LEFT,
        )
    }
}
