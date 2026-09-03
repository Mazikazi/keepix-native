# Keepix — Android Native

[![Android API](https://img.shields.io/badge/API-30%20%2B-brightgreen.svg?style=flat)](https://android-sdk.is)
[![OWASP Mobile MASVS](https://img.shields.io/badge/OWASP-MASVS%20Compliant-blue.svg)](https://mas.owasp.org/)


A premium, Tinder-style gallery cleanup app for Android. Built natively using modern Android engineering practices, including Jetpack Compose (Material 3), Room DB, WorkManager, and reactive Coroutine Flows.

Keepix solves the clutter problem of modern photo galleries by introducing an extremely fast, frictionless, gesture-driven decision interface. Instead of tedious manual taps, users sweep through their libraries with intuitive swipe gestures, ensuring their personal space remains decluttered, organized, and secure.

---

## 📸 Key Product Features

* **Swipe-to-Decide Gestures:** Photos and videos are presented as stacked cards. Swipe right to **Keep** (stores the metadata in `kept_items`), and swipe left to **Delete** (moves to Recycle Bin).
* **Smart Recycle Bin:** All swiped-left items go into an in-app Recycle Bin instead of instant deletion. The Recycle Bin shows a clean grid of items with their deletion date and a precise countdown to permanent deletion.
* **0-Day Session Mode:** An innovative, resilient deletion mode where swiped-left items are held temporarily and only permanently deleted from the device when the user *next* opens the app.
* **Configurable Retention Period:** Adjust the retention period from `0 days` (Session-based mode) up to `365 days` via the Settings screen.
* **100% Offline & Private:** Fully offline operation. Keepix requests zero network permissions, ensuring all media analysis, indexing, and storage happen entirely on-device.

---

## 🛠 Tech Stack

Keepix is built with a state-of-the-art Android toolkit:

* **UI Framework:** Jetpack Compose (Material 3) — Declarative UI with dynamic glassmorphism aesthetics.
* **Database & Persistence:** Room Database (SQLite) — Local relational storage for media lists, recycle bin, and metadata.
* **Asynchronous Execution:** Kotlin Coroutines & Flow — Unidirectional data stream from Database -> Repository -> ViewModel -> UI.
* **Background Tasks:** WorkManager — Robust background execution for session-based and timing-based asset deletion cleanup.
* **Image Loading:** Coil Compose — Asynchronous, cached loading of system gallery photos and video thumbnails.
* **Metadata Processing:** Kotlin Symbol Processing (KSP) — High-performance annotation processing for Room DB generation.

---

## 🏗 Architecture Blueprint

Keepix follows the official Android Architecture Guidelines, utilizing a structured **Model-View-ViewModel (MVVM)** pattern with unidirectional data flow (UDF).

```
          [ Jetpack Compose UI Screens ]
                       ↑
                (StateFlow State)
                       |
               [ KeepixViewModel ]
                       ↑
             (Suspend / Flow Stream)
                       |
               [ MediaRepository ]
                 /           \
                ▼             ▼
       [ Room Database ]   [ Android MediaStore ]
       (Local SQLite DB)   (Device System Gallery)
```

### Directory Structure

```
com.sese.keepix/
│
├── data/
│   └── MediaRepository.kt        # Repository coordinating Room DB & MediaStore
│
├── db/
│   ├── AppDatabase.kt            # Room Database initialisation
│   ├── BinItemDao.kt             # CRUD operations for recycle bin items
│   ├── BinItemEntity.kt          # Room DB entity for pending deletions
│   ├── KeptItemDao.kt            # CRUD operations for kept items
│   └── KeptItemEntity.kt         # Room DB entity for kept assets
│
├── ui/
│   ├── theme/                    # Color schemes, typography, and shape styling
│   ├── components/               # Custom reusable Compose widgets
│   ├── SwipeScreen.kt            # Main card gesture layout
│   ├── FullscreenViewer.kt       # Dynamic media preview (pinch-to-zoom/player)
│   ├── RecycleBinScreen.kt       # Grid layout with expiry badges
│   ├── SettingsScreen.kt         # Custom retention configurations
│   └── KeepixViewModel.kt        # Global State holder & Business logic trigger
│
└── utils/
    ├── MediaDeletionHandler.kt   # MediaStore.createDeleteRequest + existence filtering
    └── SessionCleanupWorker.kt   # WorkManager: daily sweep that MARKS expired timed items
                                  # (pendingDeletion) — files are only removed after the
                                  # user confirms the system delete dialog
```

---

## 🗄 Database Schema Blueprint

Keepix persists metadata locally in the app using two main Room entities. No media files are copied or moved—only URIs are stored, keeping the app storage footprint at practically 0%.

### 1. `bin_items` (Recycle Bin)
Tracks items pending permanent deletion from the device system gallery.

| Field | Type | Description |
|---|---|---|
| `id` (PK) | `Long` (Auto-generated) | Internal unique database key |
| `mediaId` | `Long` | Original Android MediaStore asset ID |
| `mediaUri` | `String` | Unique URI string targeting the asset file |
| `displayName` | `String` | Original file name of the media |
| `mediaType` | `String` | `'IMAGE'` or `'VIDEO'` |
| `dateTaken` | `Long` | Capture timestamp of the original media |
| `deletedAt` | `Long` | Timestamp when the user swiped left |
| `expiryAt` | `Long` | Expiry timestamp. Set to `0` for session mode |
| `sessionId` | `String` | Unique UUID of the active session when swiped |
| `retentionMode` | `String` | Deletion rule logic (`'SESSION'` or `'TIMED'`) |
| `width` | `Int` | Media width in pixels |
| `height` | `Int` | Media height in pixels |
| `durationMs` | `Long` | Asset duration (for video playback, `0` for images) |
| `pendingDeletion` | `Boolean` | `true` once cleanup has selected this row for permanent removal. The file is only removed after the user confirms the system delete dialog (`MediaStore.createDeleteRequest`); the row is dropped only after that confirmation succeeds — never before |

### 2. `kept_items` (Kept Assets)
Prevents swiped-right images from appearing back in the active queue.

| Field | Type | Description |
|---|---|---|
| `id` (PK) | `Int` (Auto-generated) | Internal unique database key |
| `mediaId` | `Long` | Original Android MediaStore asset ID |
| `mediaUri` | `String` | Unique URI string targeting the asset file |
| `displayName` | `String` | Original file name of the media |
| `mediaType` | `String` | `'IMAGE'` or `'VIDEO'` |
| `dateTaken` | `Long` | Capture timestamp of the original media |
| `keptAt` | `Long` | Timestamp when the user swiped right |
| `width` | `Int` | Media width in pixels |
| `height` | `Int` | Media height in pixels |
| `durationMs` | `Long` | Asset duration (`0` for images) |

---

## 🔒 OWASP Mobile Security Compliance

This project has been thoroughly audited and hardened in compliance with the **OWASP Mobile Application Security Verification Standard (MASVS)** and the **OWASP Mobile Top 10**.

* **Data Leakage Mitigation (MASVS-STORAGE):** `android:allowBackup` is explicitly set to `false` in `AndroidManifest.xml` to prevent unauthorized ADB data extraction of Room databases or user configurations.
* **Verbose/Debug Log Stripping (MASVS-CODE):** Built-in ProGuard optimization rules (`proguard-rules.pro`) strip `android.util.Log`'s `d`/`v`/`i`/`w` calls from release builds:
  ```proguard
  -assumenosideeffects class android.util.Log {
      public static int d(...);
      public static int v(...);
      public static int i(...);
      public static int w(...);
  }
  ```
  `Log.e` is deliberately **not** stripped — error logs are kept in release builds to support crash triage, and are called unguarded throughout the codebase (e.g. `MediaRepository.kt`, `MainActivity.kt`). A handful of lower-severity, non-error paths (permission/URI-lookup fallbacks in `MediaDeletionHandler.kt`) additionally gate their `Log.w` calls behind `BuildConfig.DEBUG` so they only fire in debug builds.
* **Secure Exception Handling:** The codebase contains no `printStackTrace()` calls — exceptions are always routed through `android.util.Log` instead, never printed directly to stderr.
* **SQL Injection Prevention:** Highly secure database access using Android Room. All SQL commands utilize strictly parameterized `@Query` structures, rendering SQL injection vectors impossible.
* **No Network Exposure:** The application contains **no internet permissions** (`android.permission.INTERNET`) in its manifest. It operates completely offline, ensuring data privacy and zero cloud leakage.

---

## 🚀 Build & Setup Guide

### Prerequisites
* **Android Studio** Ladybug (2024.2.1) or newer
* **JDK 17** (Ensure your JAVA_HOME points to JDK 17)
* Android SDK Platform 35 (Target API 35)

### Running Locally
1. Clone the repository:
   ```bash
   git clone https://github.com/Mazikazi/keepix-native.git
   cd keepix-native
   ```
2. Build the project using Gradle Wrapper:
   ```bash
   ./gradlew assembleDebug
   ```
3. Run the unit and instrumentation tests:
   ```bash
   ./gradlew test
   ```
4. Install on an active emulator or connected device:
   ```bash
   ./gradlew installDebug
   ```

---

