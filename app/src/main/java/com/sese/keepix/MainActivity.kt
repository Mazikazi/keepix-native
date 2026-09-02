package com.sese.keepix

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sese.keepix.ui.*
import com.sese.keepix.ui.theme.DarkBackground
import com.sese.keepix.ui.theme.KeepixTheme
import com.sese.keepix.db.BinItemEntity
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.MediaDeletionHandler

private const val TAG = "MainActivity"

/**
 * Caps how many URIs go into a single `MediaStore.createDeleteRequest` call.
 * That call makes a synchronous Binder transaction into MediaProvider; an
 * unbounded batch risks `TransactionTooLargeException` on a very large bin.
 * Any remainder stays marked pending in the DB and is picked up on the next
 * pass once this batch resolves (see the deletion `LaunchedEffect` below).
 *
 * Derivation: the Binder transaction buffer is ~1 MB per process pair. A
 * `content://media/external/images/media/<id>` URI parcels to roughly
 * 100-150 bytes (scheme + authority + path + a Parcel-aligned string
 * header). At 750 URIs that's ~75-115 KB -- still an order of magnitude
 * under budget even after accounting for the surrounding Bundle/Parcel
 * overhead of the `createDeleteRequest` call, which this simple per-URI
 * estimate doesn't itemize. 750 sits inside the 500-1000 range that keeps
 * that margin while cutting the dialog count for a large bin (e.g. 500-1000
 * pending items) by 5-10x versus a 100-item cap.
 */
private const val MAX_DELETE_REQUEST_BATCH = 750


/**
 * The media permission(s) this app requests, version-gated.
 *
 * On API 34+ (`UPSIDE_DOWN_CAKE`), the system grant dialog can offer
 * "Select photos…" instead of "Allow all", which grants ONLY
 * `READ_MEDIA_VISUAL_USER_SELECTED` and reports both `READ_MEDIA_IMAGES` and
 * `READ_MEDIA_VIDEO` as denied. Requesting it alongside the other two lets
 * the picker offer that option explicitly; [checkMediaPermission] below is
 * what makes a grant of just this one still count as "has access" — see its
 * doc for why that agreement matters.
 */
private fun requiredMediaPermissions(): Array<String> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> {
            // WRITE_EXTERNAL_STORAGE is not in the manifest (scoped storage +
            // createDeleteRequest cover deletion instead) — requesting it here
            // would make Android report it denied and
            // `permissions.entries.all { it.value }` would never be true on
            // API 30-32, the floor of minSdk 30.
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

/**
 * Whether the app currently holds the media read permission(s) it needs.
 * Pulled out so both the initial state, the permission-request callback, and
 * the `ON_RESUME` recheck (for returning from system Settings) share one
 * source of truth — a disagreement between what is REQUESTED and what is
 * checked for SUCCESS is exactly the shape of the original P0 this app
 * shipped with (a request array that could never fully satisfy its own
 * success condition).
 *
 * On API 34+, a grant of ONLY `READ_MEDIA_VISUAL_USER_SELECTED` (the
 * "Select photos…" option) counts as having access, not just a full grant of
 * both `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`: `MediaStore` queries then
 * simply return the user-selected subset, which this app already handles
 * correctly since it queries `MediaStore` the same way regardless of which
 * grant produced the visible set.
 */
private fun checkMediaPermission(context: Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val fullAccess = granted(Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(Manifest.permission.READ_MEDIA_VIDEO)
        val partialAccess = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return fullAccess || partialAccess
    }
    return requiredMediaPermissions().all { granted(it) }
}

/**
 * True when the app's only media access is the API 34+ "Select photos…"
 * partial grant (some, not all, of `READ_MEDIA_VISUAL_USER_SELECTED`/
 * `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`) rather than the full pair. Used
 * only to inform the user in Settings why their library may look
 * incomplete — [checkMediaPermission] already treats this state as full
 * access for every functional purpose.
 */
private fun hasOnlyPartialMediaAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val fullAccess = granted(Manifest.permission.READ_MEDIA_IMAGES) &&
        granted(Manifest.permission.READ_MEDIA_VIDEO)
    val partialAccess = granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    return partialAccess && !fullAccess
}

/**
 * Unwraps a possibly-wrapped [Context] to find the [Activity] hosting it, if
 * any. `LocalContext.current` is the Activity directly in this app today, but
 * a plain `as? Activity` is fragile if that context is ever provided wrapped
 * (e.g. a test harness or a future `ContextThemeWrapper`) — a silent `null`
 * there would make permanent-denial detection misfire on the very first
 * request.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Saves/restores the in-flight delete-request id set across process death. */
private val DeletionInFlightSaver: Saver<List<Long>?, LongArray> = Saver(
    save = { it?.toLongArray() ?: LongArray(0) },
    restore = { if (it.isEmpty()) null else it.toList() }
)

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

    val activity = remember(context) { context.findActivity() }

    // Permission state
    var hasPermission by remember { mutableStateOf(checkMediaPermission(context)) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    // Mirrors viewModel.prefs.fullscreenTutorialComplete as Compose state.
    // The prefs property itself is a plain SharedPreferences-backed
    // getter/setter with no observability -- writing it does not, on its
    // own, trigger recomposition of anything. FullscreenViewer's own
    // GestureTutorialOverlay never clears its internal `visible`, so without
    // this mirror, tapping the overlay to dismiss it wrote the pref but left
    // the fullscreen destination composed with a stale `tutorialComplete =
    // false`, and the overlay stayed on screen until some unrelated
    // recomposition happened to re-read the pref. Read once at first
    // composition and flipped alongside the pref write in
    // onTutorialDismiss below.
    var tutorialComplete by remember {
        mutableStateOf(viewModel.prefs.fullscreenTutorialComplete)
    }

    // Ids of the request currently awaiting a result from the system delete
    // dialog. Doubles as the in-flight guard against a double-launch (see the
    // deletion LaunchedEffect below). rememberSaveable so a configuration
    // change or process death while the dialog is showing doesn't drop it and
    // silently swallow the eventual RESULT_OK/CANCELED.
    var deletionInFlightIds by rememberSaveable(stateSaver = DeletionInFlightSaver) {
        mutableStateOf<List<Long>?>(null)
    }

    // The FULL set of ids the current run considered eligible (i.e. every id
    // in `existingItems` before MAX_DELETE_REQUEST_BATCH chunking), paired
    // with deletionInFlightIds (which only holds the ids in the chunk
    // actually launched). Lets a cancel tell "more chunks of this same run
    // are still queued" from "the user marked genuinely new rows while the
    // dialog was open" -- only the latter should re-arm the prompt
    // immediately (see the launcher callback below).
    //
    // Deliberately plain `remember`, NOT rememberSaveable: unlike
    // deletionInFlightIds (capped at MAX_DELETE_REQUEST_BATCH), this can hold
    // every eligible id in a single run -- tens of thousands of rows for a
    // large bulk-delete -- which would push a Bundle-backed
    // onSaveInstanceState close to the Binder transaction limit. Losing this
    // across process death only degrades precision: the `(runIds ?: ids)`
    // fallback below then compares against just the last launched chunk
    // instead of the whole run, which can cause one extra, harmless re-prompt
    // for a multi-chunk batch cancelled mid-recreation -- never data loss.
    var deletionRunIds by remember {
        mutableStateOf<List<Long>?>(null)
    }

    // True once this composition has processed one ON_RESUME that wasn't the
    // synchronous replay LifecycleRegistry.addObserver fires the instant this
    // DisposableEffect's observer is added (it walks a newly-added observer
    // up to the owner's *current* state, so if the Activity is already
    // RESUMED -- which it always is by the time setContent's first
    // composition runs -- ON_RESUME fires immediately, before
    // rememberLauncherForActivityResult below has even registered to receive
    // a pending result). Gates resumeTick's bump and the stuck-guard clear
    // below so neither fires on that replay -- see the DisposableEffect for
    // why both would misbehave otherwise.
    var hasResumedOnce by remember { mutableStateOf(false) }

    // Bumped on every genuine ON_RESUME so the deletion effect below always
    // gets a fresh chance to run when the app returns to the foreground: it
    // retries a request that was skipped while backgrounded (see below) and
    // gives the stuck-guard recovery below something to react to.
    var resumeTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Re-derive from the live permission state via checkMediaPermission
        // rather than `permissions.entries.all { it.value }`: on API 34+,
        // choosing "Select photos…" grants ONLY
        // READ_MEDIA_VISUAL_USER_SELECTED and reports the other two
        // requested permissions as denied, so an `.all { }` over the launch
        // result would never be true for that (very common) choice and would
        // strand the user on the permission screen with no explanation —
        // the exact shape of the original P0. checkMediaPermission is the
        // single place that decision is made, shared with the initial state
        // and the ON_RESUME recheck below.
        val granted = checkMediaPermission(context)
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
                val granted = checkMediaPermission(context)
                hasPermission = granted
                if (granted) {
                    isPermanentlyDenied = false
                } else if (isPermanentlyDenied) {
                    // Only ever CLEARS a stale permanent-denial flag here, never
                    // sets one: shouldShowRequestPermissionRationale is also
                    // false before the very first request has ever been made,
                    // which includes this same check at the app's very first
                    // ON_RESUME — treating that as proof of permanent denial
                    // would strand a first-run user on "Open Settings" before
                    // they ever saw the permission dialog.
                    val shouldShowRationale = activity != null && requiredMediaPermissions().any {
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                    }
                    if (shouldShowRationale) {
                        isPermanentlyDenied = false
                    }
                }

                // Skip the bump and the stuck-guard recovery below on the
                // very first ON_RESUME of this composition: that one is
                // LifecycleRegistry's synchronous replay (see hasResumedOnce's
                // declaration), not a real foreground return. Letting it
                // through would (a) null out deletionInFlightIds the instant
                // rememberSaveable restores it on an Activity recreated while
                // the system dialog is showing -- discarding the eventual
                // RESULT_OK/CANCELED before the launcher below even registers
                // to receive it -- and (b) bump
                // resumeTick on every cold start for no reason, cancelling and
                // restarting the deletion effect's first (expensive,
                // per-pending-URI) filterExistingUris pass.
                if (hasResumedOnce) {
                    resumeTick++

                    // If a delete request's system dialog launch was silently
                    // dropped (e.g. background-activity-start restrictions
                    // while this Activity was stopped), its result callback
                    // never runs and this guard would otherwise suppress
                    // every future delete prompt for the rest of the process.
                    // The normal path always clears it from the launcher
                    // callback, which fires before a *genuine* ON_RESUME
                    // whenever a dialog actually completed — so if it's still
                    // set by the time we get here (past the first-ON_RESUME
                    // replay, which is excluded above), that dialog never
                    // actually appeared.
                    if (deletionInFlightIds != null) {
                        deletionInFlightIds = null
                        deletionRunIds = null
                    }
                }
                hasResumedOnce = true
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

    val pendingDeletionUris by viewModel.pendingDeletionUris.collectAsState()
    val promptedThisSession by viewModel.promptedThisSession.collectAsState()

    val deleteResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ids = deletionInFlightIds
        val runIds = deletionRunIds
        deletionInFlightIds = null
        deletionRunIds = null
        if (ids != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.confirmDeletion(ids)
            } else {
                viewModel.deferDeletion(ids)
                // A separate, explicit delete request may have marked more
                // rows pending while this dialog was open (blocked from
                // launching its own prompt by the in-flight guard above).
                // deferDeletion just unconditionally re-armed
                // promptedThisSession for its OWN (now un-marked) ids; left
                // alone that would also suppress this newer, never-shown
                // batch until the user acts again. Detect it and re-open the
                // prompt immediately.
                //
                // Checked against runIds (the FULL id set this run started
                // with, before MAX_DELETE_REQUEST_BATCH chunking) rather than
                // just the shown chunk's ids: chunks 2..N of the SAME run are
                // still "shown" in the sense that the user already asked to
                // delete them, and treating them as unshown here would re-open
                // the dialog immediately on every cancel of a multi-chunk
                // batch (and loop forever if a persistently-failing
                // unmarkPendingDeletion keeps re-selecting the same chunk).
                // Only a row outside runIds was genuinely marked after this
                // run began.
                val runIdSet = (runIds ?: ids).toSet()
                val hasUnshownItems = pendingDeletionUris.any { it.id !in runIdSet }
                if (hasUnshownItems) {
                    viewModel.rearmPrompt()
                }
            }
        }
    }

    // Batch deletion: turns viewModel.pendingDeletionUris into a system delete
    // confirmation. Keyed on:
    //  - pendingDeletionUris + promptedThisSession: keying on the list alone
    //    would miss a re-arm (Empty Bin -> Cancel -> Empty Bin again) since
    //    the list's *content* doesn't change when deleteBinItems re-marks the
    //    same rows.
    //  - hasPermission: without read access filterExistingUris can't verify
    //    anything (every URI fails-safe into `existing`), and a user who
    //    regains permission should have any pending rows processed without
    //    needing an unrelated state change first.
    //  - resumeTick: re-evaluates on every foreground return, so a request
    //    skipped while backgrounded (below) gets retried.
    LaunchedEffect(pendingDeletionUris, promptedThisSession, hasPermission, resumeTick) {
        if (!hasPermission) return@LaunchedEffect
        if (deletionInFlightIds != null) return@LaunchedEffect
        if (pendingDeletionUris.isEmpty() || promptedThisSession) return@LaunchedEffect

        // Parse each mediaUri once and carry the pair through both buckets,
        // instead of re-parsing per lookup.
        val itemUris = pendingDeletionUris.map { it to Uri.parse(it.mediaUri) }
        val filterResult = MediaDeletionHandler.filterExistingUris(context, itemUris.map { it.second })

        val missingUris = filterResult.missing.toSet()
        val missingIds = itemUris.filter { it.second in missingUris }.map { it.first.id }
        if (missingIds.isNotEmpty()) {
            viewModel.confirmDeletion(missingIds)
            // TRD Keepix_TRD_v1.1.md:326 - "Media URI no longer valid (file
            // moved externally) -> Catch error, silently remove from SQLite,
            // show snackbar 'Photo no longer on device'". The removal half is
            // confirmDeletion above; this attaches the required message.
            viewModel.reportError("Photo no longer on device")
        }

        val existingUris = filterResult.existing.toSet()
        val existingItems = itemUris.filter { it.second in existingUris }
        if (existingItems.isEmpty()) return@LaunchedEffect

        // Don't show system UI while backgrounded: a launch here can be
        // silently dropped by background-activity-start restrictions, which
        // would otherwise leave deletionInFlightIds stuck with no result ever
        // arriving. resumeTick re-fires this whole effect on every foreground
        // return, so this batch is retried as soon as the app is visible.
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }

        val batch = existingItems.take(MAX_DELETE_REQUEST_BATCH)
        val batchIds = batch.map { it.first.id }
        val runIds = existingItems.map { it.first.id }
        try {
            val intentSender = MediaDeletionHandler.getDeletionIntent(
                context,
                batch.map { it.second }
            ).intentSender
            deletionInFlightIds = batchIds
            deletionRunIds = runIds
            deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            // createDeleteRequest makes a synchronous call into MediaProvider
            // and can throw (unresolvable URI, a wedged provider returning a
            // null result bundle, TransactionTooLargeException on an
            // oversized batch). Uncaught, that would propagate out of this
            // LaunchedEffect and kill the process — and since
            // performLaunchCleanup marks expired rows on every launch, that's
            // a launch crash-loop with no in-app recovery. Defer instead:
            // un-mark the batch and surface it as a normal, recoverable error.
            Log.e(TAG, "Failed to build/launch the system delete request", e)
            deletionInFlightIds = null
            deletionRunIds = null
            viewModel.deferDeletion(batchIds)
            viewModel.reportError("Couldn't open the delete confirmation. Please try again.")
        }
    }

    // Collect state
    val mediaItems by viewModel.mediaItems.collectAsState()
    val binItems by viewModel.binItems.collectAsState()
    val binCount by viewModel.binCount.collectAsState()
    val keptItemCount by viewModel.keptItemCount.collectAsState()
    val keptItems by viewModel.keptItems.collectAsState()
    val deletedCount by viewModel.deletedCount.collectAsState()
    val sessionKeptCount by viewModel.sessionKeptCount.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val reachedEnd by viewModel.reachedEnd.collectAsState()
    val hasLoadedOnce by viewModel.hasLoadedOnce.collectAsState()

    // Task 5: whether a fullscreen viewer is currently the top destination --
    // used only to pause SwipeScreen's autoplaying top-card video while it's
    // covered by the fullscreen one.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isFullscreenOpen = currentBackStackEntry?.destination?.route?.startsWith("fullscreen") == true

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
                    // Single source of truth shared with checkMediaPermission
                    // and the rationale checks above — hand-rolling this array
                    // a second time is exactly the kind of drift that caused
                    // the original P0 (a request array out of sync with what
                    // the manifest declares).
                    permissionLauncher.launch(requiredMediaPermissions())
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
                    navController.navigate(
                        "fullscreen/${Uri.encode(item.uri.toString())}/${item.isVideo}/${ViewerMode.SWIPE.routeKey}"
                    )
                },
                binCount = binCount,
                keptCount = keptItemCount,
                sessionKeptCount = sessionKeptCount,
                deletedCount = deletedCount,
                error = error,
                onErrorDismiss = { viewModel.clearError() },
                isLoading = isLoading,
                reachedEnd = reachedEnd,
                hasLoadedOnce = hasLoadedOnce,
                onRetry = { viewModel.loadMedia() },
                isFullscreenOpen = isFullscreenOpen
            )
        }

        composable("bin") {
            RecycleBinScreen(
                items = binItems,
                isSessionMode = viewModel.prefs.isSessionMode,
                onRestore = { item -> viewModel.restoreItem(item) },
                onDeleteConfirmed = { viewModel.deleteBinItems(binItems) },
                onDeleteSelected = { selected -> viewModel.deleteBinItems(selected) },
                onItemTap = { item ->
                    navController.navigate(
                        "fullscreen/${Uri.encode(item.mediaUri)}/${item.mediaType == "VIDEO"}/${ViewerMode.BIN.routeKey}"
                    )
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("kept") {
            KeptItemsScreen(
                items = keptItems,
                onUnkeep = { item -> viewModel.unkeepItem(item) },
                onItemTap = { item ->
                    navController.navigate(
                        "fullscreen/${Uri.encode(item.mediaUri)}/${item.mediaType == "VIDEO"}/${ViewerMode.KEPT.routeKey}"
                    )
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            // Recomputed off hasPermission (checkMediaPermission's own key)
            // rather than cached once: a user who leaves for Settings to
            // upgrade from a partial to a full grant and comes back should
            // see this notice go away without restarting the app.
            val hasOnlyPartialAccess = remember(hasPermission) {
                hasOnlyPartialMediaAccess(context)
            }
            SettingsScreen(
                currentRetentionDays = viewModel.prefs.retentionDays,
                binCount = binCount,
                onRetentionChanged = { days -> viewModel.prefs.retentionDays = days },
                onEmptyBin = { viewModel.deleteBinItems(binItems) },
                hasOnlyPartialMediaAccess = hasOnlyPartialAccess,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            // The mode travels as its ViewerMode.routeKey rather than the old
            // isBinMode Boolean: a Boolean cannot express the third ("kept
            // grid") case the action bar needs to label its buttons for. Kept
            // as a nav argument (not just composition state) so it survives
            // process death while the viewer is on top.
            route = "fullscreen/{mediaUri}/{isVideo}/{mode}",
            arguments = listOf(
                navArgument("mediaUri") { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType },
                navArgument("mode") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mediaUri = Uri.parse(backStackEntry.arguments?.getString("mediaUri") ?: "")
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            val mode = ViewerMode.fromRouteKey(backStackEntry.arguments?.getString("mode"))

            // Derived from the LIVE list for this mode, never from a snapshot
            // taken at tap time. A snapshot survives process death while
            // performLaunchCleanup() runs on every launch and can retire rows
            // underneath it, so a restored viewer could page through items that
            // no longer exist and count them in the indicator. Deriving here
            // also means an expiry firing while the viewer is open simply
            // shortens the gallery instead of stranding dead pages.
            val galleryItems = remember(mode, mediaItems, binItems, keptItems) {
                when (mode) {
                    ViewerMode.SWIPE -> mediaItems.map { GalleryItem(it.uri, it.isVideo) }
                    ViewerMode.BIN -> binItems.map {
                        GalleryItem(Uri.parse(it.mediaUri), it.mediaType == "VIDEO")
                    }
                    ViewerMode.KEPT -> keptItems.map {
                        GalleryItem(Uri.parse(it.mediaUri), it.mediaType == "VIDEO")
                    }
                }
            }
            val initialIndex = remember(galleryItems, mediaUri) {
                galleryItems.indexOfFirst { it.uri == mediaUri }.coerceAtLeast(0)
            }

            FullscreenViewer(
                mediaUri = mediaUri,
                isVideo = isVideo,
                mode = mode,
                transitionBounds = selectedMediaBounds,
                tutorialComplete = tutorialComplete,
                onTutorialDismiss = {
                    viewModel.prefs.fullscreenTutorialComplete = true
                    tutorialComplete = true
                },
                // Both callbacks take the URI of the item actually on screen.
                // In gallery mode that is whichever page the pager has settled
                // on, NOT the item that was originally tapped -- paging to
                // another bin entry and hitting RESTORE must restore that
                // entry, not the one the route was built from.
                onKeepOrRestore = { uri ->
                    val key = uri.toString()
                    // binItems/keptItems are live flows -- and galleryItems
                    // above is derived from them on every recomposition, not
                    // a tap-time snapshot (see that definition) -- so an
                    // expiry or cleanup pass firing while the viewer is open
                    // can still retire the row out from under us between the
                    // last recomposition and this callback running. Report
                    // the miss rather than closing the viewer and silently
                    // doing nothing.
                    val hit = when (mode) {
                        ViewerMode.BIN ->
                            binItems.find { it.mediaUri == key }
                                ?.also { viewModel.restoreItem(it) }
                        ViewerMode.KEPT ->
                            keptItems.find { it.mediaUri == key }
                                ?.also { viewModel.unkeepItem(it) }
                        ViewerMode.SWIPE ->
                            mediaItems.find { it.uri == uri }
                                ?.also { viewModel.keepMedia(it) }
                    }
                    // Report the hit/miss instead of reporting an error into a
                    // flow the bin and kept screens never render: on a miss the
                    // viewer stays open and shows the notice itself.
                    if (hit != null) navController.popBackStack()
                    hit != null
                },
                onDeleteOrDeleteNow = { uri ->
                    val key = uri.toString()
                    val hit = when (mode) {
                        // DELETE NOW only *marks* the row pending; the actual
                        // file deletion still goes through the Activity's
                        // system-confirmation flow below, so no Room row is
                        // dropped for an unconfirmed file deletion.
                        ViewerMode.BIN ->
                            binItems.find { it.mediaUri == key }
                                ?.also { viewModel.deleteBinItems(listOf(it)) }
                        ViewerMode.KEPT ->
                            keptItems.find { it.mediaUri == key }
                                ?.also { viewModel.deleteKeptItem(it) }
                        ViewerMode.SWIPE ->
                            mediaItems.find { it.uri == uri }
                                ?.also { viewModel.markForDeletion(it) }
                    }
                    if (hit != null) navController.popBackStack()
                    hit != null
                },
                onDismiss = { navController.popBackStack() },
                galleryItems = galleryItems,
                initialIndex = initialIndex
            )
        }
    }
}
