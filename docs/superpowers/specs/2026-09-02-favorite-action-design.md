# Keepix Favorite Action — Design

- **Status:** Approved
- **Date:** 2026-09-02
- **Platform:** Android (Kotlin, Jetpack Compose)

---

## 1. Problem

Keepix triages a photo library into two outcomes: swipe left bins an item, swipe
right keeps it. There is no way to mark an item as *special* — only as *decided*.

Users want a third outcome, **favorite**, reachable both by gesture and by button.

## 2. What already exists

Two facts constrain the design.

**The KEEP and DELETE buttons are already built.** `SwipeScreen.kt:304` and `:317`
render them, and both route through the same local `performSwipe` that the drag
gesture calls, so tap and swipe produce identical animation and outcome. Only the
favorite action is new work.

**Keepix has never modified a media file.** Every permission it holds is `READ_*`.
`PRIVACY.md` and `PrivacyPolicyScreen.kt` both state that no media files are
copied, uploaded or transmitted. Any feature that writes file *contents* breaks
that promise; a feature that writes MediaStore *metadata* does not, but still
requires an amended policy line.

## 3. Goals

- A third triage outcome that feels identical in weight to keep and delete.
- Favorites that are real outside Keepix — visible in Google Photos and the stock
  gallery, surviving uninstall.
- Zero added friction during fast swiping.
- No new dependencies.

## 4. Non-goals

- **Media compression.** Separate project, separate spec. It would require
  decoding, re-encoding and writing files back, needing `createWriteRequest`
  confirmations and a materially different privacy policy.
- A dedicated Favorites screen. Favorites are a filter on the existing Kept screen.
- Sorting, tagging, albums, or any organisation beyond a single star.

## 5. Model

Favorite is **a starred subset of kept**, not a third destination. Favoriting an
item also keeps it. This avoids an item existing in two lists at once, and matches
the near-universal convention that a favorite is a marked member of a collection
rather than a separate collection.

### 5.1 Schema

`KeptItemEntity` gains two columns:

```kotlin
val isFavorite: Boolean = false            // the user's intent; app source of truth
val pendingFavoriteSync: Boolean = false   // the MediaStore flag does not match yet
```

Room **v4 → v5**, explicit `Migration(4, 5)`:

```sql
ALTER TABLE kept_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0;
ALTER TABLE kept_items ADD COLUMN pendingFavoriteSync INTEGER NOT NULL DEFAULT 0;
```

`fallbackToDestructiveMigration()` must not reappear — it silently wiped user data
before and was removed deliberately.

`pendingFavoriteSync` is direction-agnostic. The target state is whatever
`isFavorite` says, so one flag covers both favoriting and un-favoriting.

## 6. System sync

Android exposes `MediaStore.Files.FileColumns.IS_FAVORITE` (API 30+; minSdk is 30).
Writing it for media the app does not own requires
`MediaStore.createFavoriteRequest()`, which returns a `PendingIntent` the Activity
launches for user confirmation — the same mechanism as `createDeleteRequest`.

The sync therefore uses **the same mark-then-confirm state machine as deletion**,
which is already built, reviewed and tested:

1. **Mark.** Favoriting writes `isFavorite` and `pendingFavoriteSync` to Room.
   Nothing else happens. Instant, no dialog, no interruption to swiping.
2. **Prompt.** A `LaunchedEffect` in `MainActivity` observes pending rows and
   launches `createFavoriteRequest(resolver, uris, favorite)`.
3. **Confirm.** `RESULT_OK` → `confirmFavoriteSync(ids)` clears the pending flag.
   Anything else → `deferFavoriteSync(ids)`, which sets a prompted-this-session
   flag so a cancelled dialog does not re-prompt in a loop.

`createFavoriteRequest` accepts **one boolean for the entire batch**. Favorites and
un-favorites are therefore two separate requests and must be sequenced, never
launched together.

New `utils/MediaFavoriteHandler.kt` mirrors `MediaDeletionHandler`: chunked id
lists (`SQLITE_MAX_VARIABLE_NUMBER` is 999 on API 30–31), and anything not
*proven* absent routed to retry rather than silently dropped.

### 6.1 Principal risk — competing system dialogs

`MainActivity` already runs a deletion prompt from a `LaunchedEffect`. Adding a
second prompt effect creates a race: both can launch an `IntentSender` in the same
frame, Android shows one and drops the other, and the loser's rows stay pending
forever behind a latched guard.

This exact shape — two individually-correct changes invalidating each other's
preconditions — caused three separate defects in the preceding correctness pass.

**Mitigation:** a single `systemDialogInFlight` gate owns both prompts. Deletion
takes priority; the favorite prompt waits its turn. Both prompts read and set the
one gate. This is the highest-risk part of the work and should be reviewed hardest.

## 7. Interaction

### 7.1 Swipe screen

The card gesture currently hardcodes its Y translation to `0`. It gains a Y axis:

- Axis chosen by `abs(dx) > abs(dy)`; horizontal wins ties.
- Only **up** commits; down springs back.
- Threshold reuses the existing `0.4f` of screen width, per the TRD.

A gold **★ FAVORITE** badge fades in on up-drag, driven by the same drag-progress
value as the existing KEEP and DELETE badges. `OutgoingSwipeCard` gains a vertical
fly-off direction.

A third ★ button sits between DELETE and KEEP, routed through `performSwipe` so
gesture and tap remain indistinguishable, with a content description.

### 7.2 Kept screen

Filter chips — **All** / **★ Favorites** — plus a star badge on favorited tiles and
tap-to-toggle. Reuses the existing selection-pruning pattern
(`LaunchedEffect(items)` keyed by stable Room id) so a row vanishing beneath an
open filter cannot strand state.

### 7.3 Fullscreen viewer

A ★ toggle in the action bar, in SWIPE and KEPT modes only. Deliberately the
smallest possible change: this file yielded four latent no-ops and needed three
fix waves in the preceding pass. Do not restructure it.

## 8. Error handling

| Scenario | Behaviour |
|---|---|
| User cancels the favorite dialog | Rows stay favorited in Keepix and pending; no re-prompt until next launch |
| Favorite request throws | Caught, routed to `deferFavoriteSync`, surfaced via the existing `_error` channel |
| Item deleted from device while pending | The sync request skips it and the pending flag is cleared, so it cannot re-prompt forever. Note there is no existing missing-item cleanup for `kept_items` — that path exists only for `bin_items` — so a kept row for a vanished file lingers until the user removes it. Out of scope here; worth a follow-up |
| A favorite and a deletion pend at once | Deletion dialog first, then favorite — never concurrent |
| Room write fails | Caught and surfaced; the star reverts to its persisted value |

## 9. Privacy

`PRIVACY.md` and `PrivacyPolicyScreen.kt` each gain one line: Keepix writes the
system favorite flag, with the user's confirmation, for items they favorite. No
file contents are read, copied or modified.

**The two must stay in lockstep.** The screen is the source of truth — a stale
claim there is a false statement shown to users, not merely an out-of-date file.

## 10. Testing

**JVM:**
- Favorite state machine — mark → confirm clears the flag; mark → defer keeps it
  pending and does not re-prompt within the session.
- Swipe-axis arbitration as a pure function, including diagonal input.
- Two pending sets (favorite and un-favorite) sequence rather than merge.

**Instrumented:**
- Migration 4 → 5 preserves existing rows with both flags defaulting to false,
  exercised through the real `AppDatabase.getDatabase()` singleton rather than a
  hand-built `Room.databaseBuilder` — bypassing the singleton was the gap that
  made the previous migration test unable to catch a full revert.

## 11. Files

| File | Change |
|---|---|
| `db/KeptItemEntity.kt` | two columns |
| `db/AppDatabase.kt` | v5 + `Migration(4,5)` |
| `db/KeptItemDao.kt` | mark / pending / confirm / defer, chunked |
| `utils/MediaFavoriteHandler.kt` | **new**, mirrors `MediaDeletionHandler` |
| `ui/KeepixViewModel.kt` | `favoriteMedia`, `toggleFavorite`, sync state machine |
| `MainActivity.kt` | favorite launcher + shared dialog gate |
| `ui/SwipeScreen.kt` | Y axis, ★ badge, ★ button, vertical fly-off |
| `ui/KeptItemsScreen.kt` | filter chips, star badge, toggle |
| `ui/FullscreenViewer.kt` | ★ in the action bar (minimal) |
| `ui/theme/Color.kt` | one gold overlay color |
| `PRIVACY.md`, `ui/PrivacyPolicyScreen.kt` | amended line, in lockstep |

## 12. Verification

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"
./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug
```

On a device:

1. Favorite several items, leave the screen → one dialog → confirm → the star
   appears in Google Photos.
2. Same, but cancel → items stay favorited in Keepix; no re-prompt until next launch.
3. Favorite some and un-favorite others in one session → two dialogs, sequenced,
   correct in both directions.
4. **Bin an item and favorite another in the same session** → both dialogs appear,
   one after the other, neither swallowed. This is the shared-gate regression test.
5. Swipe diagonally → the intended action fires, not the other.
6. ★ button and swipe up produce identical results.
