package com.sese.keepix.data

import android.content.Context
import android.content.SharedPreferences
import com.sese.keepix.core.logic.QualityTier
import com.sese.keepix.core.logic.RetentionWindow
import com.sese.keepix.core.logic.streakAfterAction
import com.sese.keepix.core.logic.grantsFreeMax
import java.time.LocalDate
import java.util.UUID

class KeepixPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("keepix_prefs", Context.MODE_PRIVATE)

    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 10)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    var batchSize: Int
        get() = prefs.getInt(KEY_BATCH_SIZE, 50)
        set(value) = prefs.edit().putInt(KEY_BATCH_SIZE, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var lastSessionId: String
        get() = prefs.getString(KEY_LAST_SESSION_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SESSION_ID, value).apply()

    var fullscreenTutorialComplete: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN_TUTORIAL_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN_TUTORIAL_COMPLETE, value).apply()

    /**
     * Compression strength for every shrink swipe.
     *
     * Stored by enum name, not ordinal: adding a tier in the middle of the enum
     * would silently reassign every user's stored ordinal. An unrecognised name
     * falls back to LIGHT, the free tier, which is the safe direction to fail --
     * it never hands a non-subscriber a Pro strength.
     */
    var qualityTier: QualityTier
        get() = QualityTier.entries.firstOrNull {
            it.name == prefs.getString(KEY_QUALITY_TIER, null)
        } ?: QualityTier.LIGHT
        set(value) = prefs.edit().putString(KEY_QUALITY_TIER, value.name).apply()

    var isPro: Boolean
        get() = prefs.getBoolean(KEY_IS_PRO, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PRO, value).apply()

    var freeMaxCredits: Int
        get() = prefs.getInt(KEY_FREE_MAX_CREDITS, 0)
        set(value) = prefs.edit().putInt(KEY_FREE_MAX_CREDITS, value).apply()

    /** Opt-in friends/leaderboard sync. Off means the app stays fully offline. */
    var accountEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACCOUNT, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCOUNT, value).apply()

    /** Library sort. Stored by name for the same reason as [qualityTier]. */
    var librarySortLargestFirst: Boolean
        get() = prefs.getBoolean(KEY_LIB_SORT_SIZE, false)
        set(value) = prefs.edit().putBoolean(KEY_LIB_SORT_SIZE, value).apply()

    /**
     * Free Light shrinks spent today.
     *
     * Reads as 0 once the stored day is not today, so the allowance resets at
     * local midnight without needing a scheduled job. Day is the local
     * year+dayOfYear pair rather than epochDay so "today" means what the user
     * sees on their calendar.
     */
    var lightUsesToday: Int
        get() = if (prefs.getLong(KEY_LIGHT_USES_DAY, -1L) == today()) {
            prefs.getInt(KEY_LIGHT_USES_COUNT, 0)
        } else 0
        set(value) = prefs.edit()
            .putLong(KEY_LIGHT_USES_DAY, today())
            .putInt(KEY_LIGHT_USES_COUNT, value)
            .apply()

    var streakDays: Int
        get() = prefs.getInt(KEY_STREAK_DAYS, 0)
        private set(value) = prefs.edit().putInt(KEY_STREAK_DAYS, value).apply()

    /** Epoch day of the last card acted on, or null if the user never has. */
    private var lastActionDay: Long?
        get() = prefs.getLong(KEY_LAST_ACTION_DAY, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
        set(value) = prefs.edit().putLong(KEY_LAST_ACTION_DAY, value ?: Long.MIN_VALUE).apply()

    /**
     * Records that the user acted on a card today and returns the new streak.
     *
     * Idempotent within a day -- the deck calls this on every swipe -- and the
     * continue/reset decision itself lives in [streakAfterAction] so it is
     * testable without SharedPreferences.
     */
    fun recordSwipeToday(): Int {
        val today = today()
        val last = lastActionDay
        if (last == today) return streakDays
        val updated = streakAfterAction(streakDays, last, today)
        streakDays = updated
        lastActionDay = today
        // A completed week is worth one free Max. Granted here, at the moment
        // the streak lands, because nothing else runs on a day boundary -- and
        // the `last == today` guard above means it can only fire once per day.
        if (grantsFreeMax(updated)) freeMaxCredits++
        return updated
    }

    fun generateNewSession(): String {
        val newId = UUID.randomUUID().toString()
        val previousId = lastSessionId
        lastSessionId = newId
        return previousId
    }

    val currentSessionId: String
        get() = lastSessionId

    // Retention arithmetic lives in RetentionWindow so the bin badge, the
    // cleanup worker and this agree by construction. The stored value is passed
    // through as-is: snapping it onto the new six-stop wheel is a migration that
    // belongs with the Settings rebuild, not here.
    private val window: RetentionWindow get() = RetentionWindow(retentionDays)

    val isSessionMode: Boolean
        get() = window.isSessionOnly

    fun getExpiryTimestamp(): Long = window.expiryAtMs(System.currentTimeMillis())

    companion object {
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_LAST_SESSION_ID = "last_session_id"
        private const val KEY_FULLSCREEN_TUTORIAL_COMPLETE = "fullscreen_tutorial_complete"
        private const val KEY_QUALITY_TIER = "quality_tier"
        private const val KEY_IS_PRO = "is_pro"
        private const val KEY_FREE_MAX_CREDITS = "free_max_credits"
        private const val KEY_ACCOUNT = "account_enabled"
        private const val KEY_LIB_SORT_SIZE = "library_sort_largest_first"
        private const val KEY_LIGHT_USES_DAY = "light_uses_day"
        private const val KEY_LIGHT_USES_COUNT = "light_uses_count"
        private const val KEY_STREAK_DAYS = "streak_days"
        private const val KEY_LAST_ACTION_DAY = "last_action_day"

        /**
         * Today as an epoch day in the device's own zone. Local, not UTC: the
         * streak has to break when the user's calendar says a day was missed,
         * not when a clock in London does.
         */
        private fun today(): Long = LocalDate.now().toEpochDay()
    }
}
