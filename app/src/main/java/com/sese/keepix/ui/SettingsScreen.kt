package com.sese.keepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sese.keepix.BuildConfig
import com.sese.keepix.ui.components.*
import com.sese.keepix.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentRetentionDays: Int,
    binCount: Int,
    onRetentionChanged: (Int) -> Unit,
    onEmptyBin: () -> Unit,
    // True only on API 34+ when the user chose "Select photos…" instead of
    // "Allow all": the app functions normally against that reduced set (see
    // checkMediaPermission's doc in MainActivity.kt), but the swipe queue
    // will look incomplete unless the user understands why.
    hasOnlyPartialMediaAccess: Boolean = false,
    onBack: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentRetentionDays.toFloat()) }
    var showEmptyConfirmation by remember { mutableStateOf(false) }
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }

    if (showPrivacyPolicy) {
        PrivacyPolicyScreen(onBack = { showPrivacyPolicy = false })
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (hasOnlyPartialMediaAccess) {
                    Surface(
                        color = BadgeOrange.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "You've given Keepix access to a limited " +
                                "selection of photos. To see your full library, " +
                                "allow full access in system Settings.",
                            color = BadgeOrange,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // DELETION section
                Text(
                    text = "DELETION",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Retention Period",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (sliderValue.roundToInt() == 0)
                                "Session mode — deletes on next app open"
                            else
                                "${sliderValue.roundToInt()} days",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (sliderValue.roundToInt() == 0) BadgeOrange else TextSecondary,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                onRetentionChanged(sliderValue.roundToInt())
                            },
                            valueRange = 0f..365f,
                            steps = 364,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentPurple,
                                activeTrackColor = AccentPurple,
                                inactiveTrackColor = DarkSurfaceVariant
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("0", color = TextMuted, fontSize = 12.sp)
                            Text("365", color = TextMuted, fontSize = 12.sp)
                        }

                        if (sliderValue.roundToInt() == 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = BadgeOrange.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "⚠️ Photos will be permanently deleted the next time you open the app",
                                    color = BadgeOrange,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // DISPLAY section
                Text(
                    text = "DISPLAY",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sort Order", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("Newest First", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // BIN section
                Text(
                    text = "BIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Items in bin", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text("$binCount", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                        }

                        if (binCount > 0) {
                            Spacer(modifier = Modifier.height(16.dp))
                            com.sese.keepix.ui.components.GlassButton(
                                onClick = { showEmptyConfirmation = true },
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 12.dp,
                                tintColor = DeleteRed,
                                tintAlpha = 0.2f
                            ) {
                                Text("Empty Bin")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ABOUT section
                Text(
                    text = "ABOUT",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Version", color = TextPrimary)
                            Text(BuildConfig.VERSION_NAME, color = TextSecondary)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPrivacyPolicy = true },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Privacy Policy", color = TextPrimary)
                            Text("›", color = TextMuted, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    // Empty Bin confirmation dialog
    if (showEmptyConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirmation = false },
            title = { Text("Empty Bin?", color = TextPrimary) },
            text = {
                Text(
                    "Permanently delete all $binCount items? This cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyConfirmation = false
                    onEmptyBin()
                }) {
                    Text("Delete All", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
