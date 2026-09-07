package com.sese.keepix.ui.neu

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sese.keepix.R
import com.sese.keepix.data.MediaItem

/**
 * Drives [NeuDeckScreen] with synthetic cards so the motion can be checked on a
 * real panel without media permissions or a populated library.
 *
 * ponytail: debug source set, bundled placeholder art. The deck reads real
 * MediaItems either way -- only the URIs are fake, so nothing here has to be
 * stubbed out inside the screen itself.
 */
class NeuDeckPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val drawables = listOf(
            R.drawable.sample_photo_1,
            R.drawable.sample_photo_2,
            R.drawable.sample_photo_3,
        )
        val seed = List(9) { i ->
            MediaItem(
                id = i.toLong(),
                uri = Uri.parse("android.resource://$packageName/${drawables[i % 3]}"),
                dateAdded = System.currentTimeMillis() / 1000 - i * 86_400L,
                isVideo = i % 4 == 3,
                displayName = "IMG_2026090${i}_1042.jpg",
                width = 900,
                height = 1200,
                durationMs = 14_000L,
                sizeBytes = 4_404_019L - i * 311_000L,
            )
        }
        setContent {
            NeuTheme(dark = false) {
                var deck by remember { mutableStateOf(seed) }
                var favorites by remember { mutableStateOf(setOf<Long>()) }
                NeuDeckScreen(
                    items = deck,
                    onCommit = { item, _ -> deck = deck.filterNot { it.id == item.id } },
                    onToggleFavorite = { item ->
                        favorites = if (item.id in favorites) favorites - item.id else favorites + item.id
                    },
                    isFavorite = { it.id in favorites },
                    streakDays = 4,
                    queuedCount = 2,
                )
            }
        }
    }
}
