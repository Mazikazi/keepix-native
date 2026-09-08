package com.sese.keepix.ui.neu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sese.keepix.core.logic.DeckAction
import com.sese.keepix.core.logic.QualityTier
import com.sese.keepix.data.MediaItem
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.db.CompressedItemEntity
import com.sese.keepix.db.KeptItemEntity
import kotlinx.coroutines.delay

/**
 * The five-tab app shell.
 *
 * One composable rather than five nav destinations because the prototype draws
 * the header ONCE, above the tab switch, and the tab bar is not a back stack --
 * switching tabs is not a navigation the system back button should unwind.
 * Fullscreen, the library and the paywall stay real destinations/overlays.
 *
 * The deck keeps its own header (it owns the undo ring); every other tab gets
 * the shared one, which is the same composable, so nothing shifts.
 */
@Composable
fun NeuHomeScreen(
    tab: NeuTab,
    onTabChange: (NeuTab) -> Unit,
    // Deck
    mediaItems: List<MediaItem>,
    onCommit: (MediaItem, DeckAction) -> Unit,
    onTapCard: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    isFavorite: (MediaItem) -> Boolean,
    onOpenLibrary: () -> Unit,
    onCardBoundsChanged: (com.sese.keepix.ui.MediaTransitionBounds) -> Unit,
    isFullscreenOpen: Boolean,
    isLoading: Boolean,
    hasLoadedOnce: Boolean,
    reachedEnd: Boolean,
    error: String?,
    onErrorDismiss: () -> Unit,
    onRetry: () -> Unit,
    hasOnlyPartialMediaAccess: Boolean,
    // Bin
    binItems: List<BinItemEntity>,
    isSessionMode: Boolean,
    onRestore: (BinItemEntity) -> Unit,
    onDelete: (List<BinItemEntity>) -> Unit,
    onOpenBinItem: (BinItemEntity) -> Unit,
    // Compressed
    compressedItems: List<CompressedItemEntity>,
    totalBytesSaved: Long,
    queuedCount: Int,
    // Rank
    streakDays: Int,
    weeklySwipes: Int,
    // Settings
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
    modifier: Modifier = Modifier,
    toast: NeuHomeToast? = null,
    onToastExpired: () -> Unit = {},
) {
    val c = neu
    var previewItem by remember { mutableStateOf<BinItemEntity?>(null) }
    // The sheet holds an entity captured at tap time; drop it once that row is
    // gone (restored, deleted, expired) rather than showing a stale card.
    val livePreview = previewItem?.let { p -> binItems.firstOrNull { it.id == p.id } }

    Box(modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            if (tab != NeuTab.SWIPE) {
                NeuHeader(streakDays, queuedCount, onOpenLibrary)
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    NeuTab.SWIPE -> NeuDeckScreen(
                        items = mediaItems,
                        onCommit = onCommit,
                        onTapCard = onTapCard,
                        onOpenLibrary = onOpenLibrary,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        streakDays = streakDays,
                        queuedCount = queuedCount,
                        isLoading = isLoading,
                        hasLoadedOnce = hasLoadedOnce,
                        reachedEnd = reachedEnd,
                        error = error,
                        onErrorDismiss = onErrorDismiss,
                        onRetry = onRetry,
                        hasOnlyPartialMediaAccess = hasOnlyPartialMediaAccess,
                        onCardBoundsChanged = onCardBoundsChanged,
                        isFullscreenOpen = isFullscreenOpen,
                    )
                    NeuTab.BIN -> NeuBinScreen(
                        items = binItems,
                        isSessionMode = isSessionMode,
                        onRestore = onRestore,
                        onDelete = onDelete,
                        onOpenItem = { previewItem = it },
                    )
                    NeuTab.SMALL -> NeuCompressedScreen(compressedItems, totalBytesSaved)
                    NeuTab.RANK -> NeuRankScreen(
                        streakDays = streakDays,
                        weeklySwipes = weeklySwipes,
                        accountEnabled = accountEnabled,
                        onOpenSettings = { onTabChange(NeuTab.YOU) },
                    )
                    NeuTab.YOU -> NeuSettingsScreen(
                        retentionDays = retentionDays,
                        onRetentionChanged = onRetentionChanged,
                        tier = tier,
                        onTierChanged = onTierChanged,
                        isPro = isPro,
                        lightUsesToday = lightUsesToday,
                        onOpenPaywall = onOpenPaywall,
                        accountEnabled = accountEnabled,
                        onAccountChanged = onAccountChanged,
                        batchSize = batchSize,
                        hasOnlyPartialMediaAccess = hasOnlyPartialMediaAccess,
                    )
                }
            }
            NeuTabBar(tab, onTabChange)
        }

        if (toast != null) {
            // Wall clock, not an animation: with ANIMATOR_DURATION_SCALE at 0
            // an animated dismissal fires instantly, and the toast would never
            // be readable for the users who set reduced motion.
            LaunchedEffect(toast) {
                delay(3_500)
                onToastExpired()
            }
            NeuToast(
                message = toast.message,
                kind = toast.kind,
                actionLabel = toast.actionLabel,
                onAction = { onTabChange(NeuTab.BIN); onToastExpired() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 104.dp),
            )
        }

        livePreview?.let { entity ->
            NeuBinPreviewSheet(
                item = entity,
                onRestore = { onRestore(entity); previewItem = null },
                onDeleteNow = { onDelete(listOf(entity)); previewItem = null },
                onDismiss = { previewItem = null },
                onOpenFullscreen = { onOpenBinItem(entity); previewItem = null },
                modifier = Modifier.systemBarsPadding(),
            )
        }
    }
}

/** One transient message. Held by the host so it survives a tab change. */
data class NeuHomeToast(
    val message: String,
    val kind: NeuToastKind,
    val actionLabel: String? = null,
)
