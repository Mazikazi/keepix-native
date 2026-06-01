package com.sese.keepix.data

import android.content.Context
import android.content.SharedPreferences
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

    fun generateNewSession(): String {
        val newId = UUID.randomUUID().toString()
        val previousId = lastSessionId
        lastSessionId = newId
        return previousId
    }

    val currentSessionId: String
        get() = lastSessionId

    val isSessionMode: Boolean
        get() = retentionDays == 0

    fun getExpiryTimestamp(): Long {
        if (retentionDays == 0) return 0L
        return System.currentTimeMillis() + (retentionDays * 24L * 60L * 60L * 1000L)
    }

    companion object {
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_LAST_SESSION_ID = "last_session_id"
        private const val KEY_FULLSCREEN_TUTORIAL_COMPLETE = "fullscreen_tutorial_complete"
    }
}
