# Keepix Privacy Policy

**Effective Date:** 2026-07-09  
**Last Updated:** 2026-09-03  
**Version:** 1.2

---

## 1. What Keepix Does

Keepix is a local-first Android application that helps you declutter your photo and video gallery. You swipe through your media:
- **Swipe right (Keep)** — The item stays in your gallery; Keepix records only its URI and a timestamp so it won't appear again.
- **Swipe left (Delete)** — The item moves to an in-app Recycle Bin with a configurable retention period (default 10 days). You can restore it anytime before expiry. After expiry, Keepix permanently deletes the item from your device via the system MediaStore.
- **Session Mode (0-day retention)** — Items deleted during a session are permanently removed when you next open the app.

All decisions happen on your device. Nothing is ever uploaded or transmitted.

---

## 2. What Keepix Does NOT Do

- ❌ No analytics, telemetry, or crash-reporting SDKs
- ❌ No third-party libraries that transmit data
- ❌ No user accounts, authentication, or cloud sync
- ❌ No advertising, ad IDs, or tracking
- ❌ **No network permission declared** — the app binary contains no `android.permission.INTERNET`

---

## 3. Permissions Used & Why

| Permission | Purpose |
|---|---|
| `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API ≤32, `maxSdkVersion=32`) | Read your photo library to display cards |
| `READ_MEDIA_VIDEO` (API 33+) | Read your video library to display cards |

Keepix never requests `WRITE_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`. Deleting, favoriting and optimizing each go through Android's own confirmation dialog instead, one file set at a time, and only ever affect files you selected. Keepix's minimum supported Android version is API 30 (Android 11); Android 10 (API 29) and earlier are no longer supported.

---

## 4. Data Stored On Your Device

Keepix uses a local Room (SQLite) database and a SharedPreferences file. Tables:

| Table | Columns | Purpose |
|---|---|---|
| `bin_items` | `mediaId`, `mediaUri`, `displayName`, `mediaType`, `dateTaken`, `deletedAt`, `expiryAt`, `sessionId`, `retentionMode`, `width`, `height`, `durationMs` | Recycle Bin items pending permanent deletion |
| `kept_items` | `mediaId`, `mediaUri`, `displayName`, `mediaType`, `dateTaken`, `keptAt`, `width`, `height`, `durationMs`, `isFavorite`, `pendingFavoriteSync` | Items you chose to keep (prevents re-showing) |
| `compression_journal` | `mediaUri`, `backupPath`, `originalSize`, `startedAt` | Tracks a photo optimization that is mid-write, so an interrupted one can be undone |
| SharedPreferences | `retentionDays`, `batchSize`, `onboardingComplete`, `lastSessionId`, `fullscreenTutorialComplete` | User settings |

**No media bytes are stored long-term.** Only URIs and metadata — except for the brief window during an optimization, when one original is held in app-private storage until the result is verified.

When you favorite an item, Keepix asks Android to set that photo or video's system favorite flag, so the star also appears in your gallery app. Android shows you a confirmation dialog first, and nothing is written unless you confirm. This changes only that flag — no file contents are read, copied or modified.

Optimizing a photo rewrites that file on your device to remove redundant data some cameras embed alongside the picture — a duplicate second copy of the shot. The image itself is not altered: every pixel is copied across untouched, along with the date, orientation, location and colour profile. Android shows you a confirmation dialog first, and nothing is written unless you confirm. Keepix keeps its own copy of the original in app-private storage until it has read the result back and checked it, and restores the original if anything goes wrong. That copy is deleted as soon as the result is verified.

---

## 5. Data Sharing

**We share your data with no one.** There is no backend, no analytics endpoint, no third-party processor.

---

## 6. Deletion & Retention

- **Recycle Bin items** — Auto-deleted after your chosen retention period (1–365 days) or on next app reopen if set to 0-day mode.
- **Kept items metadata** — Persists until you uninstall Keepix.
- **Uninstalling Keepix** — Removes the app and its database. Your device gallery is untouched except for items you already permanently deleted via the Bin.

---

## 7. Children

Keepix is not directed at children under 13. We do not knowingly collect data from children.

---

## 8. Contact

Open an issue: https://github.com/Mazinkazi/keepix-native/issues

---

## 9. License

Keepix is open source under the MIT License. Source: https://github.com/Mazinkazi/keepix-native