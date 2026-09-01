package com.sese.keepix.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.ui.theme.BadgeOrange
import com.sese.keepix.ui.theme.BadgeRedUrgent
import com.sese.keepix.ui.theme.TextSecondary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `getBadgeText`/`getBadgeColor` in RecycleBinScreen.kt are private top-level
 * functions (file-private in Kotlin, compiled to `private static` methods on
 * the file's facade class `RecycleBinScreenKt`) -- there is no public seam to
 * call them directly, and adding one is out of scope (production code under
 * app/src/main is not to be changed for this task). Reflection with
 * `isAccessible = true` calls the actual compiled method bodies (not a
 * reimplementation), which is the only way to pin their four-case behaviour
 * without also standing up a full Compose test environment.
 */
class RecycleBinBadgeTest {

    private val facadeClass = Class.forName("com.sese.keepix.ui.RecycleBinScreenKt")

    private fun getBadgeText(item: BinItemEntity): String {
        val m = facadeClass.getDeclaredMethod("getBadgeText", BinItemEntity::class.java)
        m.isAccessible = true
        return m.invoke(null, item) as String
    }

    /**
     * Returns the ARGB int of the real `getBadgeColor`'s result.
     *
     * `Color` is a Kotlin inline value class, so both the production
     * function and any test wrapper with a `Color`-typed return get their
     * JVM method name mangled with a signature-derived hash (e.g.
     * `getBadgeColor-vNxB06k`), and the value comes back through reflection
     * boxed as its erased carrier (`java.lang.Long`), not as a `Color`
     * object -- `as Color` fails with a ClassCastException. Reflection finds
     * the one method whose name starts with `getBadgeColor` regardless of
     * its mangled suffix, then reconstructs a real `Color` from the raw bits
     * via its declared (long) constructor so it can be compared through the
     * ordinary public `toArgb()` API.
     */
    private fun getBadgeColorArgb(item: BinItemEntity): Int {
        val m = facadeClass.declaredMethods.single { it.name.startsWith("getBadgeColor") }
        m.isAccessible = true
        val raw = m.invoke(null, item) as Long
        val ctor = Color::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
        ctor.isAccessible = true
        return (ctor.newInstance(raw) as Color).toArgb()
    }

    private fun timedItem(remaining: Long) = BinItemEntity(
        mediaId = 1L,
        mediaUri = "content://media/1",
        retentionMode = "TIMED",
        expiryAt = System.currentTimeMillis() + remaining
    )

    private fun sessionItem() = BinItemEntity(
        mediaId = 1L,
        mediaUri = "content://media/1",
        retentionMode = "SESSION"
    )

    // --- session mode ---

    @Test
    fun `session mode badge text is Deletes on reopen regardless of expiry`() {
        assertEquals("Deletes on reopen", getBadgeText(sessionItem()))
    }

    @Test
    fun `session mode badge color is BadgeOrange`() {
        assertEquals(BadgeOrange.toArgb(), getBadgeColorArgb(sessionItem()))
    }

    // --- under 24h ---

    @Test
    fun `under 24h shows less-than-1-day text and urgent red`() {
        val item = timedItem(TimeUnit.HOURS.toMillis(5))
        assertEquals("< 1 day left", getBadgeText(item))
        assertEquals(BadgeRedUrgent.toArgb(), getBadgeColorArgb(item))
    }

    @Test
    fun `expired timed item shows Expired`() {
        val item = timedItem(-TimeUnit.HOURS.toMillis(1))
        assertEquals("Expired", getBadgeText(item))
    }

    // --- under 3 days (but over 1 day) ---

    @Test
    fun `exactly 1 day remaining shows 1 day left and urgent red`() {
        val item = timedItem(TimeUnit.DAYS.toMillis(1) + TimeUnit.MINUTES.toMillis(5))
        assertEquals("1 day left", getBadgeText(item))
        // getBadgeColor buckets days<=1 as urgent red -- 1 day left is still red.
        assertEquals(BadgeRedUrgent.toArgb(), getBadgeColorArgb(item))
    }

    @Test
    fun `2 days remaining shows Nd left text and orange color`() {
        val item = timedItem(TimeUnit.DAYS.toMillis(2) + TimeUnit.HOURS.toMillis(1))
        assertEquals("2d left", getBadgeText(item))
        assertEquals(BadgeOrange.toArgb(), getBadgeColorArgb(item))
    }

    // --- over 3 days ---

    @Test
    fun `over 3 days remaining shows Nd left text and secondary text color`() {
        val item = timedItem(TimeUnit.DAYS.toMillis(5))
        assertEquals("5d left", getBadgeText(item))
        assertEquals(TextSecondary.toArgb(), getBadgeColorArgb(item))
    }

    @Test
    fun `exactly 3 days remaining is still orange not secondary`() {
        val item = timedItem(TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(1))
        assertEquals(BadgeOrange.toArgb(), getBadgeColorArgb(item))
    }
}
