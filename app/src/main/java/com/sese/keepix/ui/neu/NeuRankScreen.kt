package com.sese.keepix.ui.neu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sese.keepix.core.logic.STREAK_DAYS_PER_FREE_MAX

/**
 * The Rank tab: the streak ring, what it is worth, and the friends card.
 *
 * The prototype shows a five-person leaderboard. There is no account backend in
 * this build and inventing names would put fabricated data in front of the user
 * as if it were theirs, so the list renders only what is real: the signed-out
 * state, or the user's own row once sync is on. Everything else on the screen
 * is the prototype's.
 *
 * ponytail: no leaderboard rows until there is a service to fetch them from.
 */
@Composable
fun NeuRankScreen(
    streakDays: Int,
    weeklySwipes: Int,
    accountEnabled: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = neu
    val full = streakDays >= STREAK_DAYS_PER_FREE_MAX
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 8.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item { NeuScreenTitle("This week", Modifier.padding(top = 6.dp, bottom = 14.dp)) }

        item {
            NeuPanel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    StreakRing(streakDays)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (full) "A full week — Max shrink unlocked"
                            else "One more day for a free Max shrink",
                            style = NeuType.sectionHeader,
                            color = c.textPrimary,
                        )
                        Text(
                            if (full) "Claim it on any card over 20 MB."
                            else "Every 7-day streak earns one Max compression.",
                            style = NeuType.metadata,
                            color = c.textSecondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuExtruded(RoundedCornerShape(24.dp), offset = 5.dp, blur = 10.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("1", style = NeuType.sectionHeader, color = c.accent)
                Box(
                    Modifier.size(36.dp).background(c.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Y", style = NeuType.itemName, color = c.onAccent)
                }
                Text("You", style = NeuType.itemName, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    // "this session", not "this week": the count restarts with
                    // the process (see KeepixViewModel.weeklySwipes), and a bare
                    // "0 swipes" beside a live streak reads as a bug.
                    "$weeklySwipes this session",
                    style = NeuType.metadata,
                    color = c.textSecondary,
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
                    .neuInset(RoundedCornerShape(24.dp), offset = 6.dp, blur = 10.dp)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (accountEnabled) "Friends & leaderboard" else "Invite a friend",
                        style = NeuType.itemName,
                        color = c.textPrimary,
                    )
                    Text(
                        if (accountEnabled) {
                            "Signed in — only your weekly count is shared."
                        } else {
                            "Turn on friends in You to compare weekly counts. Only the count is shared."
                        },
                        style = NeuType.metadata,
                        color = c.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                NeuTextButton(
                    label = if (accountEnabled) "Manage" else "Turn on",
                    onClick = onOpenSettings,
                    color = c.accent,
                    shape = RoundedCornerShape(12.dp),
                    offset = 5.dp, blur = 10.dp,
                    paddingH = 13.dp, paddingV = 10.dp,
                )
            }
        }
    }
}

/**
 * The conic streak ring. Drawn rather than composed: the prototype's
 * `conic-gradient` has no Compose equivalent, and an arc stroke at the same
 * radius is visually identical at this size.
 */
@Composable
private fun StreakRing(streakDays: Int) {
    val c = neu
    val fraction = (streakDays.toFloat() / STREAK_DAYS_PER_FREE_MAX).coerceIn(0f, 1f)
    Box(
        Modifier
            .size(76.dp)
            .neuExtruded(CircleShape, offset = 5.dp, blur = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawArc(
                color = c.accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
        Column(
            Modifier
                .padding(5.dp)
                .fillMaxSize()
                .neuInset(CircleShape, offset = 6.dp, blur = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("$streakDays", style = NeuType.screenTitle, color = c.textPrimary)
            Text("DAYS", style = NeuType.microBadge, color = c.textSecondary)
        }
    }
}
