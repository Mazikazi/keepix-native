# Keepix — Technical Requirements Document (TRD)
**Version:** 1.1  
**Platform:** Android (Google Play)  
**Stack:** React Native (TypeScript)  
**Status:** Draft

---

## 1. Tech Stack

| Layer | Technology | Reason |
|---|---|---|
| Language | TypeScript | Type safety, better maintainability |
| Framework | React Native 0.74+ | Cross-platform base, Android target |
| Navigation | React Navigation v6 | Standard RN navigation, stack + bottom sheet support |
| Swipe Gesture | react-native-gesture-handler + react-native-reanimated v3 | Best-in-class swipe physics and 60fps animations on the UI thread |
| Image Loading | react-native-fast-image | GPU-cached image loading, much faster than default RN Image |
| Video Playback | react-native-video | Handles local video files, playback controls, thumbnail extraction |
| Media Access | @react-native-camera-roll/camera-roll | Gallery query, batch photo access, and device deletion |
| Local Database | react-native-sqlite-storage | Bin metadata, session tracking, expiry management |
| Settings Storage | react-native-mmkv | Fast synchronous key-value store for user preferences |
| Blur / Glassmorphism | @react-native-community/blur | Native blur view for glass effect on cards and overlays |
| Permissions | react-native-permissions | Unified permission handling across Android API versions |
| Background Tasks | react-native-background-fetch | Periodic background job for expiry-based deletion |
| Minimum Android | API 29 (Android 10) | Scoped storage, covers ~95% of active devices |
| Target Android | API 35 (Android 15) | Latest Play Store requirement |

---

## 2. Architecture

The app follows a **layered architecture** with unidirectional data flow.

```
UI Layer (React Native Screens + Components)
        ↓
State Layer (React Context + useReducer / Zustand)
        ↓
Service Layer (business logic, deletion engine)
        ↓
Data Sources:
  - CameraRoll API (device gallery)
  - SQLite Database (bin metadata)
  - MMKV (user settings)
  - BackgroundFetch (scheduled expiry cleanup)
```

**State management:** Zustand for global state (bin count, current session ID, settings). Local component state for swipe card position and animation values.

---

## 3. Module Breakdown

### 3.1 Media Indexer

**Responsibility:** Query device gallery and return media in batches.

**Library:** `@react-native-camera-roll/camera-roll`

```typescript
CameraRoll.getPhotos({
  first: 50,                    // batch size
  after: cursor,                // pagination cursor
  assetType: 'All',             // photos + videos
  include: ['filename', 'fileSize', 'imageSize', 'playableDuration'],
})
```

- Returns a `PageInfo` object with `has_next_page` and `end_cursor` for pagination
- Sort order: newest first (default CameraRoll behavior on Android)
- Only metadata is fetched per batch — actual image rendering is handled by FastImage using the URI
- **Preload trigger:** When the user reaches card index `batchSize - 10`, fetch the next batch in the background and store it in state

**Filtering out bin items:**
- Before displaying a batch, filter out any URIs that exist in the SQLite `bin_items` table
- This prevents already-deleted items from showing up again in the swipe queue

### 3.2 Swipe Card Engine

**Responsibility:** Tinder-style card stack with gesture-driven keep/delete.

**Libraries:** `react-native-gesture-handler` (PanGestureHandler) + `react-native-reanimated` v3

**Implementation approach:**

```typescript
// Shared values live on the UI thread — no JS bridge involvement during gesture
const translateX = useSharedValue(0);
const translateY = useSharedValue(0);
const rotate = useDerivedValue(() =>
  `${(translateX.value / SCREEN_WIDTH) * 15}deg`
);

// Overlay opacity derived from drag distance
const keepOpacity = useDerivedValue(() =>
  Math.min(translateX.value / SWIPE_THRESHOLD, 1)
);
const deleteOpacity = useDerivedValue(() =>
  Math.min(-translateX.value / SWIPE_THRESHOLD, 1)
);
```

- **Threshold:** `SCREEN_WIDTH * 0.4` — if released past this, action fires
- **Below threshold:** Spring animation back to center using `withSpring`
- **Above threshold:** Card flies off screen using `withTiming`, then `runOnJS` callback fires to record the decision
- **Card stack:** Render 3 cards at all times — current (top), next (90% scale), and one behind (85% scale). Scale animates up as top card leaves.
- All animation runs on the **UI thread** via Reanimated worklets — no frame drops from JS bridge

### 3.3 Fullscreen Viewer

**Responsibility:** Fullscreen photo/video on card tap.

**Photo:**
- Use `react-native-reanimated` + `react-native-gesture-handler` for pinch-to-zoom
- Or use `react-native-image-zoom-viewer` as a drop-in if custom implementation is overkill for v1
- Min zoom: 1x, Max zoom: 5x
- Double tap: toggle between 1x and 2.5x

**Video:**
- `react-native-video` component
- Auto-play on open, loop enabled
- Custom controls overlay: play/pause, scrubber, mute, duration
- Controls fade out after 3 seconds of inactivity, tap to show again

**Entry/Exit:**
- Entry: shared element transition or cross-fade from card (React Navigation shared element)
- Exit: swipe down (velocity-based) or back button

**Action bar at bottom:**
- Glassmorphism pill with KEEP and DELETE buttons
- If opened from Recycle Bin: shows RESTORE and DELETE NOW instead

**Immersive mode:**
- Hide status bar and navigation bar on enter
- Restore on exit

### 3.4 Recycle Bin

**Responsibility:** Display and manage pending-delete items.

**How deletion works — critical point:**
Keepix never moves or copies the actual media file. It only stores the file's URI in SQLite. The file stays on the device until permanent deletion. This means:
- Bin uses zero extra storage
- Restore = delete the SQLite record (file was never touched)
- Permanent delete = call `CameraRoll.deletePhotos([uri])` → removes from device gallery

**Bin UI:**
- 3-column grid using `FlatList` with `numColumns={3}`
- Each thumbnail shows a countdown badge
- Long press → multi-select mode (checkbox overlay per item)
- Multi-select actions: Restore selected / Delete selected
- "Empty Bin" button at top triggers batch delete

### 3.5 Deletion Engine

**Responsibility:** Execute permanent deletion at the right time for both modes.

**Two triggers:**

**Trigger 1 — Expiry-based (retention 1+ days):**

Primary: checked **on every app launch** inside the root component's `useEffect`:
```typescript
useEffect(() => {
  DeletionService.processExpiredItems(); // query SQLite, delete expired
}, []);
```

Secondary: `react-native-background-fetch` runs a daily background job when the app is closed:
```typescript
BackgroundFetch.configure({ minimumFetchInterval: 1440 }, async (taskId) => {
  await DeletionService.processExpiredItems();
  BackgroundFetch.finish(taskId);
});
```

This dual approach means expiry is handled reliably whether the user opens the app or not.

**Trigger 2 — Session-based (retention = 0 days):**

- On every app launch, a new `session_id` (UUID) is generated and stored in MMKV
- On the same launch, before any UI renders, query SQLite for `bin_items` where `retention_mode = 'SESSION'` and `session_id != currentSessionId`
- Call `CameraRoll.deletePhotos()` for each and remove from SQLite
- This is synchronous-first: handled in the JS layer before the swipe screen renders

```typescript
// In App.tsx, before navigation renders
const previousSessionId = mmkv.getString('last_session_id');
const currentSessionId = uuid();
mmkv.set('last_session_id', currentSessionId);

if (previousSessionId) {
  await DeletionService.cleanupPreviousSession(previousSessionId);
}
```

**Why not delete on app close?**
React Native's `AppState` listener fires `'background'` when the app goes to background, but the JS thread can be suspended before async work completes. Android can also kill the process immediately. Deleting on next launch is reliable and achieves the same user-facing result.

---

## 4. Data Model

### 4.1 SQLite Database: `keepix.db`

**Table: `bin_items`**

| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | Internal ID |
| `media_uri` | TEXT NOT NULL | CameraRoll asset URI |
| `display_name` | TEXT | Filename for display in bin |
| `media_type` | TEXT | 'IMAGE' or 'VIDEO' |
| `date_taken` | INTEGER | Unix timestamp (ms) of original capture |
| `deleted_at` | INTEGER | Unix timestamp (ms) when swiped left |
| `expiry_at` | INTEGER | Unix timestamp (ms) for permanent delete. 0 = session mode |
| `session_id` | TEXT | UUID of the session that deleted this item |
| `retention_mode` | TEXT | 'TIMED' or 'SESSION' |
| `width` | INTEGER | Original media width in pixels |
| `height` | INTEGER | Original media height in pixels |
| `duration_ms` | INTEGER | Video duration in ms. 0 for images |

**Table: `user_sessions`**

| Column | Type | Description |
|---|---|---|
| `session_id` | TEXT PRIMARY KEY | UUID |
| `started_at` | INTEGER | Unix timestamp (ms) |

### 4.2 MMKV Keys (user_settings)

| Key | Type | Default | Description |
|---|---|---|---|
| `retention_days` | number | 10 | Days before permanent deletion. 0 = session mode |
| `batch_size` | number | 50 | Photos per batch |
| `onboarding_complete` | boolean | false | Whether onboarding has been shown |
| `last_session_id` | string | — | Session ID from the previous launch |

---

## 5. Permission Handling

**Library:** `react-native-permissions`

| Android API | Permissions |
|---|---|
| API 29 – 32 | `READ_EXTERNAL_STORAGE` |
| API 33+ | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` |

```typescript
import { Platform } from 'react-native';
import { request, PERMISSIONS } from 'react-native-permissions';

const permissions = Platform.Version >= 33
  ? [PERMISSIONS.ANDROID.READ_MEDIA_IMAGES, PERMISSIONS.ANDROID.READ_MEDIA_VIDEO]
  : [PERMISSIONS.ANDROID.READ_EXTERNAL_STORAGE];
```

- Check on launch, before rendering any media UI
- If denied: show rationale screen (non-dismissible)
- If permanently denied: show "Open Settings" button linking to app settings
- No permission = blank rationale screen, app is non-functional

**Deletion permission (Android 11+ / API 30+):**
`CameraRoll.deletePhotos()` on Android 11+ automatically triggers the system confirmation dialog for media not created by your app. This is an OS-enforced behavior — it cannot be bypassed. Design the UX to not show a spinner before this dialog appears, or it will look like the app hung.

---

## 6. Glassmorphism Implementation

**Library:** `@react-native-community/blur`

```tsx
import { BlurView } from '@react-native-community/blur';

<BlurView
  style={styles.cardSurface}
  blurType="dark"        // dark frosted glass
  blurAmount={20}
  reducedTransparencyFallbackColor="rgba(30,30,30,0.8)"
>
  {/* card content */}
</BlurView>
```

**Card surface spec:**
- Blur: `blurAmount={20}`, `blurType="dark"`
- Overlay tint: `rgba(255, 255, 255, 0.08)` on top of blur
- Border: 1px `rgba(255, 255, 255, 0.25)` via `borderColor`
- Corner radius: `borderRadius: 24`
- Shadow: `elevation: 12` (Android shadow)

**Swipe overlay badges:**
- "KEEP" badge: `backgroundColor: 'rgba(76, 175, 80, 0.75)'`, green
- "DELETE" badge: `backgroundColor: 'rgba(244, 67, 54, 0.75)'`, red
- Both have `borderRadius: 8`, bold white text, and opacity driven by Reanimated shared value

**Fallback (older devices where blur is expensive):**
- If device has < 3GB RAM or API < 31, degrade to a flat semi-transparent dark card (`rgba(20, 20, 20, 0.85)`) with the same border and shadow

---

## 7. Performance Requirements

| Scenario | Target |
|---|---|
| First card visible after launch | < 1.5 seconds |
| Swipe animation | 60fps, runs on UI thread via Reanimated |
| Next batch preload | Ready before user reaches card 40 of 50 |
| Image thumbnail render | < 300ms on mid-range device (FastImage cache) |
| APK size | < 30MB |
| Memory during active swiping | < 200MB |

**Memory management:**
- `FlatList` / `FlashList` with `removeClippedSubviews` and `windowSize` tuned for bin grid
- FastImage manages its own LRU memory and disk cache
- Only 3 card components are mounted at any time — others are unmounted after swipe

---

## 8. Error Handling

| Scenario | Handling |
|---|---|
| `CameraRoll.deletePhotos()` fails | Show toast error, keep item in bin, retry on next launch |
| Media URI no longer valid (file moved externally) | Catch error, silently remove from SQLite, show snackbar: *"Photo no longer on device"* |
| Permission revoked mid-session | `AppState` change listener re-checks permission, navigates to permission screen |
| System delete confirmation dialog cancelled (API 30+) | No deletion occurs, item stays in bin — no error shown |
| CameraRoll returns empty | Show empty state screen on swipe screen |
| SQLite write failure | Log error, show generic error toast, do not crash |

---

## 9. Project Structure

```
keepix/
├── android/                        # Native Android project
├── ios/                            # Not used (Android only)
├── src/
│   ├── screens/
│   │   ├── SplashScreen.tsx        # Launch gate, runs cleanup
│   │   ├── PermissionScreen.tsx
│   │   ├── OnboardingScreen.tsx
│   │   ├── SwipeScreen.tsx         # Main card swipe UI
│   │   ├── FullscreenViewer.tsx    # Photo/video fullscreen
│   │   ├── RecycleBinScreen.tsx
│   │   └── SettingsScreen.tsx
│   ├── components/
│   │   ├── SwipeCard.tsx           # Single animated card
│   │   ├── CardStack.tsx           # 3-card stack manager
│   │   ├── GlassView.tsx           # Reusable glassmorphism wrapper
│   │   ├── BinItem.tsx             # Bin grid cell
│   │   └── VideoPlayer.tsx         # react-native-video wrapper
│   ├── services/
│   │   ├── DeletionService.ts      # Expiry + session cleanup logic
│   │   ├── MediaService.ts         # CameraRoll queries, batch loading
│   │   └── BackgroundService.ts    # BackgroundFetch setup
│   ├── store/
│   │   ├── useAppStore.ts          # Zustand global state
│   │   └── useSwipeStore.ts        # Swipe session state
│   ├── db/
│   │   ├── database.ts             # SQLite init, migrations
│   │   ├── binItemsDao.ts          # CRUD for bin_items
│   │   └── sessionsDao.ts          # CRUD for user_sessions
│   ├── hooks/
│   │   ├── useMediaBatch.ts        # Batch fetching + pagination
│   │   ├── usePermissions.ts       # Permission check + request
│   │   └── useSessionCleanup.ts    # Session ID management
│   ├── navigation/
│   │   └── AppNavigator.tsx        # React Navigation stack
│   ├── theme/
│   │   ├── colors.ts               # Glassmorphism palette
│   │   ├── typography.ts
│   │   └── spacing.ts
│   └── utils/
│       ├── uuid.ts
│       └── dateHelpers.ts
├── App.tsx                         # Root, session init, cleanup trigger
├── package.json
└── tsconfig.json
```

---

## 10. Key Dependencies (package.json)

```json
{
  "dependencies": {
    "react-native": "0.74.x",
    "@react-navigation/native": "^6.x",
    "@react-navigation/stack": "^6.x",
    "react-native-gesture-handler": "^2.x",
    "react-native-reanimated": "^3.x",
    "react-native-screens": "^3.x",
    "@react-native-camera-roll/camera-roll": "^7.x",
    "react-native-fast-image": "^8.x",
    "react-native-video": "^6.x",
    "@react-native-community/blur": "^4.x",
    "react-native-permissions": "^4.x",
    "react-native-sqlite-storage": "^6.x",
    "react-native-mmkv": "^2.x",
    "react-native-background-fetch": "^4.x",
    "zustand": "^4.x",
    "react-native-uuid": "^2.x"
  }
}
```

---

## 11. Out of Scope for v1.0 (Technical)

- Cloud sync or backup
- iOS build
- Widget / home screen shortcut
- Custom MediaStore write operations
- Content sharing from within the app
- Cross-device sync
