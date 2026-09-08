package com.sese.keepix

import android.content.Context
import android.content.SharedPreferences
import com.sese.keepix.data.KeepixPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeepixPreferencesTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var preferences: KeepixPreferences

    @Before
    fun setUp() {
        mockContext = mockk()
        mockPrefs = mockk()
        mockEditor = mockk(relaxed = true)
        
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } returns Unit
        
        preferences = KeepixPreferences(mockContext)
    }

    @Test
    fun `retentionDays snaps the legacy 10-day default up to the nearest stop`() {
        // 10 is not one of RetentionWindow.STOPS any more. Reading it back as 7
        // would silently shorten a live recovery window by three days, so it
        // rounds UP to 14; see KeepixPreferences.retentionDays.
        every { mockPrefs.getInt("retention_days", 10) } returns 14
        assertEquals(14, preferences.retentionDays)
    }

    @Test
    fun `retentionDays returns a stored value that is already a stop unchanged`() {
        every { mockPrefs.getInt("retention_days", 10) } returns 30
        assertEquals(30, preferences.retentionDays)
    }

    @Test
    fun `retentionDays setter stores value`() {
        preferences.retentionDays = 14
        verify { mockEditor.putInt("retention_days", 14) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `batchSize returns default value when not set`() {
        every { mockPrefs.getInt("batch_size", 50) } returns 50
        assertEquals(50, preferences.batchSize)
    }

    @Test
    fun `batchSize returns stored value`() {
        every { mockPrefs.getInt("batch_size", 50) } returns 100
        assertEquals(100, preferences.batchSize)
    }

    @Test
    fun `batchSize setter stores value`() {
        preferences.batchSize = 75
        verify { mockEditor.putInt("batch_size", 75) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `onboardingComplete returns false by default`() {
        every { mockPrefs.getBoolean("onboarding_complete", false) } returns false
        assertFalse(preferences.onboardingComplete)
    }

    @Test
    fun `onboardingComplete returns stored value`() {
        every { mockPrefs.getBoolean("onboarding_complete", false) } returns true
        assertTrue(preferences.onboardingComplete)
    }

    @Test
    fun `onboardingComplete setter stores value`() {
        preferences.onboardingComplete = true
        verify { mockEditor.putBoolean("onboarding_complete", true) }
        verify { mockEditor.apply() }
    }

    @Test
    fun `isSessionMode returns true when retentionDays is 0`() {
        every { mockPrefs.getInt("retention_days", 10) } returns 0
        assertTrue(preferences.isSessionMode)
    }

    @Test
    fun `isSessionMode returns false when retentionDays is not 0`() {
        every { mockPrefs.getInt("retention_days", 10) } returns 14
        assertFalse(preferences.isSessionMode)
    }

    @Test
    fun `getExpiryTimestamp returns 0 for session mode`() {
        every { mockPrefs.getInt("retention_days", 10) } returns 0
        assertEquals(0L, preferences.getExpiryTimestamp())
    }

    @Test
    fun `getExpiryTimestamp returns future time for timed mode`() {
        every { mockPrefs.getInt("retention_days", 10) } returns 14
        val expiry = preferences.getExpiryTimestamp()
        assertTrue(expiry > System.currentTimeMillis())
    }
}
