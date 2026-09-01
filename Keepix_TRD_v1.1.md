# Keepix — Technical Requirements Document (TRD)
**Version:** 1.1
**Platform:** Android (Google Play)
**Stack:** Kotlin / Jetpack Compose (native Android)
**Status:** Implemented

---

## 1. Tech Stack

| Layer | Technology | Reason |
|---|---|---|
| Language | Kotlin | Native Android language, null-safety, coroutines |
| UI Framework | Jetpack Compose (Material 3) | Declarative UI, first-party Android toolkit |
| Navigation | Navigation Compose (`androidx.navigation:navigation-compose`) | Standard Compose nav-graph, back stack management |
| Swipe Gesture | Compose `pointerInput` + `detectDragGestures`, `Animatable`/`graphicsLayer` | Native gesture + animation APIs; no external gesture library needed |
| Image Loading | Coil (`io.coil-kt:coil-compose`) | Compose-native, asynchronous, LRU-cached image loading for MediaStore URIs |
| Video Playback | `android.widget.VideoView` + `MediaPlayer`, hosted via Compose `AndroidView` | Local file/URI playback; no dedicated video dependency |
| Media Access | `android.provider.MediaStore` (`ContentResolver.query`), `MediaStore.createDeleteRequest` | Gallery query, keyset pagination, and the system-mediated delete flow scoped storage requires |
| Local Database | Room (SQLite) — `androidx.room` | Bin metadata (`bin_items`), kept-item metadata (`kept_items`), explicit versioned migrations |
| Settings Storage | `SharedPreferences` (via a `KeepixPreferences` wrapper class) | Simple synchronous key-value store for user preferences |
| Blur / Glassmorphism | Hand-rolled Compose modifiers (`Modifier.background` + alpha layering, no native blur) | Avoids a blur dependency; frosted-glass look achieved with semi-transparent surfaces and borders |
| Permissions | `androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions` + `ActivityCompat.shouldShowRequestPermissionRationale` | Standard AndroidX permission APIs; no third-party wrapper |
| Background Tasks | WorkManager (`androidx.work:work-runtime-ktx`), `PeriodicWorkRequest` | Daily retention sweep that *marks* expired items, run whether or not the app is open |
| Minimum Android | API 30 (Android 11) | Simplifies the deletion path (`MediaStore.createDeleteRequest` requires API 30); Android 10 and earlier are no longer supported |
| Target Android | API 35 (Android 15) | Latest Play Store requirement |

**No network permission is declared or used.** The app is fully offline; there is no `INTERNET` permission and no backend of any kind.

---

## 2. Architecture

The app follows the official Android **MVVM** guidance with unidirectional data flow (UDF), built entirely on Kotlin Coroutines and `StateFlow` — no external state-management library.

```
UI Layer (Jetpack Compose Screens + Components)
        ↑ (StateFlow state)
        |
KeepixViewModel (AndroidViewModel, viewModelScope)
        ↑ (suspend calls / Flow streams)
        |
MediaRepository  ─────────────┐
        |                     |
Data Sources:                 |
  - Android MediaStore  <─────┘   (device gallery, keyset-paginated)
  - Room Database (bin_items, kept_items)
  - SharedPreferences (KeepixPreferences)
  - WorkManager (SessionCleanupWorker — daily retention sweep)
```

**State management:** A single `KeepixViewModel` (scoped to the Activity) exposes `StateFlow`s for media queue, bin, kept items, loading/error state, and pagination status. `MainActivity`'s composables hold only UI-local state (permission flags, in-flight delete-request ids, navigation) via `remember`/`rememberSaveable`.

---

## 3. Module Breakdown

### 3.1 Media Indexer

**Responsibility:** Query the device gallery (images + videos) and return media in batches, oldest-safe keyset-paginated.

**Implementation:** `MediaRepository.getMediaPage(after: MediaPageKey?, limit: Int)`, backed by `MediaStore.Files` with a combined image+video selection:

```kotlin
val queryArgs = Bundle().apply {
    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder) // DATE_ADDED DESC, _ID DESC
    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
}
context.contentResolver.query(collection, projection, queryArgs, null)
```

- **Pagination cursor:** `MediaPageKey(dateAdded, id)` — a composite keyset cursor, deliberately *not* a row-count offset. `DATE_ADDED` is only second-precision (bulk imports routinely tie), and the result set mutates under the app as bin items are confirmed-deleted; an integer offset would silently skip or duplicate rows under either condition. A page returning fewer rows than requested (including empty) is the pagination-termination signal — this is read directly off the query result, never inferred from a cached total count.
- Default batch size: 50 items per page (`KeepixPreferences.batchSize`, not currently exposed in Settings UI).
- **Filtering:** Before a batch is added to the on-screen queue, it is filtered against the current bin (`bin_items`) and kept (`kept_items`) id sets so already-decided items never resurface.
- **Preload trigger:** `KeepixViewModel.removeSwipedItem` triggers a top-up fetch once the in-memory queue drops below half of `batchSize`, so the user rarely sees a loading state mid-swipe.

### 3.2 Swipe Card Engine

**Responsibility:** Tinder-style card stack with gesture-driven keep/delete, implemented in `SwipeScreen.kt`.

**Implementation approach:** Compose `pointerInput(Unit) { detectDragGestures { ... } }` drives an `Animatable`/mutable offset that feeds a `graphicsLayer` (rotation, translation, alpha) — no external gesture or animation library.

- **Threshold:** `screenWidth * 0.4f` — if released past this offset, the swipe fires as a keep/delete decision.
- **Below threshold:** the card animates back to center.
- **Above threshold:** the card animates off-screen (`OutgoingSwipeCard`) and the decision (`onSwipedLeft`/`onSwipedRight`) is dispatched to the ViewModel.
- **Card stack:** the top card is fully interactive; up to 3 additional cards render behind it at decreasing scale/increasing offset (`scale = 1f - stackDepth * 0.08f`) purely for visual depth — only the top card ever receives gesture/tap input.
- **Alternate input:** two `IconButton`s (X = delete, check = keep) sit near the bottom of the card for users who prefer tapping over swiping; they dispatch the same `onSwipedLeft`/`onSwipedRight` callbacks as a completed drag.
- A `swipeInProgress` guard (reset per-card via `remember(currentItem.id)`) prevents a double-fire from a rapid double-tap or a drag landing on a card whose swipe was already accepted.

### 3.3 Fullscreen Viewer

**Responsibility:** Fullscreen photo/video on card tap, implemented in `FullscreenViewer.kt`. Supports three modes (`ViewerMode.SWIPE`, `BIN`, `KEPT`) that change which actions are offered.

**Photo:**
- Pinch-to-zoom and pan via Compose transform gestures (`detectTransformGestures`), no external zoom library.
- Min zoom 1x, max zoom 5x (`MAX_SCALE = 5f`).
- Double-tap toggles between 1x and 2.5x (`DOUBLE_TAP_SCALE = 2.5f`).

**Video:**
- `android.widget.VideoView` + `MediaPlayer`, embedded via Compose's `AndroidView` interop — this is a plain platform view, not a Compose-native player, which is also why video is not zoomable in this screen.
- Auto-plays on open, loops.
- Custom controls overlay (play/pause, scrubber, mute, duration); fades out after 3 seconds of inactivity (`CONTROLS_FADE_DELAY_MS = 3_000L`), tap to show again.

**Entry/Exit:**
- Entry: cross-fade/scale transition from the originating card's on-screen bounds (`MediaTransitionBounds`), not a Navigation-Compose shared-element API.
- Exit: swipe-down-to-dismiss (velocity-based) or the back button/system gesture.

**Action bar at bottom:** a glassmorphism pill with mode-dependent actions — KEEP/DELETE from the swipe queue, RESTORE/DELETE from the Bin, or DELETE (unkeep + bin) from Kept Items.

**Immersive mode:** status and navigation bars are hidden while the viewer is open and restored on exit.

### 3.4 Recycle Bin

**Responsibility:** Display and manage bin items (`RecycleBinScreen.kt`), backed by the `bin_items` Room table.

**How deletion actually works — critical point (mark-then-confirm):**
Keepix never moves or copies a media file, and it never calls a direct delete API on a row it doesn't own. Because the app doesn't own most gallery rows, `ContentResolver.delete()` on them throws `RecoverableSecurityException` on modern Android. Instead:
1. A swipe-left, an Empty Bin tap, or a retention-expiry sweep only ever **marks** a `bin_items` row's `pendingDeletion` flag — the file is untouched and the row still exists.
2. `MainActivity` observes marked-pending rows and, once the app is in the foreground, builds a single `MediaStore.createDeleteRequest(...)` `PendingIntent` for the batch and launches the resulting system confirmation dialog.
3. Only on `RESULT_OK` does `KeepixViewModel.confirmDeletion(ids)` drop the Room rows — this is the *only* place a bin row is removed. On cancel/anything else, `deferDeletion(ids)` un-marks them back to ordinary bin rows (never left permanently "pending" with no way out).

This means the Bin uses zero extra file storage, and "Restore" is always just a Room delete of the bin row (the file was never touched).

**Bin UI:**
- 3-column grid (`LazyVerticalGrid(columns = GridCells.Fixed(3))`).
- Each thumbnail shows a countdown badge: `"N days left"` / `"1 day left"` / `"< 1 day left"` for timed retention, `"Deletes on reopen"` (orange) for session mode.
- Long press → multi-select mode (top bar becomes `"N selected"` with Restore/Delete actions).
- "Empty Bin" triggers a confirmation dialog: *"Permanently delete all N items? This cannot be undone."* Both Empty Bin and per-selection delete route through the same mark-then-confirm flow above — nothing is deleted synchronously from this screen.

### 3.5 Deletion Engine

**Responsibility:** Decide *when* a bin item becomes eligible for the mark-then-confirm flow above. This module only ever marks rows `pendingDeletion`; the actual file removal always happens in the Activity-owned system-dialog flow in §3.4.

**Two triggers, both mark-only:**

**Trigger 1 — Timed retention (1–365 days):**

Checked on every app launch (`KeepixViewModel.performLaunchCleanup`, called from `init {}`) and, in case the app is never reopened, once a day via WorkManager:

```kotlin
private fun schedulePeriodicCleanup() {
    val workRequest = PeriodicWorkRequestBuilder<SessionCleanupWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
        .build()
    WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
        "session_cleanup", ExistingPeriodicWorkPolicy.UPDATE, workRequest
    )
}
```

`SessionCleanupWorker.doWork()` queries `bin_items` for `retentionMode = 'TIMED' AND expiryAt <= now()` and calls `markPendingDeletion` on the matches — it never touches files and never rotates the session id (see Trigger 2).

**Trigger 2 — Session-based (retention = 0 days):**

- On every app launch, `KeepixPreferences.generateNewSession()` rotates `lastSessionId` to a fresh UUID and returns the *previous* one. Session rotation happens **only** here, at launch — a background worker must not rotate it, or it could expire the running app's own in-progress session.
- With the previous session id in hand, `performLaunchCleanup` queries `bin_items` for `retentionMode = 'SESSION' AND sessionId != currentSessionId` and marks those `pendingDeletion`.
- The marked rows then flow through the same `MainActivity` system-dialog path as any other pending deletion — there is no separate, synchronous "cleanup before the UI renders" step; the swipe screen can render immediately, and the delete confirmation appears as soon as the Activity is resumed.

**Why not delete on app close?** The process can be killed at any time once backgrounded, with no reliable "about to die" callback. Marking-at-launch (session rotation) plus a daily WorkManager sweep (timed retention) both only need to run when the process *is* alive, so neither depends on catching the moment the app goes away.

---

## 4. Data Model

### 4.1 Room Database: `keepix_database` (version 4, explicit migrations, `exportSchema = true`)

**Table: `bin_items`**

| Column | Type | Description |
|---|---|---|
| `id` | `Long` PRIMARY KEY AUTOINCREMENT | Internal ID |
| `mediaId` | `Long` | Original MediaStore asset id |
| `mediaUri` | `String` | MediaStore content URI, as a string |
| `displayName` | `String` | Filename for display in bin |
| `mediaType` | `String` | `'IMAGE'` or `'VIDEO'` |
| `dateTaken` | `Long` | Unix timestamp (ms) of original capture |
| `deletedAt` | `Long` | Unix timestamp (ms) when swiped left |
| `expiryAt` | `Long` | Unix timestamp (ms) for permanent-delete eligibility. `0` = session mode |
| `sessionId` | `String` | Session UUID active when this row was binned |
| `retentionMode` | `String` | `'TIMED'` or `'SESSION'` |
| `width` / `height` | `Int` | Original media dimensions in pixels |
| `durationMs` | `Long` | Video duration in ms, `0` for images |
| `pendingDeletion` | `Boolean` | Added in migration 3→4. `true` once cleanup has selected this row for the system delete dialog; the row is dropped only after the user confirms it (§3.4) |

**Table: `kept_items`**

| Column | Type | Description |
|---|---|---|
| `id` | `Int` PRIMARY KEY AUTOINCREMENT | Internal ID |
| `mediaId` | `Long` | Original MediaStore asset id |
| `mediaUri` | `String` | MediaStore content URI, as a string |
| `displayName` | `String` | Filename |
| `mediaType` | `String` | `'IMAGE'` or `'VIDEO'` |
| `dateTaken` | `Long` | Unix timestamp (ms) of original capture |
| `keptAt` | `Long` | Unix timestamp (ms) when swiped right |
| `width` / `height` | `Int` | Media dimensions in pixels |
| `durationMs` | `Long` | Video duration in ms, `0` for images |

There is no separate `user_sessions` table — the current session id is a single `SharedPreferences` value (see below), not a row-per-session log.

### 4.2 SharedPreferences (`keepix_prefs`, via `KeepixPreferences`)

| Key | Type | Default | Description |
|---|---|---|---|
| `retention_days` | Int | 10 | Days before permanent-deletion eligibility. `0` = session mode |
| `batch_size` | Int | 50 | MediaStore rows fetched per page (internal; not exposed in Settings) |
| `onboarding_complete` | Boolean | false | Whether onboarding has been shown |
| `last_session_id` | String | `""` | Session id rotated at the previous launch |
| `fullscreen_tutorial_complete` | Boolean | false | Whether the fullscreen-viewer tutorial hint has been shown |

---

## 5. Permission Handling

**APIs used:** `ActivityResultContracts.RequestMultiplePermissions`, `ActivityCompat.shouldShowRequestPermissionRationale` — standard AndroidX, no third-party permission library.

| Android API | Permissions |
|---|---|
| API 30 – 32 (minSdk 30) | `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) |
| API 33+ | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |

```kotlin
fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
```

- Checked on launch and re-checked on every `ON_RESUME` (covers the user granting/revoking from system Settings without recreating the Activity).
- If denied: rationale screen shown, request can be retried.
- If permanently denied (`shouldShowRequestPermissionRationale` returns `false` after a request): the CTA switches to "Open Settings", deep-linking to the app's system settings page.
- `WRITE_EXTERNAL_STORAGE`/`MANAGE_EXTERNAL_STORAGE` are never requested — scoped storage plus `MediaStore.createDeleteRequest` cover every write/delete path this app needs.

**Deletion permission (this app's minSdk, 30+):**
`MediaStore.createDeleteRequest` always triggers the system confirmation dialog for media the app doesn't own — this is OS-enforced and cannot be bypassed. The UI must not show a spinner in the moment before this dialog appears, or the delete request will look hung.

---

## 6. Glassmorphism Implementation

**No native blur dependency is used.** The frosted-glass look is achieved entirely with layered Compose modifiers (`Modifier.background(color.copy(alpha = ...))`, a semi-transparent border, and elevation/shadow) — there is no `RenderEffect`/`BlurEffect` real-time blur behind the cards.

**Card surface spec (`GlassCard`/`glassmorphism` modifier):**
- Surface tint: `GlassBorder = Color(0x40FFFFFF)` (~25% white) for the border.
- Corner radius: 24.dp on swipe cards, 16–20.dp on smaller surfaces (settings rows, badges).
- Shadow: standard Compose elevation.

**Swipe overlay badges:**
- "KEEP" badge: `KeepGreenOverlay = Color(0xBF4CAF50)` (~75% green).
- "DELETE" badge: `DeleteRedOverlay = Color(0xBFF44336)` (~75% red).
- Both are bold, rounded, and their opacity is driven directly by the drag offset (`progress = offsetX / swipeThreshold`).

There is no separate "reduced blur" fallback for low-RAM/older devices, because there is no real-time blur to fall back from — the same layered-alpha approach runs on every supported device (API 30+).

---

## 7. Performance Requirements

| Scenario | Target / actual behavior |
|---|---|
| Next batch preload | Triggered once the in-memory queue drops below `batchSize / 2`, so a fetch is usually in flight before the user runs out of cards |
| Image thumbnail render | Coil's in-memory + disk LRU cache serves repeat views instantly; first render is bound by MediaStore/Coil decode time |
| Card stack | Only the top card plus up to 3 background cards are composed at once; swiped-away cards are removed from `_mediaItems` immediately |
| Deletion batch size | A single `MediaStore.createDeleteRequest` is capped at 750 URIs (`MAX_DELETE_REQUEST_BATCH`) per system dialog, to stay well under Binder transaction limits; a larger pending set is chunked across multiple confirmations |

No fixed APK-size or memory ceilings are enforced or measured as part of this document; Coil manages its own image cache sizing.

---

## 8. Error Handling

| Scenario | Handling |
|---|---|
| `MediaStore.createDeleteRequest` throws (unresolvable URI, wedged provider, oversized batch) | Caught in `MainActivity`; the batch is un-marked back to a normal bin item (`deferDeletion`) and a recoverable error is surfaced via `_error`, instead of crashing the process |
| System delete dialog returns non-OK (cancelled) | `deferDeletion(ids)` un-marks the rows; they return to being ordinary, restorable bin items — no error shown |
| Media URI no longer valid (file moved/deleted externally) | `MediaDeletionHandler.filterExistingUris` confirms absence via a `ContentResolver` query; confirmed-missing rows are dropped from `bin_items` directly (skipping the system dialog) and a snackbar reads *"Photo no longer on device"* |
| A URI's absence *can't* be proven (`SecurityException` from scoped/partial access, malformed URI, unexpected query failure) | Treated as still-existing (fail-safe) and routed through the normal system delete dialog rather than silently dropped |
| Permission revoked mid-session | Re-checked on every `ON_RESUME`; navigates back to the Permission screen if no longer granted |
| MediaStore query fails (`SecurityException` / other) | Wrapped in `MediaAccessException`, surfaced through `_error` with a retry affordance; never crashes the ViewModel's coroutine |
| Empty gallery / all items processed | `reachedEnd` `StateFlow` drives an "All Done" empty state, distinct from "batch fetch failed" (which offers a retry instead) |

---

## 9. Project Structure

```
app/src/main/java/com/sese/keepix/
├── MainActivity.kt                 # Activity, permission flow, delete-request orchestration, NavHost
├── data/
│   ├── MediaRepository.kt          # MediaStore queries, keyset pagination
│   └── KeepixPreferences.kt        # SharedPreferences wrapper
├── db/
│   ├── AppDatabase.kt              # Room database + explicit migrations
│   ├── BinItemDao.kt / BinItemEntity.kt
│   └── KeptItemDao.kt / KeptItemEntity.kt
├── ui/
│   ├── theme/                      # Color.kt, Theme.kt, Type.kt — glassmorphism palette
│   ├── components/                 # GlassCard, GlassButton, GlassBackground, etc.
│   ├── SwipeScreen.kt              # Main card gesture layout
│   ├── FullscreenViewer.kt         # Photo/video fullscreen (SWIPE/BIN/KEPT modes)
│   ├── RecycleBinScreen.kt         # Bin grid + multi-select + Empty Bin
│   ├── KeptItemsScreen.kt          # Kept-items grid + unkeep
│   ├── SettingsScreen.kt           # Retention slider, Empty Bin, Privacy Policy link
│   ├── PermissionScreen.kt
│   ├── OnboardingScreen.kt
│   ├── PrivacyPolicyScreen.kt      # Native, bundled — no WebView, no network
│   └── KeepixViewModel.kt          # AndroidViewModel: StateFlow state + business logic
└── utils/
    ├── MediaDeletionHandler.kt     # MediaStore.createDeleteRequest + existence filtering
    └── SessionCleanupWorker.kt     # WorkManager CoroutineWorker: daily timed-retention sweep
```

---

## 10. Key Dependencies

Declared in `gradle/libs.versions.toml` (no `package.json`; this is a Gradle/Kotlin project). No dependency versions listed below are to be bumped without a stated reason — see the project's contribution constraints.

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.0"
composeBom = "2024.04.01"
room = "2.6.1"
coil = "2.6.0"
navigationCompose = "2.8.5"
workRuntime = "2.9.1"
lifecycleRuntimeKtx = "2.9.0"
```

```kotlin
// app/build.gradle.kts (abridged)
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.lifecycle.runtime.ktx)
implementation(libs.androidx.activity.compose)
implementation(platform(libs.androidx.compose.bom))
implementation(libs.androidx.ui)
implementation(libs.androidx.material3)
implementation(libs.androidx.navigation.compose)
implementation(libs.coil.compose)
implementation(libs.androidx.room.runtime)
implementation(libs.androidx.room.ktx)
ksp(libs.androidx.room.compiler)
implementation(libs.androidx.work.runtime.ktx)
```

---

## 11. Out of Scope for v1.0 (Technical)

- Cloud sync or backup
- iOS build (this is an Android-only, native Kotlin project — there is no cross-platform layer to port)
- Widget / home screen shortcut
- Custom MediaStore write operations beyond the confirmed-delete flow
- Content sharing from within the app
- Cross-device sync
