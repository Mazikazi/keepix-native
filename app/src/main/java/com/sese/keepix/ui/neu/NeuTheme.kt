package com.sese.keepix.ui.neu

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sese.keepix.R

/**
 * Neumorphic tokens, taken verbatim from the two authoritative prototypes in
 * docs/design/. Every surface in the app is molded from [surface]; depth comes
 * only from the paired shadows below. There are no card fills and no borders,
 * so MaterialTheme.colorScheme cannot express this -- route colours through
 * [LocalNeuColors] instead.
 *
 * The dark theme is NOT a hue inversion. Light is a balanced pair (60% dark /
 * 50% light); dark is near-black drops with ~4% white lift. Get that ratio
 * wrong and dark neumorphism reads as flat grey cards.
 */
@Immutable
data class NeuColors(
    val surface: Color,
    /** Down-right shadow of the standard pair. */
    val shadowDark: Color,
    /** Up-left shadow of the standard pair. */
    val shadowLight: Color,
    /** Deeper pair, for hero cards and deep photo wells (IN_DEEP in the prototypes). */
    val shadowDarkStrong: Color,
    val shadowLightStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val teal: Color,
    /** KEEP stamp text on the drag overlay -- identical in both themes in the prototypes. */
    val tealDeep: Color,
    /** Destructive. Deliberately cool-grey, never red -- red tears a hole in the single-material illusion. */
    val clay: Color,
    /** Brighter clay for glyphs and toast dots (the prototypes use two distinct clays). */
    val clayGlyph: Color,
    val inactiveDot: Color,
    val favorite: Color,
    val onAccent: Color = Color.White,
)

val NeuLightColors = NeuColors(
    surface = Color(0xFFE0E5EC),
    shadowDark = Color(0x99A3B1C6),         // rgba(163,177,198,0.60)
    shadowLight = Color(0x80FFFFFF),        // rgba(255,255,255,0.50)
    shadowDarkStrong = Color(0xB3A3B1C6),   // rgba(163,177,198,0.70)
    shadowLightStrong = Color(0x99FFFFFF),  // rgba(255,255,255,0.60)
    textPrimary = Color(0xFF3D4852),
    textSecondary = Color(0xFF6B7280),
    accent = Color(0xFF6C63FF),
    teal = Color(0xFF38B2AC),
    tealDeep = Color(0xFF2C8C87),
    clay = Color(0xFFA9524A),
    clayGlyph = Color(0xFFC4665C),
    inactiveDot = Color(0xFFA3B1C6),
    favorite = Color(0xFFE0698A),
)

val NeuDarkColors = NeuColors(
    surface = Color(0xFF262A33),
    shadowDark = Color(0x91000000),         // rgba(0,0,0,0.57)
    shadowLight = Color(0x0BFFFFFF),        // rgba(255,255,255,0.043)
    shadowDarkStrong = Color(0xA8000000),   // rgba(0,0,0,0.66)
    shadowLightStrong = Color(0x0DFFFFFF),  // rgba(255,255,255,0.051)
    textPrimary = Color(0xFFE7EBF2),
    textSecondary = Color(0xFF9AA3B2),
    accent = Color(0xFF8C84FF),
    teal = Color(0xFF4FD1C5),
    tealDeep = Color(0xFF2C8C87),
    clay = Color(0xFFD98A80),
    clayGlyph = Color(0xFFE09A90),
    inactiveDot = Color(0xFF525B6B),
    favorite = Color(0xFFF08BA6),
)

val LocalNeuColors = staticCompositionLocalOf { NeuLightColors }

/**
 * Bundled, not downloadable -- the deck's first paint must not wait on a fetch.
 *
 * ponytail: only the four Plus Jakarta and three DM Sans weights the design
 * actually calls for are in res/font. Add a weight there and here when a screen
 * needs one; the rest of both families stays out of the APK.
 * Licences: app/src/main/assets/licenses/ (OFL requires they ship with the font).
 */
object NeuType {
    val Display = FontFamily(
        Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
        Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
        Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
    )
    val Body = FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_medium, FontWeight.Medium),
        Font(R.font.dm_sans_bold, FontWeight.Bold),
    )

    val screenTitle = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.ExtraBold,
        fontSize = 23.sp, lineHeight = 26.sp, letterSpacing = (-0.69).sp,
    )
    val sectionHeader = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.ExtraBold,
        fontSize = 15.sp, letterSpacing = (-0.3).sp,
    )
    val itemName = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp, lineHeight = 17.sp,
    )
    val body = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 21.sp,
    )
    val metadata = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 16.sp,
    )
    val buttonLabel = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    val overline = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 1.6.sp,
    )
    val microBadge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    val navLabel = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 9.5.sp)
}

@Composable
fun NeuTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNeuColors provides if (dark) NeuDarkColors else NeuLightColors,
        content = content,
    )
}

/** Shorthand for [LocalNeuColors].current. */
val neu: NeuColors
    @Composable @ReadOnlyComposable get() = LocalNeuColors.current
