package com.sese.keepix.core.logic

/**
 * Compression tiers. Values from the handoff addendum's Part D -- the only part
 * of the handoff's compressor sketch that still binds, since the existing
 * PhotoCompressor (JPEG/MPF/HDR gain maps) is well past that sketch.
 */
enum class QualityTier(
    val label: String,
    val quality: Int,
    /** Longest edge in px, or null to leave dimensions untouched. */
    val longestEdge: Int?,
    val proOnly: Boolean,
    /**
     * Typical size reduction, shown under the tier's name in Settings. A
     * ballpark from the prototype, not a promise -- the actual saving depends
     * entirely on the source photo, which is why it is worded with a tilde.
     */
    val savingsTag: String,
) {
    LIGHT("Light", quality = 80, longestEdge = null, proOnly = false, savingsTag = "~58%"),
    BALANCED("Balanced", quality = 65, longestEdge = 3072, proOnly = true, savingsTag = "~72%"),
    MAX("Max", quality = 50, longestEdge = 2048, proOnly = true, savingsTag = "~82%"),
}

/** Free tier gets Light only, this many times a day. */
const val FREE_LIGHT_USES_PER_DAY = 3

/** A streak this long grants one free Max, and again on every further multiple. */
const val STREAK_DAYS_PER_FREE_MAX = 7

/**
 * Whether [tier] can be used right now.
 *
 * [freeMaxCredits] are the unspent grants earned by streaks; Balanced is Pro
 * only and streaks never unlock it.
 */
fun canUseTier(
    tier: QualityTier,
    isPro: Boolean,
    lightUsesToday: Int,
    freeMaxCredits: Int,
): Boolean = when {
    isPro -> true
    tier == QualityTier.LIGHT -> lightUsesToday < FREE_LIGHT_USES_PER_DAY
    tier == QualityTier.MAX -> freeMaxCredits > 0
    else -> false
}

/**
 * The streak after an action lands.
 *
 * Increments on the **first action of a day**, not on app open -- opening the
 * app and swiping nothing is not a day of cleaning. Days are epoch days so this
 * stays pure and timezone conversion happens at the edge.
 *
 * [lastActionEpochDay] is null when the user has never acted.
 */
fun streakAfterAction(
    currentStreak: Int,
    lastActionEpochDay: Long?,
    todayEpochDay: Long,
): Int = when {
    lastActionEpochDay == null -> 1
    lastActionEpochDay == todayEpochDay -> currentStreak.coerceAtLeast(1)
    lastActionEpochDay == todayEpochDay - 1 -> currentStreak + 1
    else -> 1
}

/** True when reaching [streakDays] should grant a free Max. */
fun grantsFreeMax(streakDays: Int): Boolean =
    streakDays >= STREAK_DAYS_PER_FREE_MAX && streakDays % STREAK_DAYS_PER_FREE_MAX == 0
