package com.sese.keepix.ui.neu

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one thing about this palette that is easy to break by eye and
 * impossible to notice in a code review: the shadow ratio.
 *
 * Light mode is a balanced pair (60% dark / 50% light). Dark mode is dominated
 * by near-black drops with only ~4% white lift. "Fixing" the dark theme by
 * raising that white toward the light theme's value is the obvious-looking edit,
 * and it collapses dark neumorphism into flat grey cards.
 */
class NeuColorsTest {

    @Test
    fun `light theme uses a balanced shadow pair`() {
        assertEquals(0.60f, NeuLightColors.shadowDark.alpha, 0.01f)
        assertEquals(0.50f, NeuLightColors.shadowLight.alpha, 0.01f)
    }

    @Test
    fun `dark theme lift stays near four percent`() {
        assertEquals(0.57f, NeuDarkColors.shadowDark.alpha, 0.01f)
        assertTrue(
            "Dark-theme light shadow is ${NeuDarkColors.shadowLight.alpha}; it must stay " +
                "far below the light theme's 0.50 or dark neumorphism reads as flat cards.",
            NeuDarkColors.shadowLight.alpha < 0.08f,
        )
    }

    @Test
    fun `strong pair is deeper than the standard pair in both themes`() {
        for (colors in listOf(NeuLightColors, NeuDarkColors)) {
            assertTrue(colors.shadowDarkStrong.alpha > colors.shadowDark.alpha)
            assertTrue(colors.shadowLightStrong.alpha > colors.shadowLight.alpha)
        }
    }

    @Test
    fun `surfaces and accents differ between themes`() {
        assertEquals(Color(0xFFE0E5EC), NeuLightColors.surface)
        assertEquals(Color(0xFF262A33), NeuDarkColors.surface)
        assertNotEquals(NeuLightColors.accent, NeuDarkColors.accent)
    }

    /**
     * The prototypes carry two distinct clays and the prose handoff documents
     * only one, so a well-meaning cleanup would collapse them. clay is the
     * deeper text/stamp colour; clayGlyph is the brighter one used for action
     * glyphs and toast dots.
     */
    @Test
    fun `both clays survive and neither drifts into red`() {
        assertEquals(Color(0xFFA9524A), NeuLightColors.clay)
        assertEquals(Color(0xFFC4665C), NeuLightColors.clayGlyph)
        assertNotEquals(NeuDarkColors.clay, NeuDarkColors.clayGlyph)
        // Cool-grey family: red must never dominate to the point of a pure hue.
        for (clay in listOf(NeuLightColors.clay, NeuLightColors.clayGlyph)) {
            assertTrue("Clay drifted into a destructive red", clay.green > 0.25f && clay.blue > 0.25f)
        }
    }
}
