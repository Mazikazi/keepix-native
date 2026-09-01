# Keepix — App Flow Document
**Version:** 1.1
**Platform:** Android
**Stack:** Kotlin / Jetpack Compose (native Android)
**Status:** Implemented

---

## 1. Flow Overview

```
Launch
  ↓
Permission Check
  ├── Not Granted → Permission Screen
  └── Granted
        ↓
    Launch Cleanup (rotate session id; MARK expired session/timed bin items
    as pendingDeletion — nothing is deleted yet, see §4)
        ↓
    First Launch? → Onboarding
        ↓
    Main Swipe Screen
        ├── Tap Card → Fullscreen Viewer
        ├── Tap Bin Icon → Recycle Bin
        ├── Tap Kept (heart) Icon → Kept Items
        └── Tap Settings Icon → Settings

    (independently, whenever pendingDeletion rows exist and the app is
    foregrounded: system delete-confirmation dialog, see §4)
```

---

## 2. Screen-by-Screen Flow

---

### SCREEN 1: Launch Cleanup (no dedicated splash UI)

**Trigger:** App process starts and `KeepixViewModel` is created (`init {}`)

**What happens (invisible to user, runs in the ViewModel, not a splash screen):**
1. Generate a new session id (UUID) via `KeepixPreferences.generateNewSession()`, which also returns the *previous* session id.
2. If a previous session id existed, query `bin_items` for `SESSION`-mode rows from that previous session and **mark** them `pendingDeletion` (no file or row is touched yet).
3. Query `bin_items` for `TIMED`-mode rows whose `expiryAt` has passed and mark those `pendingDeletion` too.
4. Schedule (or refresh) the daily `SessionCleanupWorker` via WorkManager, which repeats step 3 in the background so timed items still expire on days the app isn't opened.
5. Separately, `MainActivity` checks the media permission and decides where to route the user.

There is no dedicated splash screen composable — the app navigates straight to Permission/Onboarding/Swipe, and the marking above happens without blocking that navigation. The actual permanent deletion of anything marked in step 2/3 happens later, via the system dialog described in §4 — never synchronously here.

**Transitions:**
- Permission not granted → **Screen 2: Permission**
- Permission granted + onboarding not completed → **Screen 3: Onboarding**
- Permission granted + already onboarded → **Screen 4: Main Swipe**

---

### SCREEN 2: Permission Screen

**Trigger:** User has not granted media permissions

**UI Elements:**
- App logo/icon at top
- Headline: *"Keepix needs access to your photos"*
- Body: brief explanation of why (to show and manage your gallery)
- Primary CTA button: *"Grant Access"*

**States:**

| State | Behavior |
|---|---|
| First request | Show system permission dialog on button tap |
| Granted | Navigate to Onboarding or Main Swipe |
| Denied (can retry) | Show rationale text, re-show button |
| Permanently denied | Button changes to "Open Settings", deep-links to app settings |

Permission state is re-checked on every `ON_RESUME`, not just once at launch — returning from system Settings (e.g. after granting there) is picked up without needing to relaunch the app.

**Transitions:**
- Permission granted → **Screen 3: Onboarding** (first time) or **Screen 4: Main Swipe**
- Permanently denied → User must go to system settings manually

---

### SCREEN 3: Onboarding

**Trigger:** First launch after permission granted

**Format:** 2-step card walkthrough

**Step 1 — Swipe to Decide:**
- Animated card demo showing right swipe = Keep (green) and left swipe = Delete (red)
- Text: *"Swipe right to keep. Swipe left to delete."*

**Step 2 — Your Bin Has Your Back:**
- Illustration of recycle bin with a retention badge (default *"10 days"*)
- Text: *"Deleted photos go to your bin. Nothing is permanent until the timer runs out."*

**CTA:** *"Start Cleaning"* → navigates to **Screen 4: Main Swipe**

**Rules:**
- Onboarding is shown exactly once. After completion, `KeepixPreferences.onboardingComplete` is set to `true` (a `SharedPreferences` boolean, not a database row).
- No skip option — it is short enough that skipping is unnecessary.

---

### SCREEN 4: Main Swipe Screen

**Trigger:** Core screen of the app. Shown after onboarding or on every subsequent launch.

**UI Layout:**
```
┌─────────────────────────────┐
│ [🗑 Bin ᴺ] [♥ Kept ᴺ]  [N remaining]  [⚙ Settings] │  ← Top bar
│                             │
│   ┌─────────────────────┐   │
│   │                     │   │
│   │    PHOTO / VIDEO    │   │  ← Current card (up to 3 more stacked
│   │                     │   │     behind it at decreasing scale)
│   │                     │   │
│   └─────────────────────┘   │
│      ( ✕ )       ( ✓ )      │  ← Bottom action pill: round Delete /
│  ┌──────────────────────────┐│     Keep icon buttons (56dp)
│  │ 📷 PHOTO • Jan 12, 2024 ││  ← Metadata bar (type • date • duration)
│  └──────────────────────────┘│
└─────────────────────────────┘
```
(`ᴺ` = a small numeric badge shown only when the count is > 0.)

**Swipe Behavior:**
- Drag right: card rotates and translates with the finger; a green "KEEP" badge fades in, opacity driven by drag distance
- Drag left: card rotates the other way; a red "DELETE" badge fades in the same way
- Release past 40% of screen width (`screenWidth * 0.4f`) → action fires, card animates off-screen, next card animates up
- Release below threshold → card springs back to center

**Tap Behavior:**
- Tap on card (no drag) → open **Screen 5: Fullscreen Viewer**

**Bottom Action Buttons:**
- The ✕ (delete) and ✓ (keep) round icon buttons are a genuine equivalent to swiping, not a degraded fallback — both route through the exact same decision path as a completed drag (same off-screen animation, same callback), guarded against double-firing while a decision is already in flight.
- These exist for accessibility and user preference — not everyone is comfortable with swipes.

**Progress Indicator:**
- A pill in the **top bar** (not a separate bottom element) reads *"N remaining"*, updating as the user swipes.
- No progress bar — a raw count only, so a large library doesn't feel anxiety-inducing.

**Batch Preload Trigger:**
- When the in-memory queue drops below half of the current batch size (default 50), the next page is fetched in the background.
- Because this is a background top-up rather than a hard "batch boundary," the user typically never sees a loading pause between batches.

**Loading / Empty States:**
- Initial load (no data yet): centered spinner, *"Loading your library…"*
- Library genuinely exhausted (`reachedEnd`): *"All Done!"* with *"You kept X and deleted Y photos"*, plus a shortcut to the Bin if it isn't empty.
- A failed batch fetch (not yet exhausted) surfaces an error with a retry action instead of the "All Done" state — the two are tracked separately so a transient MediaStore failure never looks like "you finished your whole library."

**Transitions:**
- Tap bin icon → **Screen 6: Recycle Bin**
- Tap kept (heart) icon → **Screen 6b: Kept Items**
- Tap settings icon → **Screen 7: Settings**
- Tap card → **Screen 5: Fullscreen Viewer**

---

### SCREEN 5: Fullscreen Viewer

**Trigger:** User taps a card in Main Swipe, an item in the Recycle Bin, or an item in Kept Items — the screen has three modes (Swipe / Bin / Kept) that change which actions are offered.

**Entry animation:** the card cross-fades/scales up from its on-screen position (`MediaTransitionBounds`) to fullscreen — a custom Compose transition, not a navigation-library shared-element API.

**UI Layout:**
```
┌─────────────────────────────┐
│ [← Back]                   │  ← Top bar (fades out after 3s of inactivity)
│                             │
│                             │
│     PHOTO or VIDEO          │  ← Full bleed media
│                             │
│                             │
│ ┌─────────────────────────┐ │
│ │  mode-dependent actions  │ │  ← Glassmorphism bottom action bar
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

**Photo behavior:**
- Pinch to zoom (1x to 5x)
- Double-tap to toggle between 1x and 2.5x
- Pan when zoomed in

**Video behavior:**
- Plays via a plain `VideoView`/`MediaPlayer` (not a Compose-native player), which is also why video does not support the pinch-zoom photos get
- Auto-plays on open, loops
- Tap to pause/play, scrubber, mute/unmute
- Controls fade out after 3 seconds of inactivity, tap to show again

**Dismiss:**
- Swipe down with velocity → dismisses with a reverse animation back to the card
- Back button/gesture → same

**Actions in fullscreen (mode-dependent):**
- **Swipe mode:** KEEP / DELETE — marks the decision, dismisses, advances the queue
- **Bin mode:** RESTORE / DELETE NOW — restore removes the bin row (file untouched); Delete Now marks it `pendingDeletion` and follows the same system-dialog path as Empty Bin (§4) — it is not an immediate delete from this screen
- **Kept mode:** DELETE — un-keeps the item and moves it into the bin (same as a left-swipe would have)

**System UI:**
- Status bar and navigation bar hidden (immersive mode) while fullscreen is open, restored on dismiss

---

### SCREEN 6: Recycle Bin

**Trigger:** User taps the bin icon from Main Swipe Screen

**UI Layout:**
```
┌─────────────────────────────┐
│ [← Back]    Recycle Bin     │
│                [Empty Bin]  │
├─────────────────────────────┤
│ [Photo] [Photo] [Photo]     │
│ 3d left  8d left  2d left   │
│ [Photo] [Photo] [Photo]     │
│ Deletes  1d left  5d left   │
│ on reopen                   │
└─────────────────────────────┘
```

**Item states:**

| State | Badge shown |
|---|---|
| Timed, > 1 day left | "N days left" |
| Timed, exactly 1 day left | "1 day left" |
| Timed, < 24 hours | "< 1 day left" |
| Session mode | "Deletes on reopen" (orange badge) |

**Tap item:**
- Opens **Screen 5: Fullscreen Viewer** in Bin mode (RESTORE / DELETE NOW)

**Long press item:**
- Enters multi-select mode
- Checkboxes appear on items
- Top bar changes to: *"N selected"* with Restore and Delete actions

**Empty Bin / selected-items delete:**
- Confirmation dialog: *"Permanently delete all N items? This cannot be undone."* (or the selected-count equivalent)
- Confirming does **not** delete immediately: it marks the rows `pendingDeletion`, and the system delete-confirmation dialog described in §4 is what actually removes the files — see the mark-then-confirm model below.

**Empty state:**
- Illustration + *"Your bin is empty"*

---

### SCREEN 6b: Kept Items

**Trigger:** User taps the heart/Kept icon from Main Swipe Screen

**UI Layout:** a 2-column grid of every item the user has swiped right on, each thumbnail tappable to open in Fullscreen Viewer (Kept mode).

**Actions:**
- Tap item → **Screen 5: Fullscreen Viewer** in Kept mode (DELETE moves it to the bin)
- A confirmation step guards accidental un-keep/delete from this grid

**Empty state:** shown when no items have been kept yet.

---

### SCREEN 7: Settings

**Trigger:** User taps settings icon from Main Swipe Screen

**UI Layout:**
```
┌─────────────────────────────┐
│ [← Back]    Settings        │
├─────────────────────────────┤
│ DELETION                    │
│  Retention Period           │
│  [Slider: 0 ────●──── 365] │
│  Current: 10 days           │
│                             │
│ BIN                         │
│  Items in bin: 24           │
│  [Empty Bin]                │
│                             │
│ ABOUT                       │
│  Version 1.0.0              │
│  Privacy Policy             │
└─────────────────────────────┘
```

**Retention Period Slider:**
- Range: 0 to 365, one-day steps
- When set to 0: an inline note explains items will be deleted the next time the app is opened
- Change takes effect immediately for future deletions
- Existing bin items are NOT retroactively changed (they keep the `expiryAt`/`retentionMode` they were binned with)

**Transitions:**
- Tap "Empty Bin" → confirmation dialog → same mark-then-confirm flow as the Bin screen's own Empty Bin action
- Tap Privacy Policy → opens **`PrivacyPolicyScreen`**, a native Compose screen bundled with the app. This is deliberately **not** a WebView or an external browser link: Keepix declares no `INTERNET` permission, so a network-backed privacy screen would simply fail to load. The content mirrors `PRIVACY.md` at the repo root.

---

## 3. Navigation Map

```
(no splash UI)
  └── Permission Screen (if needed)
        └── Onboarding (first time only)
              └── Main Swipe ←────────────────────┐
                    ├── Fullscreen Viewer (Swipe mode)
                    │     └── (back) ──────────────┘
                    │
                    ├── Recycle Bin ←──────────────┐
                    │     ├── Fullscreen Viewer (Bin mode)
                    │     │     └── (back) ──────────┘
                    │     └── (back) → Main Swipe
                    │
                    ├── Kept Items ←────────────────┐
                    │     ├── Fullscreen Viewer (Kept mode)
                    │     │     └── (back) ──────────┘
                    │     └── (back) → Main Swipe
                    │
                    └── Settings
                          ├── Privacy Policy
                          │     └── (back) → Settings
                          └── (back) → Main Swipe
```

---

## 4. State Transitions: Deletion Modes

Both modes below share one final step: **nothing is ever deleted from the device except through a user-confirmed `MediaStore.createDeleteRequest` system dialog.** Marking a row `pendingDeletion` and launching that dialog is the only path from "eligible for removal" to "actually gone" — the Room row is not dropped until `RESULT_OK` comes back.

### Timed Mode (1–365 days)

```
Swipe Left
    → Item added to bin_items (retentionMode = TIMED, expiryAt = now + N days)
    → File stays on device
    → On next app launch AND daily via SessionCleanupWorker (WorkManager):
        → bin_items WHERE retentionMode = TIMED AND expiryAt <= now() → marked pendingDeletion
    → MainActivity observes pendingDeletion rows, builds a single
      MediaStore.createDeleteRequest for the batch (chunked at 750 URIs per
      request), and launches the system confirmation dialog
    → User confirms (RESULT_OK) → file removed by the system → Room row dropped
    → User cancels → row un-marked, stays an ordinary restorable bin item
```

### Session Mode (0 days)

```
App Launch
    → New session id generated (SharedPreferences), previous id returned
    → bin_items WHERE retentionMode = SESSION AND sessionId != previousId
      are marked pendingDeletion (NOT deleted yet)
    → Those rows flow into the same system-dialog path as Timed mode above

Swipe Left (during session)
    → Item added to bin_items (retentionMode = SESSION, sessionId = current)
    → File stays on device
    → Item visible in bin with "Deletes on reopen" badge
    → User can restore any time while the app is open

App Closed / Backgrounded
    → No deletion happens (SessionCleanupWorker never rotates the session id —
      only app launch does, so a background sweep can't expire the still-running
      session's own items)
    → Items remain in bin

Next App Open
    → Session id rotates again; SESSION items from the now-previous session id
      are marked pendingDeletion and go through the same confirm/cancel dialog
      flow as any other pending item
```

---

## 5. Edge Cases

| Scenario | Behavior |
|---|---|
| User changes retention from 10 days to 0 mid-session | Existing bin items keep their original `expiryAt`/`retentionMode`. New deletions use session mode. |
| User restores an item that was already deleted from the device externally | Handled by the mark-then-confirm filter: a URI whose MediaStore row is confirmed gone is removed from `bin_items` directly (no dialog needed), with a snackbar: *"Photo no longer on device"* |
| A URI's absence can't be proven (permission-scoped access, malformed URI, unexpected query failure) | Treated as still-existing and routed through the normal system dialog rather than silently dropped — never assume a file is gone without proof |
| User swipes through all photos | "All Done!" empty state on Main Swipe, with a kept/deleted count summary |
| Gallery has 0 photos/videos | Same "All Done!" / empty-queue state appears immediately after the first (empty) page loads |
| App is force-killed during session mode (zero-day) | Session cleanup runs on next open — the session id mismatch is detected the same way regardless of how the previous process ended |
| User revokes permission from system Settings mid-use | Re-checked on every `ON_RESUME`; Permission Screen is shown again without needing a fresh launch |
| System delete confirmation dialog is silently dropped (e.g. background-activity-start restrictions while backgrounded) | Retried automatically the next time the app returns to the foreground, instead of leaving the batch stuck |
| System delete confirmation dialog cancelled | No deletion occurs; item(s) return to being ordinary bin items — no error shown |
| A batch of pending deletions exceeds 750 items | Chunked across multiple sequential system dialogs (`MAX_DELETE_REQUEST_BATCH`) rather than one oversized request |
