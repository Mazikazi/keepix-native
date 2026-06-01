# Keepix — Product Requirements Document (PRD)
**Version:** 1.0  
**Platform:** Android (Google Play)  
**Status:** Draft

---

## 1. Problem Statement

People accumulate hundreds or thousands of photos and videos on their phones but never clean them up. Traditional gallery management is tedious — users have to tap into each photo individually, decide, go back, repeat. There is no fast, frictionless way to sweep through a large library and make keep/delete decisions at speed.

Keepix solves this by turning photo cleanup into a Tinder-style swipe experience — fast, intuitive, and satisfying.

---

## 2. Product Goals

- Let users process large photo/video libraries quickly through swipe gestures
- Prevent accidental permanent loss through a configurable recycle bin
- Load media smoothly without freezing or long waits
- Feel polished and modern with a glassmorphism UI design language

---

## 3. Success Metrics (v1.0)

| Metric | Target |
|---|---|
| Photos processed per session | 50+ per average session |
| Accidental deletion recovery rate | < 5% of deleted items restored (low = users are confident) |
| App crash rate | < 0.5% of sessions |
| Median load time for first card | < 1.5 seconds |
| Play Store rating | 4.0+ within 60 days of launch |

---

## 4. Target Users

**Primary:** Android users with 500+ photos on their device who feel overwhelmed by their gallery and want a faster way to declutter.

**Secondary:** Users who regularly take large batches of photos (events, travel) and want to quickly trim them afterward.

---

## 5. Core Features

### 5.1 Swipe-to-Decide Cards

- Photos and videos from the device gallery are displayed as stacked cards, one at a time
- **Swipe right** = Keep (photo stays on device, no action taken)
- **Swipe left** = Delete (photo is moved to Keepix recycle bin)
- Cards animate in a Tinder-style motion during swipe
- A visual indicator appears during the swipe gesture (e.g., green "KEEP" overlay on right drag, red "DELETE" overlay on left drag)
- After swiping the last card in a batch, the next batch loads automatically

### 5.2 Batch Loading

- Media is loaded in batches of **50 items** by default
- The next batch is preloaded in the background while the user is still swiping the current one
- This prevents any visible loading pause between batches
- Media is sorted by **date taken, newest first** by default

### 5.3 Tap to Fullscreen

- Tapping the card (without swiping) opens the media in fullscreen mode
- In fullscreen: pinch-to-zoom for photos, standard playback controls for videos
- Swipe down or tap a close button to return to the card view
- Keep/Delete buttons are available in fullscreen mode so the user doesn't have to exit first

### 5.4 Recycle Bin

- All swiped-left media goes into an in-app recycle bin — it is **not immediately deleted from the device**
- The bin shows a list/grid of deleted items with their deletion date and a countdown to permanent deletion
- Users can restore any item from the bin back to the device gallery at any time before it expires
- When the retention period expires, the item is permanently deleted from the device

### 5.5 Configurable Retention Period

Users can set their bin retention period in Settings. Options:

| Setting | Behavior |
|---|---|
| 0 days | Session-based deletion (see Section 5.6) |
| 1 – 365 days | Item is permanently deleted N days after swipe-left |
| Default | 10 days |

### 5.6 Zero-Day Retention (Session Mode)

This is a special mode when the user sets retention to 0 days:

- Swipe left → item is marked for deletion but **not yet deleted**
- While the app remains open, the user can go to the bin and restore any marked item
- When the user **next opens the app** after closing it, all items marked during the previous session are permanently deleted from the device
- The bin shows a warning label: *"These will be deleted when you reopen the app"*
- This behavior is more reliable on Android than triggering on app close (see TRD for technical reasoning)

### 5.7 Undo (via Recycle Bin)

- There is no swipe-back gesture for undo
- Undo is done by opening the bin and restoring the item
- The bin is accessible via a button in the main card view (e.g., trash icon in top right)
- For zero-day mode: undo is only available while the app is still open in the current session

---

## 6. User Flows

### 6.1 First Launch

1. App requests media read permission (Android `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`)
2. User grants permission
3. App displays a brief one-screen onboarding card explaining swipe directions
4. First batch of 50 photos loads, swiping begins

### 6.2 Main Swiping Flow

1. User sees a card with photo/video
2. User swipes right (Keep) or left (Delete)
3. Next card animates in
4. At card 40 of 50, background preload of next batch begins
5. At card 50, next batch is already ready — no loading pause
6. User can tap bin icon at any time to review/restore deleted items

### 6.3 Fullscreen Flow

1. User taps card
2. Fullscreen opens with the media
3. For video: auto-plays with sound, has pause/play controls
4. For photo: pinch-to-zoom enabled
5. User can swipe down to close or tap X
6. Keep/Delete buttons visible at bottom of fullscreen view

### 6.4 Recycle Bin Flow

1. User taps bin icon
2. Grid view of all deleted items with days remaining label
3. Tap item to preview
4. Tap "Restore" to send it back to device gallery
5. Tap "Delete Now" to permanently delete before expiry
6. "Empty Bin" button to permanently delete all items at once

### 6.5 Retention Setting Flow

1. Settings → Retention Period
2. Slider or input: 0 to 365 days
3. If user selects 0, a brief tooltip explains session-mode behavior
4. Change applies to future deletions only (existing bin items keep their original expiry)

---

## 7. Non-Functional Requirements

| Area | Requirement |
|---|---|
| Performance | First card must appear within 1.5s of launch |
| Performance | Swipe gesture must feel instant (no frame drops during card animation) |
| Storage | Bin metadata stored locally in app (no cloud) |
| Offline | Fully offline — no internet connection required |
| Permissions | Only request permissions that are actually used |
| Media access | Read from device gallery; write-back only on restore or permanent delete |
| Minimum Android version | Android 10 (API 29) |

---

## 8. UI Design Principles

- **Glassmorphism**: Frosted glass effect on cards, overlays, and bottom sheets. Blur backgrounds, semi-transparent surfaces, subtle white borders.
- **Dark-first**: Dark background to make media pop and reduce eye strain during long sessions
- **Minimal chrome**: The photo is the focus. UI elements should be subtle and not compete with the media
- **Smooth animations**: Card swipe, fullscreen transition, and bin open/close should all use fluid animations (60fps target)

---

## 9. Out of Scope for v1.0

- Cloud backup or sync
- iCloud / Google Photos integration
- Duplicate photo detection
- AI-based photo ranking or suggestions
- Sharing photos from within the app
- Custom albums or tagging
- Face grouping
- Web or iOS version

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| User accidentally swipes left on important photo | High | Recycle bin with restore — no instant permanent delete |
| Android media permission changes across OS versions | Medium | Handle both legacy (`READ_EXTERNAL_STORAGE`) and new scoped storage APIs |
| Session-mode deletion not firing reliably on app reopen | Medium | Use WorkManager with a startup trigger (see TRD) |
| Large libraries (10,000+ photos) causing slow initial indexing | Medium | Index lazily in background, show available cards immediately |
