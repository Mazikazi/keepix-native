package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sese.keepix.core.logic.FREE_LIGHT_USES_PER_DAY
import com.sese.keepix.core.logic.RetentionWindow
import com.sese.keepix.core.logic.STREAK_DAYS_PER_FREE_MAX

/**
 * The three screens that sit outside the tab bar: permission, onboarding, and
 * the paywall sheet. Grouped in one file because none of them holds state and
 * all three are one column of panels.
 */

@Composable
fun NeuPermissionScreen(
    onRequestPermission: () -> Unit,
    isPermanentlyDenied: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    Box(modifier.fillMaxSize().background(c.surface)) {
        // Three empty cards fanned out: the deck, before it has anything in it.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp, start = 22.dp, end = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            listOf(-7f, 0f, 7f).forEachIndexed { i, angle ->
                val hero = i == 1
                Box(
                    Modifier
                        .width(78.dp)
                        .height(104.dp)
                        .graphicsLayer { rotationZ = angle }
                        .neuExtruded(
                            RoundedCornerShape(16.dp),
                            offset = if (hero) 9.dp else 5.dp,
                            blur = if (hero) 16.dp else 10.dp,
                        ),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 30.dp),
        ) {
            Text(
                "Let's clear some space together",
                style = NeuType.screenTitle,
                color = c.textPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                "Keepix needs to see your photos and videos to show them as cards. " +
                    "Nothing leaves your phone, and nothing is deleted without your say.",
                style = NeuType.body,
                color = c.textSecondary,
                modifier = Modifier.padding(bottom = 22.dp),
            )
            NeuPanel(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CheckRow("Photos & video — read access")
                    CheckRow("Write only when you restore or delete")
                }
            }
            Spacer(Modifier.height(20.dp))
            NeuAccentButton(
                label = if (isPermanentlyDenied) "Open Settings" else "Allow access",
                onClick = if (isPermanentlyDenied) onOpenSettings else onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPermanentlyDenied) {
                Text(
                    "Keepix was denied photo access. Android only lets Settings undo that.",
                    style = NeuType.metadata,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckRow(label: String) {
    val c = neu
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(34.dp).neuInset(CircleShape, offset = 6.dp, blur = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = NeuType.buttonLabel, color = c.teal)
        }
        Text(label, style = NeuType.body, color = c.textPrimary)
    }
}

@Composable
fun NeuOnboardingScreen(
    retentionDays: Int,
    batchSize: Int,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    val window = RetentionWindow(retentionDays)
    Column(
        modifier
            .fillMaxSize()
            .background(c.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        NeuOverline("One quick thing")
        Text(
            "Three directions. That's the whole app.",
            style = NeuType.screenTitle,
            color = c.textPrimary,
        )
        GestureRow("→", c.teal, "Swipe right to keep", "Nothing happens to the file.")
        GestureRow(
            "←", c.clayGlyph, "Swipe left to bin",
            if (window.isSessionOnly) {
                "Sits in the bin until you close Keepix. Restore anytime."
            } else {
                "Sits in the bin for $retentionDays day${if (retentionDays == 1) "" else "s"}. Restore anytime."
            },
        )
        GestureRow(
            "↓", c.accent, "Swipe down to shrink",
            "Keeps the photo, compresses it in the background.",
        )
        NeuAccentButton(
            label = "Start with $batchSize photos",
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@Composable
private fun GestureRow(glyph: String, glyphColor: Color, title: String, note: String) {
    val c = neu
    Row(
        Modifier
            .fillMaxWidth()
            .neuExtruded(RoundedCornerShape(32.dp), offset = 9.dp, blur = 16.dp)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NeuMedallion(glyph, glyphColor, diameter = 48.dp)
        Column {
            Text(title, style = NeuType.sectionHeader, color = c.textPrimary)
            Text(
                note,
                style = NeuType.metadata,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The Pro sheet.
 *
 * [onSubscribe] is deliberately not wired to a purchase in this build -- there
 * is no billing client yet, and a button that pretends to charge is worse than
 * one that says it is coming. The host passes a no-op and the sheet says so.
 */
@Composable
fun NeuPaywallSheet(
    streakDays: Int,
    onDismiss: () -> Unit,
    onSubscribe: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = neu
    Box(
        modifier
            .fillMaxSize()
            .background(c.surface.copy(alpha = 0.86f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Swallows the scrim's dismiss click; the sheet itself is not a
                // dismiss target.
                .clickable(enabled = false) {}
                .neuExtruded(
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                    offset = 12.dp, blur = 20.dp, strong = true,
                )
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 28.dp),
        ) {
            NeuGrabHandle(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp))
            Text(
                "You've used your $FREE_LIGHT_USES_PER_DAY free shrinks",
                style = NeuType.screenTitle,
                color = c.textPrimary,
            )
            Text(
                "Pro keeps shrinking without limits — and unlocks Balanced and Max " +
                    "strength for the big ones.",
                style = NeuType.body,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(
                Modifier.padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                listOf(
                    "Unlimited background compression",
                    "Balanced & Max strength — up to 82% smaller",
                    "Originals kept until you say otherwise",
                ).forEach { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .neuExtruded(RoundedCornerShape(20.dp), offset = 5.dp, blur = 10.dp)
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Box(
                            Modifier.size(30.dp).neuInset(CircleShape, offset = 3.dp, blur = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", style = NeuType.buttonLabel, color = c.teal)
                        }
                        Text(line, style = NeuType.body, color = c.textPrimary)
                    }
                }
            }
            if (onSubscribe != null) {
                NeuAccentButton("Try Pro free for 7 days", onSubscribe, Modifier.fillMaxWidth())
                Text(
                    "then \$2.49/mo · cancel anytime",
                    style = NeuType.metadata,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            } else {
                Text(
                    "Pro isn't on sale yet — this build has no billing. Light stays free " +
                        "and unlimited tomorrow.",
                    style = NeuType.body,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NeuTextButton(
                label = "Keep swiping without it",
                onClick = onDismiss,
                color = c.textSecondary,
                shape = RoundedCornerShape(16.dp),
                offset = 5.dp, blur = 10.dp, paddingV = 15.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                if (streakDays >= STREAK_DAYS_PER_FREE_MAX) {
                    "◆ Streak reward ready: 1 free Max shrink"
                } else {
                    val left = STREAK_DAYS_PER_FREE_MAX - streakDays
                    "◆ $left more day${if (left == 1) "" else "s"} of swiping = 1 free Max shrink"
                },
                style = NeuType.buttonLabel,
                color = c.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .neuInset(RoundedCornerShape(16.dp), offset = 3.dp, blur = 6.dp)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
            )
        }
    }
}
