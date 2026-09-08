package com.sese.keepix.ui.neu

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sese.keepix.core.logic.FREE_LIGHT_USES_PER_DAY
import com.sese.keepix.core.logic.QualityTier
import com.sese.keepix.core.logic.RetentionWindow

/**
 * The You tab. The header gear is gone -- settings live only here (addendum
 * decision: one settings entry).
 *
 * The retention control is a snapping wheel, not a slider: the stops are not
 * evenly spaced in days (0, 1, 3, 7, 14, 30) and a slider would imply they are.
 */
@Composable
fun NeuSettingsScreen(
    retentionDays: Int,
    onRetentionChanged: (Int) -> Unit,
    tier: QualityTier,
    onTierChanged: (QualityTier) -> Unit,
    isPro: Boolean,
    lightUsesToday: Int,
    onOpenPaywall: () -> Unit,
    accountEnabled: Boolean,
    onAccountChanged: (Boolean) -> Unit,
    batchSize: Int,
    hasOnlyPartialMediaAccess: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = neu
    val window = RetentionWindow(retentionDays)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { NeuScreenTitle("Settings", Modifier.padding(top = 6.dp, bottom = 16.dp)) }

        item {
            NeuPanel {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            "Bin retention",
                            style = NeuType.sectionHeader,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (window.isSessionOnly) "Session"
                            else "$retentionDays day${if (retentionDays == 1) "" else "s"}",
                            style = NeuType.sectionHeader,
                            color = c.accent,
                        )
                    }
                    Text(
                        if (window.isSessionOnly) {
                            "Session mode: binned items are deleted the next time you open " +
                                "Keepix. Restore anything before you close it."
                        } else {
                            "Binned items are permanently deleted $retentionDays days after " +
                                "you swipe. Existing bin items keep their original date."
                        },
                        style = NeuType.body,
                        color = c.textSecondary,
                        modifier = Modifier.heightIn(min = 38.dp).padding(bottom = 16.dp),
                    )
                    RetentionWheel(retentionDays, onRetentionChanged)
                }
            }
        }

        item {
            NeuPanel {
                Column {
                    Text(
                        "Compression strength",
                        style = NeuType.sectionHeader,
                        color = c.textPrimary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Text(
                        if (isPro) {
                            "Applied to every shrink swipe. Originals are replaced once the " +
                                "new file is verified."
                        } else {
                            "Light is free. Balanced and Max come with Pro."
                        },
                        style = NeuType.body,
                        color = c.textSecondary,
                        modifier = Modifier.padding(bottom = 15.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        QualityTier.entries.forEach { option ->
                            val locked = option.proOnly && !isPro
                            val active = option == tier
                            Column(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = NeuTouchTarget)
                                    .then(
                                        if (active) {
                                            Modifier.neuInset(
                                                RoundedCornerShape(16.dp),
                                                offset = 10.dp, blur = 20.dp, strong = true,
                                            )
                                        } else {
                                            Modifier.neuExtruded(
                                                RoundedCornerShape(16.dp), offset = 5.dp, blur = 10.dp,
                                            )
                                        }
                                    )
                                    .clickable {
                                        if (locked) onOpenPaywall() else onTierChanged(option)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 15.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    option.label,
                                    style = NeuType.itemName,
                                    color = when {
                                        locked -> c.textSecondary
                                        active -> c.accent
                                        else -> c.textPrimary
                                    },
                                )
                                Text(
                                    if (locked) "PRO" else option.savingsTag,
                                    style = NeuType.microBadge,
                                    color = c.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isPro) {
            item {
                val left = (FREE_LIGHT_USES_PER_DAY - lightUsesToday).coerceAtLeast(0)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .neuExtruded(RoundedCornerShape(32.dp), offset = 9.dp, blur = 16.dp, clipContent = false)
                        .background(c.accent, RoundedCornerShape(32.dp))
                        .clickable(onClick = onOpenPaywall)
                        .padding(22.dp),
                ) {
                    Text("Keepix Pro", style = NeuType.screenTitle, color = c.onAccent)
                    Text(
                        "Unlimited shrinking, Balanced & Max strength. " +
                            if (left > 0) "$left free shrink${if (left == 1) "" else "s"} left today."
                            else "Free shrinks used up.",
                        style = NeuType.body,
                        color = c.onAccent.copy(alpha = 0.82f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuExtruded(RoundedCornerShape(24.dp), offset = 5.dp, blur = 10.dp)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Friends & leaderboard", style = NeuType.itemName, color = c.textPrimary)
                    Text(
                        if (accountEnabled) "Signed in · syncing weekly count only"
                        else "Off · Keepix stays fully offline",
                        style = NeuType.metadata,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                NeuSwitch(accountEnabled, onAccountChanged)
            }
        }

        item {
            InfoRow("Sort order", "Date taken · newest first")
        }
        item {
            InfoRow(
                "Batch size",
                "$batchSize cards, next batch preloads at card ${(batchSize * 4) / 5}",
            )
        }
        if (hasOnlyPartialMediaAccess) {
            item {
                InfoRow(
                    "Photo access",
                    "Keepix can only see the photos you selected. Grant full access in " +
                        "Android Settings to sort everything.",
                )
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, note: String) {
    val c = neu
    Column(
        Modifier
            .fillMaxWidth()
            .neuExtruded(RoundedCornerShape(24.dp), offset = 5.dp, blur = 10.dp)
            .padding(18.dp),
    ) {
        Text(title, style = NeuType.itemName, color = c.textPrimary)
        Text(note, style = NeuType.metadata, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The neumorphic switch: a carved track with a raised knob. The track is the
 * one control allowed to take the accent as a fill, because "on" has no other
 * expression here -- the knob's own depth is unchanged between states.
 */
@Composable
private fun NeuSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = neu
    val knobX by animateDpAsState(if (checked) 29.dp else 3.dp, label = "knob")
    Box(
        Modifier
            .size(width = 56.dp, height = 30.dp)
            .neuInset(CircleShape, offset = 3.dp, blur = 6.dp, clipContent = false)
            .background(if (checked) c.accent else c.surface, CircleShape)
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(24.dp)
                .neuExtruded(CircleShape, offset = 3.dp, blur = 6.dp),
        )
    }
}

/** Height of one wheel row. Three rows are visible; the middle one is selected. */
private val WheelRow = 42.dp

/**
 * A snapping vertical picker over [RetentionWindow.STOPS].
 *
 * Selection is whatever has come to rest in the highlighted slot, so the
 * committed value follows the scroll -- there is no separate confirm. Built on
 * LazyColumn + a snap fling rather than a custom gesture: the platform already
 * owns the physics and this is one control.
 */
@Composable
private fun RetentionWheel(retentionDays: Int, onChange: (Int) -> Unit) {
    val c = neu
    val stops = RetentionWindow.STOPS
    val selectedIndex = stops.indexOfFirst { it == retentionDays }
        .let { if (it >= 0) it else stops.indexOf(RetentionWindow.fromStoredDays(retentionDays).days) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))

    // The list is padded by exactly one row top and bottom, so firstVisibleItem
    // IS the row sitting in the highlighted slot once the fling has snapped.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                if (!scrolling) stops.getOrNull(index)?.let { if (it != retentionDays) onChange(it) }
            }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .neuInset(RoundedCornerShape(20.dp), offset = 6.dp, blur = 12.dp, strong = true)
            .padding(horizontal = 10.dp),
    ) {
        // The raised slot marking the committed row, drawn under the list.
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .offset(y = WheelRow)
                .fillMaxWidth()
                .height(WheelRow)
                .neuExtruded(RoundedCornerShape(13.dp), offset = 4.dp, blur = 9.dp),
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier.fillMaxWidth().height(WheelRow * 3),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = WheelRow),
        ) {
            items(stops.size) { i ->
                val value = stops[i]
                val selected = value == retentionDays
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WheelRow)
                        .clickable { onChange(value) }
                        .alpha(if (selected) 1f else 0.5f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (value == 0) "Session only" else "$value day${if (value == 1) "" else "s"}",
                        style = NeuType.sectionHeader.copy(
                            fontSize = if (selected) 20.sp else 17.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        ),
                        color = if (selected) c.accent else c.textSecondary,
                    )
                }
            }
        }
    }
}
