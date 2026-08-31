package com.sese.keepix

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var isPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionRequestCount by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        hasPermission = granted
        if (!granted) {
            permissionRequestCount++
            if (permissionRequestCount >= 2) {
                isPermanentlyDenied = true
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadMedia()
        }
    }

    // TASK 3 wires the system delete dialog here: a StartIntentSenderForResult
    // launcher plus a LaunchedEffect on viewModel.pendingDeletionUris that builds
    // one MediaStore.createDeleteRequest over the marked URIs and calls
    // viewModel.confirmDeletion(ids) on RESULT_OK / viewModel.deferDeletion()
    // otherwise. Until then, delete actions only mark rows as pending.

    // Collect state
    val mediaItems by viewModel.mediaItems.collectAsState()
    val binItems by viewModel.binItems.collectAsState()
    val binCount by viewModel.binCount.collectAsState()
    val keptItemCount by viewModel.keptItemCount.collectAsState()
    val keptItems by viewModel.keptItems.collectAsState()
    val deletedCount by viewModel.deletedCount.collectAsState()
    val error by viewModel.error.collectAsState()

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
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
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
                    fullscreenGalleryItems = emptyList()
                    fullscreenInitialIndex = 0
                    fullscreenSource = "swipe"
                    navController.navigate("fullscreen/${Uri.encode(item.uri.toString())}/${item.isVideo}/false")
                },
                binCount = binCount,
                keptCount = keptItemCount,
                deletedCount = deletedCount,
                error = error,
                onErrorDismiss = { viewModel.clearError() }
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
