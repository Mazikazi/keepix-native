package com.sese.keepix

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sese.keepix.ui.*
import com.sese.keepix.ui.theme.DarkBackground
import com.sese.keepix.ui.theme.KeepixTheme
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.MediaDeletionHandler

/**
 * Whether the app currently holds the media read permission(s) it needs.
 * Pulled out so both the initial state and the `ON_RESUME` recheck (for
 * returning from system Settings) share one source of truth.
 */
private fun checkMediaPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: KeepixViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KeepixTheme {
                com.sese.keepix.ui.components.GlassBackground {
                    KeepixApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun KeepixApp(viewModel: KeepixViewModel) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedMediaBounds by remember { mutableStateOf<MediaTransitionBounds?>(null) }
    // Gallery state for fullscreen viewer
    var fullscreenGalleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var fullscreenInitialIndex by remember { mutableIntStateOf(0) }
    var fullscreenSource by remember { mutableStateOf("swipe") } // swipe, bin, kept

    // Permission state
    var hasPermission by remember { mutableStateOf(checkMediaPermission(context)) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    val activity = context as? Activity

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        hasPermission = granted
        if (!granted) {
            // Once the system will no longer show a rationale for ANY of the
            // requested permissions, the user has hit "Deny & don't ask again"
            // (or an admin policy blocks it) and only Settings can recover —
            // this mirrors the platform's own recommended detection instead of
            // guessing from a request counter.
            val shouldShowRationale = activity != null && permissions.keys.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            isPermanentlyDenied = !shouldShowRationale
        }
    }

    // Returning from system Settings resumes the Activity but doesn't recreate
    // it, so re-check permission state on every ON_RESUME rather than only once
    // at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = checkMediaPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadMedia()
        }
    }

    // Batch deletion: turns viewModel.pendingDeletionUris into a system delete
    // confirmation. Keyed on BOTH pendingDeletionUris and promptedThisSession —
    // keying on the list alone would miss a re-arm (Empty Bin -> Cancel -> Empty
    // Bin again) since the list's *content* doesn't change when deleteBinItems
    // re-marks the same rows.
    val pendingDeletionUris by viewModel.pendingDeletionUris.collectAsState()
    val promptedThisSession by viewModel.promptedThisSession.collectAsState()

    // Ids of the request currently awaiting a result from the system dialog.
    // Doubles as the in-flight guard: filterExistingUris is a suspend call, so
    // there's an async gap between the effect firing and the IntentSender
    // actually launching. If the key set changes during that gap (e.g. a second
    // Empty Bin tap marks more rows before the first request resolves),
    // LaunchedEffect cancels and restarts this block — this flag, which survives
    // that restart because it's held in `remember` state outside the effect,
    // stops the restarted block from firing a second system dialog on top of
    // one that's already showing.
    var deletionInFlightIds by remember { mutableStateOf<List<Long>?>(null) }

    val deleteResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ids = deletionInFlightIds
        deletionInFlightIds = null
        if (ids != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.confirmDeletion(ids)
            } else {
                viewModel.deferDeletion(ids)
            }
        }
    }

    LaunchedEffect(pendingDeletionUris, promptedThisSession) {
        if (deletionInFlightIds != null) return@LaunchedEffect
        if (pendingDeletionUris.isEmpty() || promptedThisSession) return@LaunchedEffect

        val items = pendingDeletionUris
        val uris = items.map { Uri.parse(it.mediaUri) }
        val filterResult = MediaDeletionHandler.filterExistingUris(context, uris)

        val missingUris = filterResult.missing.toSet()
        val missingIds = items.filter { Uri.parse(it.mediaUri) in missingUris }.map { it.id }
        if (missingIds.isNotEmpty()) {
            viewModel.confirmDeletion(missingIds)
        }

        val existingUris = filterResult.existing.toSet()
        val existingItems = items.filter { Uri.parse(it.mediaUri) in existingUris }
        if (existingItems.isNotEmpty()) {
            val existingIds = existingItems.map { it.id }
            val intentSender = MediaDeletionHandler.getDeletionIntent(
                context,
                existingItems.map { Uri.parse(it.mediaUri) }
            ).intentSender
            deletionInFlightIds = existingIds
            deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    // Collect state
    val mediaItems by viewModel.mediaItems.collectAsState()
    val binItems by viewModel.binItems.collectAsState()
    val binCount by viewModel.binCount.collectAsState()
    val keptItemCount by viewModel.keptItemCount.collectAsState()
    val keptItems by viewModel.keptItems.collectAsState()
    val deletedCount by viewModel.deletedCount.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Determine start destination
    val startDestination = when {
        !hasPermission -> "permission"
        !viewModel.prefs.onboardingComplete -> "onboarding"
        else -> "swipe"
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable("permission") {
            PermissionScreen(
                onRequestPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO
                            )
                        )
                    } else {
                        // WRITE_EXTERNAL_STORAGE is not in the manifest (scoped
                        // storage + createDeleteRequest cover deletion instead) —
                        // requesting it here would make Android report it denied
                        // and `permissions.entries.all { it.value }` would never
                        // be true on API 30-32, the floor of minSdk 30.
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                isPermanentlyDenied = isPermanentlyDenied
            )

            // Navigate away when permission is granted
            LaunchedEffect(hasPermission) {
                if (hasPermission) {
                    if (!viewModel.prefs.onboardingComplete) {
                        navController.navigate("onboarding") {
                            popUpTo("permission") { inclusive = true }
                        }
                    } else {
                        navController.navigate("swipe") {
                            popUpTo("permission") { inclusive = true }
                        }
                    }
                }
            }
        }

        composable("onboarding") {
            OnboardingScreen(
                onComplete = {
                    viewModel.prefs.onboardingComplete = true
                    navController.navigate("swipe") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("swipe") {
            SwipeScreen(
                mediaItems = mediaItems,
                onSwipedLeft = { item -> viewModel.markForDeletion(item) },
                onSwipedRight = { item -> viewModel.keepMedia(item) },
                onNavigateToBin = { navController.navigate("bin") },
                onNavigateToKept = { navController.navigate("kept") },
                onNavigateToSettings = { navController.navigate("settings") },
                onCardBoundsChanged = { bounds -> selectedMediaBounds = bounds },
                onTapCard = { item ->
                    fullscreenGalleryItems = mediaItems.map { GalleryItem(it.uri, it.isVideo) }
                    fullscreenInitialIndex = mediaItems.indexOf(item).coerceAtLeast(0)
                    fullscreenSource = "swipe"
                    navController.navigate("fullscreen/${Uri.encode(item.uri.toString())}/${item.isVideo}/false")
                },
                binCount = binCount,
                keptCount = keptItemCount,
                deletedCount = deletedCount,
                error = error,
                onErrorDismiss = { viewModel.clearError() },
                isLoading = isLoading
            )
        }

        composable("bin") {
            RecycleBinScreen(
                items = binItems,
                isSessionMode = viewModel.prefs.isSessionMode,
                onRestore = { item -> viewModel.restoreItem(item) },
                onDeleteConfirmed = { viewModel.deleteBinItems(binItems) },
                onItemTap = { item ->
                    fullscreenGalleryItems = binItems.map { GalleryItem(Uri.parse(it.mediaUri), it.mediaType == "VIDEO") }
                    fullscreenInitialIndex = binItems.indexOf(item).coerceAtLeast(0)
                    fullscreenSource = "bin"
                    navController.navigate("fullscreen/${Uri.encode(item.mediaUri)}/${item.mediaType == "VIDEO"}/true")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("kept") {
            KeptItemsScreen(
                items = keptItems,
                onUnkeep = { item -> viewModel.unkeepItem(item) },
                onItemTap = { item ->
                    fullscreenGalleryItems = keptItems.map { GalleryItem(Uri.parse(it.mediaUri), it.mediaType == "VIDEO") }
                    fullscreenInitialIndex = keptItems.indexOf(item).coerceAtLeast(0)
                    fullscreenSource = "kept"
                    navController.navigate("fullscreen/${Uri.encode(item.mediaUri)}/${item.mediaType == "VIDEO"}/false")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                currentRetentionDays = viewModel.prefs.retentionDays,
                binCount = binCount,
                onRetentionChanged = { days -> viewModel.prefs.retentionDays = days },
                onEmptyBin = { viewModel.deleteBinItems(binItems) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "fullscreen/{mediaUri}/{isVideo}/{isBinMode}",
            arguments = listOf(
                navArgument("mediaUri") { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType },
                navArgument("isBinMode") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val mediaUri = Uri.parse(backStackEntry.arguments?.getString("mediaUri") ?: "")
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            val isBinMode = backStackEntry.arguments?.getBoolean("isBinMode") ?: false

            // Track current gallery index so page indicator updates
            var currentGalleryIndex by remember { mutableIntStateOf(fullscreenInitialIndex) }

            FullscreenViewer(
                mediaUri = mediaUri,
                isVideo = isVideo,
                isBinMode = isBinMode,
                transitionBounds = selectedMediaBounds,
                tutorialComplete = viewModel.prefs.fullscreenTutorialComplete,
                onTutorialDismiss = { viewModel.prefs.fullscreenTutorialComplete = true },
                onKeepOrRestore = {
                    if (isBinMode) {
                        val binItem = binItems.find { it.mediaUri == mediaUri.toString() }
                        binItem?.let { viewModel.restoreItem(it) }
                    }
                    navController.popBackStack()
                },
                onDeleteOrDeleteNow = {
                    if (isBinMode) {
                        val binItem = binItems.find { it.mediaUri == mediaUri.toString() }
                        binItem?.let { viewModel.deleteBinItems(listOf(it)) }
                    } else {
                        val mediaItem = mediaItems.find { it.uri == mediaUri }
                        mediaItem?.let { viewModel.markForDeletion(it) }
                    }
                    navController.popBackStack()
                },
                onDismiss = { navController.popBackStack() },
                galleryItems = fullscreenGalleryItems,
                initialIndex = currentGalleryIndex,
                onGalleryIndexChanged = { newIndex -> currentGalleryIndex = newIndex }
            )
        }
    }
}
