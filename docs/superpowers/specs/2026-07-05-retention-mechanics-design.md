# Keepix Retention Mechanics — Design

- **Status:** Approved v2 (brainstorming)
- **Date:** 2026-07-05
- **Owner:** Mazin Kazi
- **Path:** `keepix-native/` (private local Android app)

---

## 1. TL;DR

Add a layered engagement system to Keepix focused on **mission-aligned dopamine** with a **mild daily streak**:

1. **Day Streak** — quiet, loss-aversion-light. Shown on a chip in the dashboard.
2. **Milestone Vault** — long-term milestones (10 / 100 / 1,000 deletes; 100 MB / 1 GB freed; 7 / 30-day streaks). Each unlock reveals a maximalist card variant.
3. **Year-in-Review** — annual story surfaced on first open after Jan 1. Bounded ~60s, skippable.

All surfaces adopt the **Maximalism / Dopamine** design system in full — heavy neon borders, multi-layer stacked + glow shadows, animated gradient text on hero typography, dot+stripe+mesh pattern overlays, floating decorative shapes, accent rotation across the 5-color palette.

Local-only. No notifications, no accounts, no cloud, no analytics SDK. Everything reads and writes to local Room tables; the user DB IS the telemetry.

---

## 2. Goals & Non-Goals

### Goals

- Users return on a daily cadence (visible streak chip, no nag).
- Long-term layer (Vault + Year-in-Review) ensures the app has reasons to be reopened weeks/months later.
- Engagement feel stays tied to the core cleanup mission — "I freed 2.4 GB" not "I scored 5,432 points."
- Visual system already shipped during the maximalism design migration is *used more aggressively* on new surfaces, not diluted.
- Zero new external dependencies.

### Non-Goals (YAGNI)

- Notifications / Android NotificationManager.
- Cloud / accounts / cross-device sync.
- Variable-reward loot-box mechanics.
- Social / sharing / leaderboards.
- IAP / premium tier.
- Analytics SDK.
- Restyling already-migrated existing screens (SwipeScreen, SettingsScreen, etc.). The Max aesthetic they carry is sufficient.

---

## 3. Background (current state)

- Keepix is an Android Kotlin/Compose app — Tinder-style photo/video cleanup.
- Local-first Room DB (`AppDatabase`).
- Existing screens: `MainActivity`, `SwipeScreen`, `KeptItemsScreen`, `RecycleBinScreen`, `SettingsScreen`, `OnboardingScreen`, `PermissionScreen`, `FullscreenViewer`.
- Recycle bin has a configurable retention period (0 → 365 days).
- Worker: `SessionCleanupWorker` runs at session end.
- Theme: already migrated to a Maximalism / Dopamine palette (`Color.kt`), with `Effects.kt` providing `Modifier.maxBox()` (stacked hard shadows + border) and `AccentsAt(i)` for systematic accent rotation.
- Existing Max components: `MaxCard`, `MaxButton`, `MaxBackground`.
- **No existing retention/engagement mechanics** — this design introduces them greenfield.

---

## 4. Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  MainActivity (existing)                 │
│  routes to YearInReviewScreen if pending, else Dashboard │
└──────────────────────────────────────────────────────────┘
                            │
              ┌─────────────┼─────────────────┐
              ▼             ▼                  ▼
   ┌───────────────┐ ┌───────────────┐ ┌──────────────────┐
   │ Dashboard     │ │ Vault         │ │ Year-in-Review   │
   │ Screen (NEW)  │ │ Screen (NEW)  │ │ Screen (NEW)     │
   └───────────────┘ └───────────────┘ └──────────────────┘
              │             │                  │
              ▼             ▼                  ▼
   ┌───────────────────────────────────────────────────────┐
   │           KeepixViewModel (extended)                  │
   │  ↳ observes AppStatsDao + StreakDao + MilestonesDao   │
   └───────────────────────────────────────────────────────┘
              │             │                  │
              ▼             ▼                  ▼
   ┌──────────────────┐ ┌────────────────┐ ┌──────────────────┐
   │ StreakRepo       │ │ AppStatsRepo   │ │ MilestonesRepo   │
   │ (date math)      │ │ (agg)          │ │ (threshold logic)│
   └──────────────────┘ └────────────────┘ └──────────────────┘
              │             │                  │
              ▼             ▼                  ▼
   ┌─────────────────────────────────────────────────┐
   │  Room v2 schema (3 new tables):                │
   │   • app_stats       (one row per local date)    │
   │   • streak_state    (single row)                │
   │   • milestone_unlocks (append-only log)         │
   └─────────────────────────────────────────────────┘
```

The retention layer reads/writes only through the new Room schema. Existing code paths (swipe outcome, bin, retention) are *observers* via existing ViewModel hooks, not new flows.

---

## 5. Data model

### 5.1 `app_stats`

```kotlin
@Entity(tableName = "app_stats")
data class AppStatsEntity(
    @PrimaryKey val localDate: String,    // "2026-07-05"
    val photosSwiped: Int = 0,
    val bytesFreed: Long = 0L,
    val sessionCount: Int = 0,
    val minutesInApp: Int = 0,
    val firstOpenEpochMs: Long,            // set at first write
    val lastTouchEpochMs: Long,            // updated on each stat bump
)
```

- PK is the calendar local-date so upsert per day is idempotent.
- All cumulative counters reset to 0 if user uninstalls and reinstalls (acceptable; matches the "fresh start on device change" model).

### 5.2 `streak_state`

```kotlin
@Entity(tableName = "streak_state")
data class StreakStateEntity(
    @PrimaryKey val id: Int = 1,           // always 1
    val lastOpenDate: String?,            // null until first open
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastUpdatedEpochMs: Long = 0L,
)
```

Single-row table. Streak logic operates on `lastOpenDate` only.

### 5.3 `milestone_unlocks`

```kotlin
@Entity(tableName = "milestone_unlocks")
data class MilestoneUnlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val milestoneKey: String,             // "DELETES_100", "BYTES_1GB", "STREAK_7"
    val unlockedAtEpochMs: Long,
    val accentOffset: Int,                // 0..4 — index into Accents for chrome
    val unlockValue: Long,                // e.g., 100 for DELETES_100
)
```

Append-only. (milestoneKey, unlockedAtEpochMs) combination is the natural key — uniqueness enforced by a DB-level unique constraint.

### 5.4 `app_meta`

```kotlin
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,          // "YEAR_IN_REVIEW_PENDING_FOR_YEAR" etc.
    val value: String,
    val setAtEpochMs: Long,
)
```

A trivial key-value store for cross-session flags that don't belong to any user-data table. Row keys are constants, not user input. Initial row set:

- `"YEAR_IN_REVIEW_PENDING_FOR_YEAR"` — set to the previous year's integer (e.g., `"2026"`) by `MilestonesRepo.ensureYearInReviewFlag(prevYear)` during the day-1 stat write. Cleared when the user reaches the last page of (or skips) `YearInReviewScreen`.
- `"FIRST_OPEN_EVER_EPOCH_MS"` — set on first ever app open. Read by `StreakRepo.observeOnOpen()` to know if it's "first ever" vs "after gap."

### 5.5 Bump-vs-check contract

- `AppStatsRepo.recordSwipeLeft(item)` — atomic. Bumps today's `photosSwiped` and `bytesFreed`, then triggers `MilestonesRepo.checkThresholds()`.
- `MilestonesRepo.checkThresholds()` — runs in the same transaction; never duplicates writes thanks to the unique constraint.
- `StreakRepo.observeOnOpen()` — runs once at app open. Decides streak continuation or break.
- `AppStatsRepo.recordSessionEnd(minutes)` — called from `SessionCleanupWorker` (existing) at the natural session boundary.

---

## 6. Mechanic surfaces

### 6.1 Streak (Daily)

- **Logic.** At app open:
  - `lastOpenDate == today` → no change.
  - `lastOpenDate == yesterday` → `currentStreak += 1`, persist `lastOpenDate = today`, bump `longestStreak` if needed.
  - Else (gap ≥ 2 days, or first-ever open) → `currentStreak = 1`, persist `lastOpenDate = today`.
- **Quiet break.** Streak resets silently — no nag, no negative feedback.
- **UI surface:** `StreakChip.kt`. ≤ 64dp tall; fixed accent (`BorderYellow`); double-stack hard shadow (`magenta + cyan`); pulse-glow on the shadow at 1.5s loop; text is animated gradient-shift on `DAY N`. Tap → `float` animation.

### 6.2 Milestone Vault (Daily + Long-term)

Thresholds (Phase 1, deletable later):

| Key | Trigger |
|---|---|
| `DELETES_10` | 10 cumulative swipe-lefts |
| `DELETES_100` | 100 cumulative |
| `DELETES_1000` | 1,000 cumulative |
| `BYTES_100MB` | 100 MB freed |
| `BYTES_1GB`  | 1 GB freed |
| `STREAK_7` | 7-day streak |
| `STREAK_30` | 30-day streak |

On threshold crossed:

1. `MilestoneUnlockEntity` row written.
2. One-shot `MilestoneUnlocked(milestoneKey, accentOffset, value)` sharedflow event emitted by `KeepixViewModel`.
3. `MainActivity` collects the event and shows `MilestoneUnlockDialog`.

**`MilestoneUnlockDialog`** (the maximalist showcase):
- Backdrop = `MaxBackground`.
- Massive backdrop typography: `✦ UNLOCKED ✦`, `text-[16rem]`, magenta, 0.2 opacity, bleed off top edge.
- Two pattern overlays: dot-grid (0.10 alpha, ties to `accentOffset` color) + diagonal stripes (0.06 alpha).
- 3-5 `FloatingShape` emoji/stars (random spread), each animated with `float` or `float-reverse`, 4-8s loops.
- Central `MaxCard` with gradient background (rotated via `accentOffset`), `BorderWidths.Heavy` clashing border, triple-stack hard shadow plus pulse-glow.
- Inside: hero text (animated gradient-shift, triple-stack hard text shadow in clash colors), stats sub-line (single hard text shadow).
- Dismiss CTA: MaxButton with gradient bg + clashing yellow border + pulse-glow. Press = scale-down 0.95 + shadow retract (physical press feel).

**`MilestoneUnlockCard`** (vault tile) — `LOCKED` vs `UNLOCKED`:
- LOCKED: desaturated MutedSurface bg, dashed `BorderWidths.Subtle`, no animation, "DELETES 1,000 — LOCKED" caption.
- UNLOCKED: gradient bg, `BorderWidths.Heavy` clashing border (rotated via `accentOffset`), triple-stack hard shadow + pulse-glow, hero text with triple-stack text shadow, dot-grid + stripes overlays, big emoji decoration (`🚀` for deletes, `📦` for bytes, `🔥` for streak). Idle `float-reverse`; tap `wiggle`.

**`VaultScreen`** — vertical list of `MilestoneUnlockCard`s. Unlocked tiles first (most recent first by `unlockedAtEpochMs`); locked tiles after, ordered by the user's progress percentage toward each threshold (closest-to-unlock first). Asymmetric positioning per the design system (rotate ±1°–2°, alternating `translate-y-8`).

### 6.3 Year-in-Review (Long-term)

- **Trigger.** On first app open after Jan 1, `MainActivity` reads `app_meta["YEAR_IN_REVIEW_PENDING_FOR_YEAR"]`. If set and equal to the previous calendar year, route the user through `YearInReviewScreen` before `DashboardScreen`. Always skippable; clearing the flag on dismiss or skip.
- **Pages (5-7):**
  1. **Cover** — backdrop "YOUR 2026" in clashing magenta/cyan gradient text. Floating stars. Pulse-glow border ring around hero.
  2. **Total swipes** — `text-9xl` with triple-stack hard text shadow. Counter animates from 0 → value over 1.5s.
  3. **Bytes freed** — hero number + chromatic month-chart (dot-per-month, size = bytes).
  4. **Longest streak** — "ladder" of stacked accent rectangles; streak row glows, others desaturated. Asymmetric offsets.
  5. **Monthly rhythm** — calendar grid; each day-tile colored by intensity via `AccentsAt(i)`; mixed solid/dashed borders.
  6. **Closing** — full chaos: 4 patterns, 5+ floating emojis, animated gradient text, pulse-glow CTA to dismiss.
- Page transition: Compose `HorizontalPager`, 250ms each, bouncy easing.

### 6.4 Dashboard integration

New `DashboardScreen` becomes the primary entry from `MainActivity`. It carries:

- **Top:** `StreakChip`.
- **Hero row:** 3 "TODAY'S NUMBERS" tiles (photos swiped, bytes freed, minutes). Asymmetric offsets per the design system (broken grid). Each tile is a `MaxCard` variant with rotated accent.
- **Mid band:** "Next milestone" preview — gradient bar with progress fill in clashing accent, upcoming threshold number visible (`"⇡ 87 swipes until 💎 SILVER VAULT"`). Pulse-glow.
- **Bottom:** primary CTA "START SWIPING" (gradient bg + clashing yellow border + pulse-glow). Secondary outline "VIEW VAULT" (dashed border; on hover fills with solid accent).
- **Backdrop:** `MaxBackground` + diagonal stripes + dot-grid + 4-5 floating decorative shapes in corners.

---

## 7. New theme modules

To make the maximalist treatment *mechanical* and not aspirational, three theme files are added:

### `ui/theme/Type.kt` (extend)

```kotlin
// Imports existing KeepixTypography. Adds:
val Display7xl = TextStyle(
    fontFamily = KeepixFontFamily.Heading,   // "Outfit" / "Unbounded"
    fontWeight = FontWeight.Black,            // 900
    fontSize = 128.sp,                       // text-9xl range
    lineHeight = 132.sp,
    letterSpacing = (-2).sp,                  // tracking-tighter
    textTransform = TextTransform.Uppercase, // via Locale in actual styling
)
```

(Compose applies case via `String.uppercase(Locale.current)` in composables — no longer a `TextStyle` property. Display a style sample below.)

### `ui/theme/Effects.kt` (extend)

```kotlin
fun Modifier.glow(
    color: Color,
    radius: Dp = 24.dp,
    alpha: Float = 0.5f,
): Modifier = ...

fun Modifier.clashingBorder(
    color: Color,
    width: Dp = BorderWidths.Standard,
    style: BorderStyle = BorderStyle.Solid, // (Solid, Dashed, Dotted, Double)
): Modifier = ...
```

> Implementation note: Compose's `BorderStroke` only supports a single style. `style: Dashed`, `Dotted`, and `Double` are drawn via `.drawBehind{}` using stroke caps and pattern math — but only the visual is faked; modifier returns a `Modifier` chaining the border draw layer over a clipped shape. Acceptable for spec purposes; concrete math is implementation-plan work.

### `ui/theme/Patterns.kt` (NEW)

Composable overlays. Each returns a Box that consumers stack behind content:

```kotlin
@Composable
fun PatternDotsOverlay(color: Color = BorderMagenta, spacing: Dp = 20.dp, dotSize: Dp = 1.5.dp, alpha: Float = 0.10f, ...) = ...

@Composable
fun PatternStripesOverlay(color: Color = BorderYellow, angleDeg: Float = 45f, stripeWidth: Dp = 14.dp, alpha: Float = 0.06f, ...) = ...

@Composable
fun PatternCheckerOverlay(color: Color = BorderCyan, cellSize: Dp = 40.dp, alpha: Float = 0.05f, ...) = ...

@Composable
fun PatternMeshOverlay(accentA: Color = BorderMagenta, accentB: Color = BorderCyan, accentC: Color = BorderPurple, alpha: Float = 0.18f, ...) = ...
```

### `ui/theme/Motion.kt` (NEW)

```kotlin
data class MotionSpec(
    val durationMs: Int,
    val easing: Easing,
    val iteration: RepeatMode,
)

object MotionSpecs {
    val Float = MotionSpec(6000, FastOutSlowInEasing, RepeatMode.Reverse)
    val FloatReverse = MotionSpec(5000, FastOutSlowInEasing, RepeatMode.Reverse)
    val PulseGlow = MotionSpec(1500, FastOutSlowInEasing, RepeatMode.Reverse)
    val Wiggle = MotionSpec(1000, FastOutSlowInEasing, RepeatMode.Reverse)
    val BounceSubtle = MotionSpec(2000, FastOutSlowInEasing, RepeatMode.Reverse)
    val GradientShift = MotionSpec(4000, LinearEasing, RepeatMode.Reverse)
    val SpinSlow = MotionSpec(20000, LinearEasing, RepeatMode.Restart)
}
```

Helpers: `@Composable fun Modifier.float(spec: MotionSpec): Modifier`, etc., each one wrapping `rememberInfiniteTransition()`. All helpers short-circuit to the original modifier if `LocalReducedMotion.current == true`.

### `ui/theme/Decor.kt` (NEW)

```kotlin
@Composable
fun FloatingShape(
    type: FloatingShapeType,    // Star, Spark, Ring, Square, Emoji
    color: Color,
    size: Dp = 40.dp,
    initialOffset: Offset = Offset.Zero,
    motionSpec: MotionSpec = MotionSpecs.Float,
    modifier: Modifier = Modifier,
) = ...

@Composable
fun BackdropText(
    text: String,
    color: Color,
    size: TextUnit = 200.sp,
    opacity: Float = 0.20f,
    modifier: Modifier = Modifier,
) = ...

@Composable
fun GradientText(
    text: String,
    brush: Brush,
    shadow: TextShadowStack? = null,
    motionSpec: MotionSpec = MotionSpecs.GradientShift,
    modifier: Modifier = Modifier,
) = ...

data class TextShadowStack(
    val layers: List<Pair<Offset, Color>>,  // e.g. 3 layers in clash colors
)
```

---

## 8. New components

| Component | Purpose |
|---|---|
| `ui/components/StreakChip.kt` | Small badge with spark + day count. Pulse-glow. |
| `ui/components/MilestoneUnlockCard.kt` | Vault tile. Two composable variants: `MilestoneUnlockCardLocked`, `MilestoneUnlockCardUnlocked`. |
| `ui/components/MilestoneUnlockDialog.kt` | Full celebration modal. Shows on threshold crossed. |
| `ui/components/MaxCard.kt` (extend) | New variants: `MaxCardChatty(pulseGlow = true)`, `MaxCardGradient(brush)`. |
| `ui/components/MaxButton.kt` (split from `MaxCard.kt`) | New variant: `MaxButtonPulsing(accentRotation = true)`. |
| `ui/components/MassiveHero.kt` | Container that composes `BackdropText` + `FloatingShape`s + pulse-glow ring. Used on Year-in-Review cover. |
| `ui/components/DashboardTile.kt` | Single "TODAY'S NUMBER" tile. Rotating accent per index in row. Pulse-glow on refresh. |
| `ui/components/NextMilestoneBar.kt` | Progress bar with clashing accent fill. Pulse-glow. |
| `ui/components/MonthHeatmap.kt` | Calendar grid colored by intensity via `AccentsAt(i)`. Mixed border styles. |
| `ui/components/StreakLadder.kt` | Horizontal stacked rectangles; streak row glows. |
| `ui/components/YIRPage.kt` | Single page of the Year-in-Review story (5-7 of these composed). |
| `ui/dashboard/AsymmetricOffset.kt` | Layout helper that applies `translate-y-8` to rows by `index % 2`. |

---

## 9. New screens

| Screen | Parent/Entry |
|---|---|
| `ui/screens/DashboardScreen.kt` | Routes here from `MainActivity` after deciding no `YearInReviewScreen` is pending. |
| `ui/screens/VaultScreen.kt` | Reached from Dashboard's "VIEW VAULT" secondary CTA. |
| `ui/screens/YearInReviewScreen.kt` | Reached from `MainActivity` on first open after Jan 1 if pending. Skippable. |

---

## 10. Hooks into existing code

| Existing surface | Hook |
|---|---|
| `MainActivity.kt` | Routes between `YearInReviewScreen` → `DashboardScreen` → existing app screens. |
| `KeepixViewModel.logSwipeLeft` (already determines outcomes) | After writing the bin row, also call `AppStatsRepo.recordSwipeLeft(item)`. |
| `SessionCleanupWorker` (existing) | At session end, write `AppStatsRepo.recordSessionEnd(minutes)`. |
| `RecycleBinScreen` | Add a small "⬢ VAULT" link in the top bar to encourage cross-screen movement. |

All hooks run *after* the primary write to ensure the cleanup action completes regardless of stats layer health.

---

## 11. Tests

| Layer | What's tested |
|---|---|
| Unit — `StreakRepo` | day-math; continuation across midnight; break after gap; timezone/local-date consistency; first-ever open. |
| Unit — `MilestonesRepo` | each threshold fires exactly once per `milestoneKey`; uniqueness constraint enforced; ordering by `unlockedAtEpochMs` consistent. |
| Unit — `AppStatsDao` | upsert idempotent per `localDate`; total counts match sum of daily rows. |
| Unit — `YearInReviewQuery` | aggregation returns right shape per last year; empty year (no data) renders a graceful "see you in 2027" page. |
| Compose UI — `StreakChip` | text content "DAY N" correct, accessibility label set, reduced-motion flag disables animation. |
| Compose UI — `MilestoneUnlockDialog` | dismiss button works; appears once per `milestoneKey`. |
| Compose UI — `DashboardScreen` | streak chip and three tiles render; navigation to SwipeScreen and VaultScreen works. |
| Instrumented — E2E | simulate open → swipe left several times → drop session → assert `app_stats` and `milestone_unlocks` rows correct. |

---

## 12. Open decisions (adopted defaults)

1. **Streak chip accent** — fixed at `BorderYellow` (the user's "hot streak" hot marker). Rainbow-per-day rejected; too busy.
2. **Vault grid** — asymmetric broken grid on tablet+, 1-column scrollable list on phones (auto-break at `WindowWidthSizeClass.Compact`).
3. **Year-in-Review ceiling** — bounded ~60s with always-visible Skip. Recommended default.
4. **Floating shape density** — 4-6 on Dashboard, 8-12 on Year-in-Review pages. Tame-but-loud default; visually verify during implementation and adjust if the design rubric feels too thin or too chaotic.

---

## 13. What's OUT (YAGNI extended)

- Notifications.
- Cloud / accounts / sync.
- Variable-reward loot boxes.
- Social / sharing / leaderboards.
- IAP.
- Analytics SDK.
- New onboarding redesign.
- Visual restyle of existing screens.
- Cross-device / cross-install streak preservation.

---

## 14. Future work (next brainstorm tier)

- **Notifications** (requires runtime permission + NotificationManager) once baseline engagement metrics confirm a need for active re-engagement.
- **Visual restyle** of existing screens (SwipeScreen etc.) to lift them closer to the new maximalist intensity of the retention screens. Out of scope for this spec because they already underwent the migration.
- **Cross-device streak** if/when accounts become a thing.
- **Cohort analytics** in-app (aggregating the same Room tables) to surface personal patterns ("you tend to clean most on Sundays").
- **Year-in-Review export** to PNG/share card if social sharing is ever enabled.

---

## 15. Approval & next step

- **Status:** Approved v2 by Mazin.
- **Next step:** Run self-review, then await user's review of the written file before invoking `writing-plans`.
