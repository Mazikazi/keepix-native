package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The step-1 sign-off gate. Every state of the two modifiers, at every step of
 * the shadow scale, in both themes -- compare side by side against
 * docs/design/keepix-soft-ui-light.dc.html and its dark twin before any feature
 * work starts. If the modifiers are wrong here, every screen is wrong.
 */
@Composable
fun NeuStatePreview(modifier: Modifier = Modifier) {
    var dark by remember { mutableStateOf(false) }
    NeuTheme(dark = dark) {
        val c = neu
        Column(
            modifier
                .fillMaxSize()
                .background(c.surface)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Soft UI", style = NeuType.screenTitle, color = c.textPrimary)
                Box(Modifier.weight(1f))
                Pill(if (dark) "Dark" else "Light") { dark = !dark }
            }

            Section("Shadow scale - extruded") {
                Swatch("hero\n9/16", 9.dp, 16.dp, strong = true)
                Swatch("standard\n5/10", 5.dp, 10.dp)
                Swatch("small\n4/9", 4.dp, 9.dp)
            }

            Section("Shadow scale - inset") {
                Swatch("pressed\n3/6", 3.dp, 6.dp, inset = true)
                Swatch("selected\n6/12", 6.dp, 12.dp, inset = true)
                Swatch("deep well\n10/20", 10.dp, 20.dp, inset = true, strong = true)
            }

            Section("Interaction states") {
                StateTile("rest", c.textPrimary)
                StateTile("pressed", c.textPrimary, forceInset = true)
                StateTile("disabled", c.textSecondary)
            }

            Text(
                "Press any tile above - nothing changes colour, it presses in.",
                style = NeuType.metadata,
                color = c.textSecondary,
            )

            // The deck's action triad: 62 / 46 / 56 / 62. Shrink is smaller than
            // its neighbours and the only filled control, so it reads as the one
            // deliberate action among three.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("✕", 62.dp, c.clayGlyph)
                Glyph("♡", 46.dp, c.textSecondary)
                Box(
                    Modifier
                        .size(56.dp)
                        .neuExtruded(CircleShape)
                        .background(c.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("↓", style = NeuType.itemName, color = c.onAccent) }
                Glyph("✓", 62.dp, c.teal)
            }

            // Bottom nav: 34dp tiles at 12dp radius. The active tab is the
            // DEEPEST inset in the system (10/20) - carved, not merely pushed.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val tabs = listOf(
                    Triple("→", "Swipe", true),
                    Triple("✕", "Bin", false),
                    Triple("↓", "Small", false),
                    Triple("◆", "Rank", false),
                    Triple("⚙", "You", false),
                )
                tabs.forEach { (glyph, label, active) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .let {
                                    if (active) {
                                        it.neuInset(RoundedCornerShape(12.dp), 10.dp, 20.dp, strong = true)
                                    } else {
                                        it.neuExtruded(RoundedCornerShape(12.dp), 4.dp, 9.dp)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                glyph,
                                style = NeuType.buttonLabel,
                                color = if (active) c.accent else c.textSecondary,
                            )
                        }
                        Text(
                            label,
                            style = NeuType.navLabel,
                            color = if (active) c.textPrimary else c.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            // Accent CTAs are the one exception to "never change colour": they do
            // not invert, they move (translateY on press).
            Box(
                Modifier
                    .fillMaxWidth()
                    .neuExtruded(RoundedCornerShape(16.dp), strong = true)
                    .background(c.accent, RoundedCornerShape(16.dp))
                    .padding(17.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Allow access", style = NeuType.itemName, color = c.onAccent) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(title.uppercase(), style = NeuType.overline, color = neu.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) { content() }
    }
}

@Composable
private fun Swatch(
    label: String,
    offset: Dp,
    blur: Dp,
    inset: Boolean = false,
    strong: Boolean = false,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .size(96.dp)
            .let {
                if (inset) it.neuInset(shape, offset, blur, strong)
                else it.neuExtruded(shape, offset, blur, strong)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NeuType.microBadge, color = neu.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StateTile(label: String, labelColor: Color, forceInset: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(96.dp)
            .let {
                if (pressed || forceInset) it.neuInset(shape, 3.dp, 6.dp)
                else it.neuExtruded(shape, 5.dp, 10.dp)
            }
            .clickable(source, indication = null) {},
        contentAlignment = Alignment.Center,
    ) { Text(label, style = NeuType.buttonLabel, color = labelColor) }
}

@Composable
private fun Glyph(glyph: String, size: Dp, color: Color) {
    Box(Modifier.size(size).neuExtruded(CircleShape), contentAlignment = Alignment.Center) {
        Text(glyph, style = NeuType.screenTitle, color = color)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .neuExtruded(RoundedCornerShape(14.dp), 5.dp, 10.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
    ) { Text(label, style = NeuType.buttonLabel, color = neu.accent) }
}

@Preview(widthDp = 412, heightDp = 892, showBackground = false)
@Composable
private fun NeuStatePreviewPreview() = NeuStatePreview()
