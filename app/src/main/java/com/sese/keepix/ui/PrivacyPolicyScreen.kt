package com.sese.keepix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sese.keepix.ui.components.GlassCard
import com.sese.keepix.ui.theme.*

/**
 * Renders the Keepix privacy policy (content mirrors PRIVACY.md at the repo
 * root) entirely from strings baked into this composable.
 *
 * This is deliberately NOT a WebView pointed at a URL and does NOT fetch
 * anything: the app declares no INTERNET permission, so a network-backed
 * privacy screen would silently fail to load. Everything here is bundled
 * with the app and rendered locally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    // Mirrors the "N selected" bar's pattern in RecycleBinScreen: a hardware/
    // gesture back press should close this screen the same way the visible
    // back arrow does, not fall through to whatever hosts this composable.
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Keepix Privacy Policy",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Effective Date: 2026-07-09  •  Version 1.0",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(20.dp))

            PolicySection(title = "1. What Keepix Does") {
                BodyText("Keepix is a local-first Android application that helps you declutter your photo and video gallery. You swipe through your media:")
                BulletText("Swipe right (Keep) — The item stays in your gallery; Keepix records only its URI and a timestamp so it won't appear again.")
                BulletText("Swipe left (Delete) — The item moves to an in-app Recycle Bin with a configurable retention period (default 10 days). You can restore it anytime before expiry. After expiry, Keepix permanently deletes the item from your device via the system MediaStore.")
                BulletText("Session Mode (0-day retention) — Items deleted during a session are permanently removed when you next open the app.")
                Spacer(modifier = Modifier.height(4.dp))
                BodyText("All decisions happen on your device. No media files are copied, uploaded, or transmitted.")
            }

            PolicySection(title = "2. What Keepix Does NOT Do") {
                BulletText("No analytics, telemetry, or crash-reporting SDKs")
                BulletText("No third-party libraries that transmit data")
                BulletText("No user accounts, authentication, or cloud sync")
                BulletText("No advertising, ad IDs, or tracking")
                BulletText("No network permission declared — the app binary contains no android.permission.INTERNET")
            }

            PolicySection(title = "3. Permissions Used & Why") {
                TableRow("READ_MEDIA_IMAGES (API 33+) / READ_EXTERNAL_STORAGE (API ≤32)", "Read your photo library to display cards")
                TableRow("READ_MEDIA_VIDEO (API 33+)", "Read your video library to display cards")
                Spacer(modifier = Modifier.height(4.dp))
                BodyText("These are read-only. Keepix never requests WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE.")
            }

            PolicySection(title = "4. Data Stored On Your Device") {
                BodyText("Keepix uses a local Room (SQLite) database and DataStore preferences.")
                TableRow("bin_items", "Recycle Bin items pending permanent deletion")
                TableRow("kept_items", "Items you chose to keep (prevents re-showing)")
                TableRow("DataStore prefs", "retentionDays, onboardingComplete, fullscreenTutorialComplete")
                Spacer(modifier = Modifier.height(4.dp))
                BodyText("No media bytes are stored. Only URIs and metadata.")
            }

            PolicySection(title = "5. Data Sharing") {
                BodyText("We share your data with no one. There is no backend, no analytics endpoint, no third-party processor.")
            }

            PolicySection(title = "6. Deletion & Retention") {
                BulletText("Recycle Bin items — Auto-deleted after your chosen retention period (1–365 days) or on next app reopen if set to 0-day mode.")
                BulletText("Kept items metadata — Persists until you uninstall Keepix.")
                BulletText("Uninstalling Keepix — Removes the app and its database. Your device gallery is untouched except for items you already permanently deleted via the Bin.")
            }

            PolicySection(title = "7. Children") {
                BodyText("Keepix is not directed at children under 13. We do not knowingly collect data from children.")
            }

            PolicySection(title = "8. Contact") {
                BodyText("Open an issue: github.com/Mazinkazi/keepix-native/issues")
            }

            PolicySection(title = "9. License") {
                BodyText("Keepix is open source under the MIT License. Source: github.com/Mazinkazi/keepix-native")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PolicySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = AccentPurple,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary
    )
}

@Composable
private fun BulletText(text: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text("•  ", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TableRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}
