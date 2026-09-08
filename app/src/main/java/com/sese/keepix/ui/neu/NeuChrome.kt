package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Chrome shared by every tab: the header strip, the bottom tab bar, the toast,
 * and the button shapes the prototypes reuse everywhere.
 *
 * Extracted rather than copied per screen because the prototype draws the
 * header ONCE, above the tab switch -- so the streak chip and the library
 * button must not jump by a pixel when the tab changes.
 */

/** The five tabs, in prototype order. Glyphs are text, not icon assets. */
enum class NeuTab(val label: String, val glyph: String) {
    SWIPE("Swipe", "→"),
    BIN("Bin", "✕"),
    SMALL("Small", "↓"),
    RANK("Rank", "◆"),
    YOU("You", "⚙"),
}

/**
 * Minimum height for anything tappable. The prototype sets `min-height:44px` on
 * every button for the same reason.
 */
val NeuTouchTarget = 44.dp

@Composable
fun NeuHeader(
    streakDays: Int,
    queuedCount: Int,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = neu
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Inset, not extruded: the streak chip is carved, per the prototype.
        Row(
            Modifier
                .neuInset(CircleShape, offset = 3.dp, blur = 6.dp)
                .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("◆", style = NeuType.buttonLabel, color = c.accent)
            Text("$streakDays", style = NeuType.sectionHeader, color = c.textPrimary)
            Text("day streak", style = NeuType.metadata, color = c.textSecondary)
        }

        Box(Modifier.weight(1f))

        if (queuedCount > 0) {
            Row(
                Modifier
                    .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(c.accent, CircleShape))
                Text("Shrinking $queuedCount", style = NeuType.buttonLabel, color = c.textPrimary)
            }
        }

        trailing()

        // 2x2 grid of 5dp squares with 3dp gaps -- CSS shapes in the prototype,
        // so there is no icon asset to port.
        Box(
            Modifier
                .size(NeuTouchTarget)
                .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp)
                .clickable(onClick = onOpenLibrary),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .background(c.textSecondary, RoundedCornerShape(1.5.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bottom bar. The active tab's glyph well is the deepest inset in the whole
 * system (10/20) -- that depth is the only thing marking selection, since
 * nothing here changes background colour.
 */
@Composable
fun NeuTabBar(selected: NeuTab, onSelect: (NeuTab) -> Unit, modifier: Modifier = Modifier) {
    val c = neu
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
            .neuExtruded(RoundedCornerShape(24.dp), offset = 9.dp, blur = 16.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NeuTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = NeuTouchTarget)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .then(
                            if (active) {
                                Modifier.neuInset(
                                    RoundedCornerShape(12.dp), offset = 10.dp, blur = 20.dp,
                                    strong = true,
                                )
                            } else {
                                Modifier.neuExtruded(
                                    RoundedCornerShape(12.dp), offset = 5.dp, blur = 10.dp,
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.glyph,
                        style = NeuType.buttonLabel,
                        color = if (active) c.accent else c.textSecondary,
                    )
                }
                Text(
                    tab.label,
                    style = NeuType.navLabel,
                    color = if (active) c.textPrimary else c.textSecondary,
                )
            }
        }
    }
}

/** Toast kinds, which differ only by the colour of the leading dot. */
enum class NeuToastKind { OK, DELETED, COMPRESSED }

@Composable
fun NeuToast(
    message: String,
    kind: NeuToastKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = neu
    Row(
        modifier
            .fillMaxWidth()
            .neuExtruded(RoundedCornerShape(20.dp), offset = 12.dp, blur = 20.dp, strong = true)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        val dot = when (kind) {
            NeuToastKind.OK -> c.teal
            NeuToastKind.DELETED -> c.clayGlyph
            NeuToastKind.COMPRESSED -> c.accent
        }
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Text(message, style = NeuType.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            NeuTextButton(
                actionLabel, onAction, color = c.accent,
                shape = RoundedCornerShape(12.dp), paddingH = 13.dp, paddingV = 9.dp,
            )
        }
    }
}

/** Screen title, e.g. "Bin" / "Compressed". */
@Composable
fun NeuScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NeuType.screenTitle, color = neu.textPrimary, modifier = modifier)
}

/**
 * Extruded surface button with a coloured label. Pressing it does not change
 * its background -- only [selected] does, by flipping it to an inset of the
 * same colour, which is the one state cue this system allows.
 */
@Composable
fun NeuTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = neu.textPrimary,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    offset: Dp = 4.dp,
    blur: Dp = 9.dp,
    paddingH: Dp = 15.dp,
    paddingV: Dp = 12.dp,
) {
    Box(
        modifier
            .heightIn(min = NeuTouchTarget)
            .then(
                if (selected) Modifier.neuInset(shape, offset = offset, blur = blur)
                else Modifier.neuExtruded(shape, offset = offset, blur = blur)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = paddingH, vertical = paddingV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = NeuType.buttonLabel,
            textAlign = TextAlign.Center,
            // Disabled drops the label to textSecondary and keeps the extrusion,
            // rather than greying the surface -- the surface never changes.
            color = if (enabled) color else neu.textSecondary,
        )
    }
}

/**
 * The accent-filled CTA. The one place in the system where a control carries a
 * colour of its own; the shadow pair stays the surface's, so it still reads as
 * moulded out of the same sheet.
 */
@Composable
fun NeuAccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    val c = neu
    Box(
        modifier
            .heightIn(min = NeuTouchTarget)
            .neuExtruded(shape, offset = 9.dp, blur = 16.dp, clipContent = false)
            .background(if (enabled) c.accent else c.surface, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = NeuType.sectionHeader,
            textAlign = TextAlign.Center,
            color = if (enabled) c.onAccent else c.textSecondary,
        )
    }
}

/** A raised panel: the 32dp-radius 9/16 card the prototypes use for every section. */
@Composable
fun NeuPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 32.dp,
    padding: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .neuExtruded(RoundedCornerShape(radius), offset = 9.dp, blur = 16.dp)
            .padding(padding),
    ) { content() }
}

/** Section overline: 10sp, 1.6sp tracking, upper case. */
@Composable
fun NeuOverline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = NeuType.overline,
        color = neu.textSecondary,
        modifier = modifier,
    )
}

/** The carved circular medallion behind a glyph, used on every empty/hero state. */
@Composable
fun NeuMedallion(
    glyph: String,
    glyphColor: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 56.dp,
) {
    Box(
        modifier
            .size(diameter)
            .neuInset(CircleShape, offset = 8.dp, blur = 15.dp, strong = true),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = NeuType.screenTitle, color = glyphColor)
    }
}

/** Sheet grab handle: a 46x5 carved pill. */
@Composable
fun NeuGrabHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 46.dp, height = 5.dp)
            .neuInset(CircleShape, offset = 2.dp, blur = 4.dp),
    )
}
