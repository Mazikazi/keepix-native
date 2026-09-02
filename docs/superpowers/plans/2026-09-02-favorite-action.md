# Favorite Action Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third triage outcome — favorite — as a starred subset of kept, reachable by swipe-up and a ★ button, and synced to Android's system `IS_FAVORITE` flag.

**Architecture:** Favorite is a flag on `kept_items`, not a third table. Syncing to MediaStore reuses the existing mark-then-confirm state machine: the app writes intent to Room instantly, and a batched `MediaStore.createFavoriteRequest` confirms it later. A single dialog gate in `MainActivity` serialises the favorite prompt against the existing deletion prompt.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, Coil. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-02-favorite-action-design.md`

## Global Constraints

- Kotlin + Compose (Material 3), Room, WorkManager, Coil. **No new dependencies.**
- minSdk 30, targetSdk 35, compileSdk 35. Build with `export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"` (system JDK is 22; AGP 8.7.3 rejects it).
- Design language stays `glassmorphism`. No `maxBox`/`AccentsAt`/`MaxCard`/`MaxBackground`.
- **Never drop a Room row for an item whose file deletion was not confirmed.**
- Room migrations are explicit. `fallbackToDestructiveMigration()` must not appear anywhere, including test setup.
- No INTERNET permission. No file *contents* are read, copied or modified — this feature writes MediaStore *metadata* only.
- `PRIVACY.md` and `ui/PrivacyPolicyScreen.kt` must stay in lockstep; the screen is the source of truth.
- Verify with: `./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug`
- **No device or emulator is available.** Instrumented tests are written and compiled, never run. Never claim an instrumented test passes.

---

## File Structure

| File | Responsibility |
|---|---|
| `db/KeptItemEntity.kt` | +`isFavorite`, +`pendingFavoriteSync` |
| `db/AppDatabase.kt` | v5, `MIGRATION_4_5`, registered in `getDatabase()` |
| `db/KeptItemDao.kt` | favorite queries, chunked like `BinItemDao` |
| `utils/MediaUriFilter.kt` | **new** — `filterExistingUris`, extracted so deletion and favorite share it |
| `utils/MediaDeletionHandler.kt` | delegates filtering to `MediaUriFilter` |
| `utils/MediaFavoriteHandler.kt` | **new** — `createFavoriteRequest` wrapper |
| `ui/KeepixViewModel.kt` | favorite intent + sync state machine |
| `MainActivity.kt` | favorite launcher + **shared dialog gate** |
| `ui/SwipeScreen.kt` | `SwipeAction`, Y axis, ★ badge, ★ button |
| `ui/KeptItemsScreen.kt` | filter chips, star badge, toggle |
| `ui/FullscreenViewer.kt` | ★ in the action bar |
| `ui/theme/Color.kt` | `FavoriteGoldOverlay` |

---

### Task 1: Room v5 — favorite columns

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/db/KeptItemEntity.kt`
- Modify: `app/src/main/java/com/sese/keepix/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/sese/keepix/db/KeptItemDao.kt`
- Test: `app/src/androidTest/java/com/sese/keepix/db/RoomMigration4To5Test.kt` (create)

**Interfaces:**
- Produces: `KeptItemEntity.isFavorite: Boolean`, `KeptItemEntity.pendingFavoriteSync: Boolean`; `KeptItemDao.setFavorite(ids: List<Int>, favorite: Boolean)`, `.getPendingFavoriteSync(): Flow<List<KeptItemEntity>>`, `.clearPendingFavoriteSync(ids: List<Int>)`, `.getFavoriteItems(): Flow<List<KeptItemEntity>>`
- Note `KeptItemEntity.id` is **`Int`**, unlike `BinItemEntity.id` which is `Long`. All kept-item id lists are `List<Int>`.

- [ ] **Step 1: Add the columns**

In `KeptItemEntity.kt`, append to the data class:

```kotlin
    /** The user's intent. This app's source of truth for the star. */
    val isFavorite: Boolean = false,
    /**
     * The MediaStore IS_FAVORITE flag does not match [isFavorite] yet. Direction
     * agnostic: the target state is whatever [isFavorite] says, so one flag covers
     * both favoriting and un-favoriting.
     */
    val pendingFavoriteSync: Boolean = false
```

- [ ] **Step 2: Bump the database and add the migration**

In `AppDatabase.kt`: change `version = 4` to `version = 5`, add beside `MIGRATION_3_4`:

```kotlin
        /**
         * Adds [KeptItemEntity.isFavorite] and [KeptItemEntity.pendingFavoriteSync].
         * Existing rows default to 0, so an upgrade never marks anything favorite
         * and never queues a sync.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE kept_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE kept_items ADD COLUMN pendingFavoriteSync INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
```

and change the builder line to `.addMigrations(MIGRATION_3_4, MIGRATION_4_5)`.

- [ ] **Step 3: Add the DAO methods**

In `KeptItemDao.kt`, add the chunk constant at file top (mirroring `BinItemDao.kt:12`) and the methods:

```kotlin
/**
 * SQLite (API 30-31) caps bound variables at 999. Chunked exactly as
 * [com.sese.keepix.db.BinItemDao] does, so no call site has to remember to.
 */
private const val SQL_ID_CHUNK_SIZE = 900
```

```kotlin
    @Query("SELECT * FROM kept_items WHERE isFavorite = 1 ORDER BY keptAt DESC")
    fun getFavoriteItems(): Flow<List<KeptItemEntity>>

    /** Rows whose star has not yet been written to MediaStore. */
    @Query("SELECT * FROM kept_items WHERE pendingFavoriteSync = 1 ORDER BY keptAt DESC")
    fun getPendingFavoriteSync(): Flow<List<KeptItemEntity>>

    @Query("UPDATE kept_items SET isFavorite = :favorite, pendingFavoriteSync = 1 WHERE id IN (:ids)")
    suspend fun setFavoriteChunk(ids: List<Int>, favorite: Boolean)

    /**
     * Records the user's intent and queues a MediaStore sync. Never writes to
     * MediaStore itself — that only happens after the user confirms the system
     * dialog. Chunked internally.
     */
    suspend fun setFavorite(ids: List<Int>, favorite: Boolean) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { setFavoriteChunk(it, favorite) }
    }

    @Query("UPDATE kept_items SET pendingFavoriteSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingFavoriteSyncChunk(ids: List<Int>)

    /**
     * Clears the sync flag. Called both when the system dialog confirms the write
     * and when it is cancelled — a cancelled star stays set in-app but stops
     * re-prompting, which is the deliberate difference from deletion (nothing was
     * destroyed, so there is nothing to undo).
     */
    suspend fun clearPendingFavoriteSync(ids: List<Int>) {
        ids.chunked(SQL_ID_CHUNK_SIZE).forEach { clearPendingFavoriteSyncChunk(it) }
    }
```

- [ ] **Step 4: Build, which regenerates the schema**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin --console=plain
```
Expected: BUILD SUCCESSFUL, and `app/schemas/com.sese.keepix.db.AppDatabase/5.json` now exists.

- [ ] **Step 5: Write the migration test**

Create `app/src/androidTest/java/com/sese/keepix/db/RoomMigration4To5Test.kt`. Model it on the existing `RoomMigration3To4Test.kt` — read that file first and match its structure.

It must assert **row survival**, not merely that the migration runs:

```kotlin
package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-4-5"

@RunWith(AndroidJUnit4::class)
class RoomMigration4To5Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate4To5_preservesKeptRows_andDefaultsFavoriteFlagsToFalse() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO kept_items " +
                    "(mediaId, mediaUri, displayName, mediaType, dateTaken, keptAt, width, height, durationMs) " +
                    "VALUES (42, 'content://media/external/images/media/42', 'a.jpg', 'IMAGE', 100, 200, 4, 3, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true, AppDatabase.MIGRATION_4_5
        )

        db.query("SELECT mediaId, isFavorite, pendingFavoriteSync FROM kept_items").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
            assertFalse("isFavorite must default to false", c.getInt(1) != 0)
            assertFalse("pendingFavoriteSync must default to false", c.getInt(2) != 0)
        }
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest assembleDebugAndroidTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL. The instrumented test compiles only — there is no device. Do not claim it passes.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sese/keepix/db/ app/schemas/ app/src/androidTest/java/com/sese/keepix/db/RoomMigration4To5Test.kt
git commit -m "feat(db): add favorite columns to kept_items (Room v5)"
```

---

### Task 2: Media URI filtering, shared — and the favorite request

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/MediaUriFilter.kt`
- Modify: `app/src/main/java/com/sese/keepix/utils/MediaDeletionHandler.kt`
- Create: `app/src/main/java/com/sese/keepix/utils/MediaFavoriteHandler.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `MediaUriFilter.UriFilterResult(existing, missing)`, `MediaUriFilter.filterExistingUris(context, uris, hasOnlyPartialMediaAccess): UriFilterResult`; `MediaFavoriteHandler.getFavoriteIntent(context, uris, favorite: Boolean): PendingIntent`

- [ ] **Step 1: Extract the filter**

`filterExistingUris` currently lives in `MediaDeletionHandler` but is not deletion-specific — favoriting needs the same "does this URI still resolve" check. Move it, unchanged, into a new `utils/MediaUriFilter.kt` as an `object MediaUriFilter`.

**Move the code verbatim, including its full KDoc.** That doc explains why a `SecurityException`, a null cursor, and a partial-access empty cursor must all route to `existing` rather than `missing` — reasoning that took three review rounds to get right. Do not paraphrase or shorten it. Rename the `TAG` constant to `"MediaUriFilter"`.

- [ ] **Step 2: Point the deletion handler at it**

In `MediaDeletionHandler.kt`, delete `UriFilterResult` and `filterExistingUris`, and keep `getDeletionIntent` as-is. Update the one call site in `MainActivity.kt` from `MediaDeletionHandler.filterExistingUris(...)` to `MediaUriFilter.filterExistingUris(...)`, and its `MediaDeletionHandler.UriFilterResult` references to `MediaUriFilter.UriFilterResult`.

- [ ] **Step 3: Verify the extraction changed nothing**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL, 49 tests, 0 failures. `MediaRepositoryTest` and the deletion state-machine tests must be unaffected — if any fails, the move was not verbatim.

- [ ] **Step 4: Write the favorite handler**

Create `app/src/main/java/com/sese/keepix/utils/MediaFavoriteHandler.kt`:

```kotlin
package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The only way this app writes Android's system favorite flag.
 *
 * The app does not own the MediaStore rows it stars, so the flag can only be set
 * through [MediaStore.createFavoriteRequest] and the system confirmation dialog it
 * produces. Nothing is written until that dialog returns `RESULT_OK`.
 *
 * This writes MediaStore *metadata* only — no file contents are read, copied or
 * modified.
 */
object MediaFavoriteHandler {

    /**
     * Returns a [PendingIntent] prompting the user to confirm setting or clearing
     * the favorite flag on [uris]. Requires minSdk 30.
     *
     * [MediaStore.createFavoriteRequest] takes ONE boolean for the whole batch, so
     * favoriting and un-favoriting cannot share a request. Callers must partition
     * by target state and launch the two requests sequentially — never together,
     * or the second `IntentSender` is dropped.
     *
     * @param uris must be non-empty and already filtered by
     *   [MediaUriFilter.filterExistingUris]; a URI whose row no longer exists makes
     *   the whole request fail.
     * @param favorite the target state for every URI in this batch.
     */
    fun getFavoriteIntent(
        context: Context,
        uris: List<Uri>,
        favorite: Boolean
    ): PendingIntent {
        require(uris.isNotEmpty()) { "getFavoriteIntent requires a non-empty URI list" }
        return MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
    }
}
```

- [ ] **Step 5: Verify**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL, 49 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/ app/src/main/java/com/sese/keepix/MainActivity.kt
git commit -m "refactor: extract MediaUriFilter, add MediaFavoriteHandler"
```

---

### Task 3: ViewModel favorite state machine

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/KeepixViewModel.kt`
- Test: `app/src/test/java/com/sese/keepix/ui/FavoriteStateMachineTest.kt` (create)

**Interfaces:**
- Consumes: `KeptItemDao.setFavorite/clearPendingFavoriteSync/getPendingFavoriteSync/getFavoriteItems` (Task 1).
- Produces: `KeepixViewModel.favoriteMedia(mediaItem: MediaItem)`, `.toggleFavorite(item: KeptItemEntity)`, `.pendingFavoriteSync: StateFlow<List<KeptItemEntity>>`, `.favoritePromptedThisSession: StateFlow<Boolean>`, `.confirmFavoriteSync(ids: List<Int>)`, `.deferFavoriteSync(ids: List<Int>)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sese/keepix/ui/FavoriteStateMachineTest.kt`. Read the existing `DeletionStateMachineTest.kt` and `ViewModelTestHarness.kt` **first** and mirror them — the harness uses `Unsafe.allocateInstance` plus reflection to run real ViewModel bytecode without Robolectric, and injects MockK-mocked DAOs verified with `coVerify`.

> **Verified:** the harness is at `app/src/test/java/com/sese/keepix/testutil/ViewModelTestHarness.kt`
> (package `com.sese.keepix.testutil`, *not* `...ui`) and already exposes
> `newViewModel(application, binItemDao = mockk(relaxed = true), keptItemDao = mockk(relaxed = true), …)`,
> so `keptItemDao` needs no new injection point. Import it from `testutil`.

```kotlin
    @Test
    fun `favoriteMedia records intent and queues a sync, writing nothing to MediaStore`() = runTest {
        val vm = harness.newViewModel(keptItemDao = keptItemDao)

        vm.favoriteMedia(mediaItem(id = 7L))
        advanceUntilIdle()

        coVerify { keptItemDao.insert(match { it.isFavorite && it.pendingFavoriteSync }) }
    }

    @Test
    fun `confirmFavoriteSync clears the pending flag and leaves the star set`() = runTest {
        val vm = harness.newViewModel(keptItemDao = keptItemDao)

        vm.confirmFavoriteSync(listOf(1, 2))
        advanceUntilIdle()

        coVerify(exactly = 1) { keptItemDao.clearPendingFavoriteSync(listOf(1, 2)) }
        coVerify(exactly = 0) { keptItemDao.setFavorite(any(), any()) }
    }

    @Test
    fun `deferFavoriteSync stops re-prompting without unsetting the star`() = runTest {
        val vm = harness.newViewModel(keptItemDao = keptItemDao)

        vm.deferFavoriteSync(listOf(3))
        advanceUntilIdle()

        assertTrue(vm.favoritePromptedThisSession.value)
        // Nothing was destroyed, so unlike deletion there is nothing to undo:
        // the star stays set in-app, the sync flag is simply cleared.
        coVerify(exactly = 1) { keptItemDao.clearPendingFavoriteSync(listOf(3)) }
        coVerify(exactly = 0) { keptItemDao.setFavorite(any(), any()) }
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests '*FavoriteStateMachineTest*' --console=plain
```
Expected: FAIL — compilation error, `favoriteMedia` unresolved.

- [ ] **Step 3: Implement**

In `KeepixViewModel.kt`, beside the existing deletion members:

```kotlin
    val favoriteItems: StateFlow<List<KeptItemEntity>> = keptItemDao.getFavoriteItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingFavoriteSync: StateFlow<List<KeptItemEntity>> =
        keptItemDao.getPendingFavoriteSync()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _favoritePromptedThisSession = MutableStateFlow(false)

    /**
     * Mirrors [promptedThisSession] for the favorite dialog. A LaunchedEffect
     * observing [pendingFavoriteSync] needs this in its key list to notice a
     * re-arm when the pending set's *content* has not changed.
     */
    val favoritePromptedThisSession: StateFlow<Boolean> =
        _favoritePromptedThisSession.asStateFlow()
```

`favoriteMedia` reuses the keep path — favoriting also keeps:

```kotlin
    /**
     * Swipe-up / ★ button. Keeps the item AND stars it, in one insert: favorite is
     * a starred subset of kept, never a separate destination.
     */
    fun favoriteMedia(mediaItem: MediaItem) {
        viewModelScope.launch {
            keptItemDao.insert(
                KeptItemEntity(
                    mediaId = mediaItem.id,
                    mediaUri = mediaItem.uri.toString(),
                    displayName = mediaItem.displayName,
                    mediaType = if (mediaItem.isVideo) "VIDEO" else "IMAGE",
                    dateTaken = mediaItem.dateAdded * 1000,
                    keptAt = System.currentTimeMillis(),
                    width = mediaItem.width,
                    height = mediaItem.height,
                    durationMs = mediaItem.durationMs,
                    isFavorite = true,
                    pendingFavoriteSync = true
                )
            )
            keptMediaIds = keptMediaIds + mediaItem.id
            _sessionKeptCount.value++
            removeSwipedItem(mediaItem)
        }
    }

    /** Star toggle from the Kept grid or the fullscreen viewer. */
    fun toggleFavorite(item: KeptItemEntity) {
        viewModelScope.launch {
            try {
                keptItemDao.setFavorite(listOf(item.id), !item.isFavorite)
                _favoritePromptedThisSession.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle favorite", e)
                _error.value = "Couldn't update that favorite."
            }
        }
    }

    /**
     * The system dialog confirmed the MediaStore write for [ids]. Clear the sync
     * flag; [KeptItemEntity.isFavorite] already holds the intended value.
     */
    fun confirmFavoriteSync(ids: List<Int>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                keptItemDao.clearPendingFavoriteSync(ids)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear favorite sync flag", e)
                _error.value = "Some favorites could not be saved."
            }
        }
    }

    /**
     * The user cancelled the favorite dialog. Unlike [deferDeletion] this does NOT
     * revert the user's intent: nothing was destroyed, the star simply did not
     * reach MediaStore. Clearing the sync flag stops it re-prompting on every
     * future launch; the star stays set in-app.
     */
    fun deferFavoriteSync(ids: List<Int>) {
        _favoritePromptedThisSession.value = true
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                keptItemDao.clearPendingFavoriteSync(ids)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear deferred favorite sync flag", e)
                _error.value = "Some favorites could not be saved."
            }
        }
    }
```

- [ ] **Step 4: Run the tests**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL, all previous tests plus the three new ones passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/KeepixViewModel.kt app/src/test/java/com/sese/keepix/ui/FavoriteStateMachineTest.kt
git commit -m "feat(vm): add favorite intent and MediaStore sync state machine"
```

---

### Task 4: MainActivity — favorite launcher and the shared dialog gate

**This is the highest-risk task in the plan.** Three defects in the preceding correctness pass came from two individually-correct changes invalidating each other's preconditions. Read the existing deletion `LaunchedEffect` and its launcher callback in full before writing anything.

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt`
- Modify: `PRIVACY.md`
- Modify: `app/src/main/java/com/sese/keepix/ui/PrivacyPolicyScreen.kt`

**Interfaces:**
- Consumes: `pendingFavoriteSync`, `favoritePromptedThisSession`, `confirmFavoriteSync`, `deferFavoriteSync` (Task 3); `MediaFavoriteHandler.getFavoriteIntent`, `MediaUriFilter.filterExistingUris` (Task 2).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the shared gate**

Both prompts must serialise. Add one piece of state that both effects read and set, alongside the existing `deletionInFlightIds`:

```kotlin
    // Both system dialogs (delete and favorite) go through this one gate.
    // Launching two IntentSenders in the same frame makes Android show one and
    // silently drop the other, leaving the loser's rows pending forever behind
    // its own latched guard. Deletion takes priority; the favorite prompt waits
    // for the next recomposition after the gate clears.
    var systemDialogInFlight by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Make the deletion effect own the gate**

In the existing deletion `LaunchedEffect`, add `if (systemDialogInFlight) return@LaunchedEffect` beside the existing `deletionInFlightIds != null` check, and set `systemDialogInFlight = true` at the same point `deletionInFlightIds` is assigned. In the `deleteResultLauncher` callback, set `systemDialogInFlight = false` at the same point `deletionInFlightIds` is nulled. Add `systemDialogInFlight` to the effect's key list.

Do **not** change any other behaviour in that effect — its filtering, missing-id handling, chunking and re-arm logic are all reviewed and correct.

- [ ] **Step 3: Extract and test the batch partition**

`createFavoriteRequest` takes one boolean for the whole batch, so a session holding
both stars and un-stars needs two dialogs. Putting that rule inside the effect
would make it untestable, so it goes in a pure top-level function in
`MainActivity.kt`:

```kotlin
/**
 * Picks the single-target-state batch to send next.
 *
 * [MediaStore.createFavoriteRequest] takes ONE boolean for the whole batch, so a
 * pending set containing both stars and un-stars cannot go in one request.
 * Favorites go first; the un-favorites are picked up on the next pass once this
 * request resolves and clears the shared dialog gate. Returning a mixed batch
 * would silently apply one target state to both groups.
 */
fun selectFavoriteBatch(pending: List<KeptItemEntity>): List<KeptItemEntity> {
    val toFavorite = pending.filter { it.isFavorite }
    return if (toFavorite.isNotEmpty()) toFavorite else pending.filter { !it.isFavorite }
}
```

Create `app/src/test/java/com/sese/keepix/FavoriteBatchTest.kt`:

```kotlin
package com.sese.keepix

import com.sese.keepix.db.KeptItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteBatchTest {

    private fun item(id: Int, fav: Boolean) = KeptItemEntity(
        id = id, mediaId = id.toLong(), mediaUri = "content://m/$id",
        displayName = "$id.jpg", mediaType = "IMAGE", dateTaken = 0L, keptAt = 0L,
        isFavorite = fav, pendingFavoriteSync = true
    )

    @Test
    fun `a mixed pending set never produces a mixed batch`() {
        val batch = selectFavoriteBatch(
            listOf(item(1, true), item(2, false), item(3, true))
        )
        assertTrue("every item in a batch shares one target state",
            batch.all { it.isFavorite == batch.first().isFavorite })
    }

    @Test
    fun `favorites are sent before un-favorites`() {
        val batch = selectFavoriteBatch(listOf(item(1, false), item(2, true)))
        assertEquals(listOf(2), batch.map { it.id })
    }

    @Test
    fun `an all-unfavorite set still produces a batch`() {
        val batch = selectFavoriteBatch(listOf(item(1, false), item(2, false)))
        assertEquals(listOf(1, 2), batch.map { it.id })
    }

    @Test
    fun `an empty pending set produces an empty batch`() =
        assertTrue(selectFavoriteBatch(emptyList()).isEmpty())
}
```

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests '*FavoriteBatchTest*' --console=plain
```
Expected: FAIL first (unresolved `selectFavoriteBatch`), then PASS once the function above is added.

- [ ] **Step 4: Add the favorite launcher**

```kotlin
    var favoriteInFlightIds by remember { mutableStateOf<List<Int>?>(null) }

    val favoriteResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ids = favoriteInFlightIds
        favoriteInFlightIds = null
        systemDialogInFlight = false
        if (ids != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.confirmFavoriteSync(ids)
            } else {
                viewModel.deferFavoriteSync(ids)
            }
        }
    }
```

- [ ] **Step 5: Add the favorite prompt effect**

```kotlin
    // Batched favorite sync. Deliberately mirrors the deletion effect's key list
    // and guards; see that effect for why each key is needed.
    LaunchedEffect(
        pendingFavoriteSync, favoritePromptedThisSession,
        hasPermission, resumeTick, systemDialogInFlight
    ) {
        if (!hasPermission) return@LaunchedEffect
        if (systemDialogInFlight || favoriteInFlightIds != null) return@LaunchedEffect
        if (pendingFavoriteSync.isEmpty() || favoritePromptedThisSession) return@LaunchedEffect
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }

        val batch = selectFavoriteBatch(pendingFavoriteSync)
        if (batch.isEmpty()) return@LaunchedEffect
        val targetState = batch.first().isFavorite

        val itemUris = batch.map { it to Uri.parse(it.mediaUri) }
        val filterResult = MediaUriFilter.filterExistingUris(
            context,
            itemUris.map { it.second },
            hasOnlyPartialMediaAccess = hasOnlyPartialMediaAccess(context)
        )

        // A row whose file is gone can never be starred. Clear its sync flag so it
        // stops re-prompting; the kept row itself is left alone.
        val missingUris = filterResult.missing.toSet()
        val missingIds = itemUris.filter { it.second in missingUris }.map { it.first.id }
        if (missingIds.isNotEmpty()) viewModel.confirmFavoriteSync(missingIds)

        val existingUris = filterResult.existing.toSet()
        val sendable = itemUris.filter { it.second in existingUris }
        if (sendable.isEmpty()) return@LaunchedEffect

        val ids = sendable.map { it.first.id }
        try {
            val intent = MediaFavoriteHandler.getFavoriteIntent(
                context, sendable.map { it.second }, targetState
            )
            favoriteInFlightIds = ids
            systemDialogInFlight = true
            favoriteResultLauncher.launch(
                IntentSenderRequest.Builder(intent.intentSender).build()
            )
        } catch (e: Exception) {
            favoriteInFlightIds = null
            systemDialogInFlight = false
            viewModel.deferFavoriteSync(ids)
            viewModel.reportError("Couldn't open the favorite confirmation.")
        }
    }
```

- [ ] **Step 6: Update the privacy policy — both files, in lockstep**

In `PrivacyPolicyScreen.kt` section 4, add after the storage description:

```kotlin
                BodyText(
                    "When you favorite an item, Keepix asks Android to set that " +
                        "photo or video's system favorite flag, so the star also " +
                        "appears in your gallery app. Android shows you a " +
                        "confirmation dialog first, and nothing is written unless " +
                        "you confirm. This changes only that flag — no file " +
                        "contents are read, copied or modified."
                )
```

Then make the equivalent edit to `PRIVACY.md` so the two say the same thing. The screen is the source of truth.

- [ ] **Step 7: Verify**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 8: Self-check the gate before committing**

Trace and write down in your report: with one bin item pending deletion and one kept item pending favorite sync at the same time, exactly which dialog opens first, what clears the gate, and what causes the second to open. If either can be starved, the gate is wrong.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/sese/keepix/MainActivity.kt app/src/main/java/com/sese/keepix/ui/PrivacyPolicyScreen.kt PRIVACY.md
git commit -m "feat: wire favorite sync dialog behind a shared system-dialog gate"
```

---

### Task 5: SwipeScreen — the third action

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/SwipeScreen.kt`
- Modify: `app/src/main/java/com/sese/keepix/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt` (wire `onSwipedUp`)
- Test: `app/src/test/java/com/sese/keepix/ui/SwipeAxisTest.kt` (create)

**Interfaces:**
- Consumes: `KeepixViewModel.favoriteMedia` (Task 3).
- Produces: `SwipeAction` enum (`DELETE`, `KEEP`, `FAVORITE`); `resolveSwipeAction(dx: Float, dy: Float, threshold: Float): SwipeAction?`

- [ ] **Step 1: Add the color**

In `Color.kt`, beside `KeepGreenOverlay` / `DeleteRedOverlay`:

```kotlin
val FavoriteGold = Color(0xFFFFC107)
val FavoriteGoldOverlay = Color(0xBFFFC107)  // 75%
```

- [ ] **Step 2: Write the failing axis test**

Create `app/src/test/java/com/sese/keepix/ui/SwipeAxisTest.kt`:

```kotlin
package com.sese.keepix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeAxisTest {

    private val threshold = 100f

    @Test fun `right past threshold keeps`() =
        assertEquals(SwipeAction.KEEP, resolveSwipeAction(150f, 0f, threshold))

    @Test fun `left past threshold deletes`() =
        assertEquals(SwipeAction.DELETE, resolveSwipeAction(-150f, 0f, threshold))

    @Test fun `up past threshold favorites`() =
        assertEquals(SwipeAction.FAVORITE, resolveSwipeAction(0f, -150f, threshold))

    @Test fun `down does nothing`() =
        assertNull(resolveSwipeAction(0f, 150f, threshold))

    @Test fun `below threshold does nothing`() =
        assertNull(resolveSwipeAction(50f, -50f, threshold))

    // Horizontal wins ties: keeping is the common case, favoriting is deliberate,
    // so an ambiguous diagonal must not silently star something.
    @Test fun `equal diagonal resolves horizontally`() =
        assertEquals(SwipeAction.KEEP, resolveSwipeAction(150f, -150f, threshold))

    @Test fun `mostly-vertical diagonal favorites`() =
        assertEquals(SwipeAction.FAVORITE, resolveSwipeAction(40f, -150f, threshold))
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests '*SwipeAxisTest*' --console=plain
```
Expected: FAIL — `SwipeAction` and `resolveSwipeAction` unresolved.

- [ ] **Step 4: Implement the resolver**

At the top level of `SwipeScreen.kt`:

```kotlin
enum class SwipeAction { DELETE, KEEP, FAVORITE }

/**
 * Which action a released drag commits to, or null to spring back.
 *
 * Horizontal wins ties (`>=`): keep/delete are the common outcomes and favorite is
 * deliberate, so an ambiguous diagonal must never silently star an item. Only
 * upward vertical commits — downward springs back, leaving room for a future
 * pull-down gesture without stealing it now.
 */
fun resolveSwipeAction(dx: Float, dy: Float, threshold: Float): SwipeAction? {
    val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
    return when {
        horizontal && dx >= threshold -> SwipeAction.KEEP
        horizontal && dx <= -threshold -> SwipeAction.DELETE
        !horizontal && dy <= -threshold -> SwipeAction.FAVORITE
        else -> null
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests '*SwipeAxisTest*' --console=plain
```
Expected: PASS, 7 tests.

- [ ] **Step 6: Rework `performSwipe` to take an action**

Change `performSwipe(direction: Float, startOffset: Float)` to `performSwipe(action: SwipeAction, startOffset: Offset)`, dispatching to `onSwipedLeft` / `onSwipedRight` / a new `onSwipedUp` parameter on `SwipeScreen`. Keep the existing `swipeInProgress` guard exactly as it is — it already covers rapid taps and a tap racing a drag.

`OutgoingCard` gains a `SwipeAction` instead of a `Float` direction, and `OutgoingSwipeCard` flies the card up (negative Y) for `FAVORITE` and horizontally for the other two.

In `SwipeableCard`, add `offsetY` alongside `offsetX`, and stop hardcoding Y to `0` in the `.offset {}` block:

```kotlin
            .offset {
                IntOffset(
                    (if (isDragging) offsetX else animatedOffsetX).roundToInt(),
                    (if (isDragging) offsetY else animatedOffsetY).roundToInt()
                )
            }
```

Route `onDragEnd` through the resolver rather than comparing `offsetX` inline:

```kotlin
                    onDragEnd = {
                        isDragging = false
                        val action = resolveSwipeAction(offsetX, offsetY, swipeThreshold)
                        if (action != null && !swipeHandled) {
                            swipeHandled = true
                            onSwipeProgress(0f)
                            onSwiped(action, Offset(offsetX, offsetY))
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
```

and accumulate both axes in the drag callback (`offsetX += dragAmount.x; offsetY += dragAmount.y`). Keep `swipeHandled` and the `interactive` guard exactly as they are.

Report vertical progress to the parent alongside the existing horizontal `progress`, so the ★ badge can react to upward drag the way the KEEP/DELETE badges react to horizontal drag.

- [ ] **Step 7: Add the ★ badge and the middle button**

The badge mirrors the existing KEEP/DELETE overlay badges, driven by upward drag progress, tinted `FavoriteGoldOverlay`.

The button goes **between** DELETE and KEEP in the existing `GlassCard(cornerRadius = 40.dp)` row — same 56dp circle, same `Arrangement.spacedBy(32.dp)`:

```kotlin
                        IconButton(
                            onClick = { performSwipe(SwipeAction.FAVORITE, Offset.Zero) },
                            enabled = !swipeInProgress,
                            modifier = Modifier
                                .size(56.dp)
                                .background(FavoriteGoldOverlay, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Favorite this item",
                                tint = Color.White
                            )
                        }
```

- [ ] **Step 8: Wire it in MainActivity**

Pass `onSwipedUp = { item -> viewModel.favoriteMedia(item) }` to `SwipeScreen`.

- [ ] **Step 9: Verify**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/SwipeScreen.kt app/src/main/java/com/sese/keepix/ui/theme/Color.kt app/src/main/java/com/sese/keepix/MainActivity.kt app/src/test/java/com/sese/keepix/ui/SwipeAxisTest.kt
git commit -m "feat(swipe): add favorite as swipe-up and a middle button"
```

---

### Task 6: Kept screen — filter and star toggle

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/KeptItemsScreen.kt`
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt`

**Interfaces:**
- Consumes: `KeepixViewModel.toggleFavorite` (Task 3), `FavoriteGold` (Task 5).

- [ ] **Step 1: Add the filter chips**

Add `FilterChip`s for **All** and **★ Favorites** above the grid, with the selection held in `rememberSaveable` so it survives rotation. Filter the rendered list by `isFavorite` when Favorites is selected.

- [ ] **Step 2: Add the star badge and toggle**

Show a `FavoriteGold` star on tiles where `isFavorite` is true, and add an `onToggleFavorite` parameter wired to `viewModel.toggleFavorite`. Keep the existing `combinedClickable` long-press → unkeep confirmation intact.

- [ ] **Step 3: Verify the grid survives the list changing underneath the filter**

**Already done — verify, don't re-add.** `KeptItemsScreen.kt:105` already reads
`items(items, key = { it.id })`, added by an earlier fix wave. Confirm it is still
there after your filter change, since a row vanishing while the Favorites filter is
active must not strand state or crash the grid.

Watch for the duplicate-key hazard this introduces: a `LazyLayout` key that repeats
is a runtime crash, and `kept_items` has no unique index on `mediaId`/`mediaUri` —
uniqueness rests on the in-memory `keptMediaIds` exclusion set. Filtering must not
be able to produce the same Room id twice.

- [ ] **Step 4: Verify**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/KeptItemsScreen.kt app/src/main/java/com/sese/keepix/MainActivity.kt
git commit -m "feat(kept): add favorites filter and star toggle"
```

---

### Task 7: Fullscreen viewer — ★ in the action bar

**This file is the most defect-prone in the codebase** — four latent no-ops and three fix waves during the correctness pass. Make the smallest possible change. Do not restructure the gesture arbitration, the pager, the zoom state, or the video lifecycle.

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/FullscreenViewer.kt`
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt`

**Interfaces:**
- Consumes: `KeepixViewModel.toggleFavorite` (Task 3), `FavoriteGold` (Task 5).

- [ ] **Step 1: Add an optional star to the action bar**

Add a nullable `onToggleFavorite: ((Uri) -> Boolean)?` and an `isFavorite: (Uri) -> Boolean` to `ViewerActionBar`, rendering a ★ only when the callback is non-null. Wire it in `SWIPE` and `KEPT` modes only; pass null in `BIN` mode.

Follow the existing action-bar callback convention exactly: the callbacks return `Boolean` (false = the target row is gone), and `MainActivity` pops only on a hit while the viewer shows its own in-viewer notice on a miss.

- [ ] **Step 2: Verify nothing regressed**

Run:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug --console=plain
```
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/FullscreenViewer.kt app/src/main/java/com/sese/keepix/MainActivity.kt
git commit -m "feat(viewer): add favorite toggle to the action bar"
```

---

## Device Verification

Nothing in this plan runs on hardware in this environment. A human must verify:

1. Favorite several items, leave the screen → **one** dialog → confirm → the star appears in Google Photos.
2. Same, but **cancel** → items stay starred in Keepix, no re-prompt until next launch.
3. Favorite some and un-favorite others in one session → **two** dialogs, sequenced, correct in both directions.
4. **Bin an item and favorite another in the same session** → both dialogs appear one after the other, neither swallowed. *This is the shared-gate regression test.*
5. Swipe diagonally up-and-right → keeps, does not favorite.
6. ★ button and swipe-up produce identical results.
7. Rotate with the Favorites filter active → the filter survives.
8. Run the instrumented suite, starting with `RoomMigration4To5Test`.
