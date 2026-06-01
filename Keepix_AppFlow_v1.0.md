# Keepix — App Flow Document
**Version:** 1.1  
**Platform:** Android  
**Stack:** React Native (TypeScript)  
**Status:** Draft

---

## 1. Flow Overview

```
Launch
  ↓
Permission Check
  ├── Not Granted → Permission Screen
  └── Granted
        ↓
    Session Cleanup (zero-day mode)
        ↓
    First Launch? → Onboarding
        ↓
    Main Swipe Screen
        ├── Tap Card → Fullscreen Viewer
        ├── Tap Bin Icon → Recycle Bin
        └── Tap Settings Icon → Settings
```

---

## 2. Screen-by-Screen Flow

---

### SCREEN 1: Splash / Launch Gate

**Trigger:** App opens (cold start or reopen)

**What happens (invisible to user, runs in background):**
1. Generate new `session_id` (UUID), store in MMKV
2. Query SQLite for `SESSION` mode bin items from the previous `session_id`
3. If found → call `CameraRoll.deletePhotos()` for each, remove from SQLite
4. Query SQLite for any `TIMED` bin items where `expiry_at <= now()`
5. If found → permanently delete those too
6. Check media permission status

**Transitions:**
- Permission not granted → **Screen 2: Permission**
- Permission granted + first launch flag not set → **Screen 3: Onboarding**
- Permission granted + already onboarded → **Screen 4: Main Swipe**

**Duration:** Should complete in under 1 second. Show app logo/wordmark while this runs.

---

### SCREEN 2: Permission Screen

**Trigger:** User has not granted media permissions

**UI Elements:**
- App logo at top
- Illustration (e.g., phone with photos)
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

**Transitions:**
- Permission granted → **Screen 3: Onboarding** (first time) or **Screen 4: Main Swipe**
- Permanently denied → User must go to system settings manually

---

### SCREEN 3: Onboarding

**Trigger:** First launch after permission granted

**Format:** Single screen or 2-step card walkthrough (not more than 2 steps)

**Step 1 — Swipe to Decide:**
- Animated card demo showing right swipe = Keep (green) and left swipe = Delete (red)
- Text: *"Swipe right to keep. Swipe left to delete."*

**Step 2 — Your Bin Has Your Back:**
- Illustration of recycle bin with countdown timer
- Text: *"Deleted photos go to your bin. Nothing is permanent until the timer runs out."*

**CTA:** *"Start Cleaning"* → navigates to **Screen 4: Main Swipe**

**Rules:**
- Onboarding is shown exactly once. After completion, a flag is set in `user_settings`
- No skip option — it is short enough that skipping is unnecessary

---

### SCREEN 4: Main Swipe Screen

**Trigger:** Core screen of the app. Shown after onboarding or on every subsequent launch.

**UI Layout:**
```
┌─────────────────────────────┐
│  [Bin Icon]       [Settings]│  ← Top bar (glassmorphism pill)
│                             │
│   ┌─────────────────────┐   │
│   │                     │   │
│   │    PHOTO / VIDEO    │   │  ← Current card (glassmorphism border)
│   │                     │   │
│   │                     │   │
│   └─────────────────────┘   │
│  ┌──────────────────────────┐│
│  │ 📅 Jan 12, 2024  VIDEO  ││  ← Card metadata bar (glassmorphism)
│  └──────────────────────────┘│
│                             │
│   [✕ DELETE]   [✓ KEEP]    │  ← Action hint buttons (tap or swipe)
└─────────────────────────────┘
```

**Swipe Behavior:**
- Drag right: card rotates clockwise, green "KEEP" badge fades in on card
- Drag left: card rotates counter-clockwise, red "DELETE" badge fades in on card
- Release past 40% threshold → action fires, card flies off screen, next card animates up
- Release below threshold → card springs back to center

**Tap Behavior:**
- Tap on card (no drag) → open **Screen 5: Fullscreen Viewer**

**Bottom Buttons:**
- Tapping "DELETE" button = same as swiping left
- Tapping "KEEP" button = same as swiping right
- These exist for accessibility and user preference — not everyone is comfortable with swipes

**Progress Indicator:**
- Subtle text or pill at bottom: *"124 remaining"* — updates as user swipes
- Does not show a progress bar (would be anxiety-inducing for large libraries)

**Batch Preload Trigger:**
- When user reaches card 40 of current 50-card batch, background fetch of next 50 begins
- When current batch ends, next batch is already in memory — no loading pause

**Empty State:**
- When all photos have been processed: show a clean "All done" screen
- Display count summary: *"You kept X photos and deleted Y"*
- CTA: *"View Bin"* or *"Done"*

**Transitions:**
- Tap bin icon → **Screen 6: Recycle Bin**
- Tap settings icon → **Screen 7: Settings**
- Tap card → **Screen 5: Fullscreen Viewer**

---

### SCREEN 5: Fullscreen Viewer

**Trigger:** User taps on a card in Main Swipe Screen OR taps an item in the Recycle Bin

**Entry animation:** Card expands to fullscreen (shared element transition or crossfade)

**UI Layout:**
```
┌─────────────────────────────┐
│ [← Back]           [⋮ More]│  ← Top bar (fades out after 2s of inactivity)
│                             │
│                             │
│                             │
│     PHOTO or VIDEO          │  ← Full bleed media
│                             │
│                             │
│                             │
│ ┌─────────────────────────┐ │
│ │  [✕ DELETE]  [✓ KEEP]  │ │  ← Glassmorphism bottom action bar
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

**Photo behavior:**
- Pinch to zoom (1x to 5x)
- Double-tap to toggle between 1x and 2.5x
- Pan when zoomed in

**Video behavior:**
- Auto-plays on open
- Tap to pause/play
- Bottom progress bar scrubber
- Mute/unmute button
- Loops automatically

**Dismiss:**
- Swipe down with velocity → dismisses with reverse animation back to card
- Tap back button → same

**Actions in fullscreen:**
- Tap "KEEP" → marks as kept, dismisses fullscreen, advances to next card
- Tap "DELETE" → marks as deleted, moves to bin, dismisses fullscreen, advances to next card
- If opened from Recycle Bin → actions change to "RESTORE" and "Delete Now"

**System UI:**
- Status bar and navigation bar hidden (immersive mode) while fullscreen is open
- Restored on dismiss

---

### SCREEN 6: Recycle Bin

**Trigger:** User taps bin icon from Main Swipe Screen

**UI Layout:**
```
┌─────────────────────────────┐
│ [← Back]    Recycle Bin     │
│ [Empty Bin]                 │
├─────────────────────────────┤
│ SESSION MODE BANNER         │  ← Only shown if retention = 0
│ "Deletes on next app open"  │
├─────────────────────────────┤
│ [Photo] [Photo] [Photo]     │
│ 3d left  8d left  2d left   │
│ [Photo] [Photo] [Photo]     │
│ Deletes  1d left  5d left   │
│ on open                     │
└─────────────────────────────┘
```

**Item states:**

| State | Badge shown |
|---|---|
| Timed, > 1 day left | "X days left" |
| Timed, < 24 hours | "< 1 day left" (red badge) |
| Session mode | "Deletes on reopen" (orange badge) |

**Tap item:**
- Opens **Screen 5: Fullscreen Viewer** in bin mode (RESTORE / Delete Now actions)

**Long press item:**
- Enters multi-select mode
- Checkboxes appear on items
- Top bar changes to: *"X selected"* with Restore and Delete actions

**Empty Bin:**
- Confirmation bottom sheet: *"Permanently delete all X items? This cannot be undone."*
- Confirm → delete all, show empty state

**Empty state:**
- Illustration + *"Your bin is empty"*

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
│ DISPLAY                     │
│  Sort Order                 │
│  Newest First (only option) │
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
- Range: 0 to 365
- Snaps to integers
- When set to 0: inline tooltip appears below slider:
  *"Photos will be deleted the next time you open the app"*
- Change takes effect immediately for future deletions
- Existing bin items are NOT retroactively changed

**Transitions:**
- Tap "Empty Bin" → confirmation sheet → same as bin's empty action
- Tap Privacy Policy → opens in-app WebView or external browser

---

## 3. Navigation Map

```
Splash
  └── Permission Screen (if needed)
        └── Onboarding (first time only)
              └── Main Swipe ←────────────────────┐
                    ├── Fullscreen Viewer           │
                    │     └── (back) ──────────────┘
                    │
                    ├── Recycle Bin ←──────────────┐
                    │     ├── Fullscreen Viewer      │
                    │     │     └── (back) ──────────┘
                    │     └── (back) → Main Swipe
                    │
                    └── Settings
                          └── (back) → Main Swipe
```

---

## 4. State Transitions: Deletion Modes

### Timed Mode (1–365 days)

```
Swipe Left
    → Item added to bin_items (retention_mode = TIMED, expiry_at = now + N days)
    → File stays on device
    → Daily ExpiryWorker checks expiry_at
    → expiry_at reached → ContentResolver.delete() → record removed from Room
```

### Session Mode (0 days)

```
App Launch
    → New session_id generated
    → SessionCleanupWorker runs:
        → Queries bin_items WHERE retention_mode = SESSION AND session_id != current
        → ContentResolver.delete() for each
        → Records removed from Room

Swipe Left (during session)
    → Item added to bin_items (retention_mode = SESSION, session_id = current)
    → File stays on device
    → Item visible in bin with "Deletes on reopen" badge
    → User can restore any time while app is open

App Closed / Backgrounded
    → No deletion happens
    → Items remain in bin

Next App Open
    → SessionCleanupWorker fires
    → All SESSION items from previous session_id are permanently deleted
```

---

## 5. Edge Cases

| Scenario | Behavior |
|---|---|
| User changes retention from 10 days to 0 mid-session | Existing bin items keep their original expiry. New deletions use session mode. |
| User restores an item that was deleted from device externally | Restore attempt fails silently. Item is removed from bin with an error snackbar: *"Photo no longer exists on device"* |
| User swipes through all photos | Empty state shown on Main Swipe: *"You've reviewed everything"* with stats |
| Gallery has 0 photos/videos | Immediate empty state on Main Swipe after launch |
| App is force-killed during session mode (zero-day) | Session cleanup runs on next open — items from previous session are still deleted correctly because session_id mismatch is detected |
| User denies permission after granting it (revokes from settings) | On next launch, Permission Screen is shown again |
| ContentResolver.delete() triggers system confirmation dialog (API 30+) | System dialog appears. If user cancels → item stays in bin. If user confirms → deleted. |
