# Keepix Max/Dopamine UI Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite ALL Keepix screens with the full Maximalism/Dopamine design system — Pattern overlays, FloatingShapes, GradientText, MotionSpecs, clashing borders, stacked shadows, asymmetric layouts, accent rotation.

**Architecture:** Build primitives first (Patterns, Motion, Decor, BorderWidths) → Extend MaxCard/MaxButton → Rewrite each screen top-to-bottom → Wire retention screens (Dashboard/Vault/YIR) → Polish & QA.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, existing Color.kt/Effects.kt/Type.kt/MaxBackground/MaxCard/MaxButton.

---

## Global Constraints

- **Fonts:** Outfit/Unbounded (headings, Black 900), DM Sans (body), Bangers/Bungee (display) — bundle .ttf in `res/font/`
- **Colors:** CosmicBlack base, MutedSurface cards, 5-accent wheel (Magenta/Cyan/Yellow/Orange/Purple) + DangerRed/KeepLime gestures
- **No new deps** — pure Compose
- **Reduced motion** respected everywhere (`LocalReducedMotion.current`)
- **60fps target** on mid-range Android 10+
- **Atomic commits per task** — test passes before commit

---

## File Map (What Gets Created/Modified)

### NEW Theme Primitives
| File | Purpose |
|------|---------|
| `ui/theme/BorderWidths.kt` | Subtle/Standard/Heavy/Hero constants |
| `ui/theme/Patterns.kt` | Dots/Stripes/Checker/Mesh composables |
| `ui/theme/Motion.kt` | MotionSpecs + float/pulseGlow/wiggle/bounce/gradientShift modifiers |
| `ui/theme/Decor.kt` | FloatingShape, BackdropText, GradientText, TextShadowStack |
| `res/font/outfit_bold.ttf` etc. | Font assets |

### EXTENDED Theme Files
| File | Additions |
|------|-----------|
| `ui/theme/Type.kt` | Display7xl (128sp), Display9xl (160sp) |
| `ui/theme/Effects.kt` | Modifier.glow(), clashingBorder(style: Solid/Dashed/Dotted/Double) |

### EXTENDED Components
| File | New Variants |
|------|--------------|
| `ui/components/MaxCard.kt` | MaxCardChatty(pulseGlow), MaxCardGradient(brush) |
| `ui/components/MaxButton.kt` | MaxButtonPulsing(accentRotation) |

### NEW Components
| File | Purpose |
|------|---------|
| `ui/components/StreakChip.kt` | DAY N badge with pulse-glow |
| `ui/components/MilestoneUnlockCard.kt` | Locked/Unlocked vault tiles |
| `ui/components/MilestoneUnlockDialog.kt` | Celebration modal |
| `ui/components/MassiveHero.kt` | YIR cover: BackdropText + FloatingShapes + pulse ring |
| `ui/components/DashboardTile.kt` | "TODAY'S NUMBERS" tile with rotated accent |
| `ui/components/NextMilestoneBar.kt` | Progress bar with clashing fill + pulse-glow |
| `ui/components/MonthHeatmap.kt` | Calendar grid colored by intensity |
| `ui/components/StreakLadder.kt` | Stacked accent rectangles, streak row glows |
| `ui/components/YIRPage.kt` | Single Year-in-Review page |
| `ui/dashboard/AsymmetricOffset.kt` | translate-y-8 per index parity |

### NEW Screens
| File | Parent/Entry |
|------|--------------|
| `ui/screens/DashboardScreen.kt` | MainActivity → after YearInReview check |
| `ui/screens/VaultScreen.kt` | Dashboard "VIEW VAULT" CTA |
| `ui/screens/YearInReviewScreen.kt` | MainActivity → first open after Jan 1 |

### REWRITTEN Screens (existing paths)
| File | Key Changes |
|------|-------------|
| `ui/SwipeScreen.kt` | MaxBackground + patterns, MaxCardChatty stack, SideSwipeLights as pattern, asymmetric metadata bar, MaxButtonPulsing CTAs |
| `ui/RecycleBinScreen.kt` | MaxBackground, PatternDots/Stripes, MaxCard session banner, BinGridItem → MaxCardChatty, asymmetric grid |
| `ui/KeptItemsScreen.kt` | MaxBackground, PatternMesh, MaxCardChatty grid, MassiveHero empty state |
| `ui/SettingsScreen.kt` | MaxBackground, PatternStripes, MaxCard sections, MaxButtonPulsing empty bin |
| `ui/FullscreenViewer.kt` | MaxBackground, PatternChecker, MaxCard page indicator, MaxButtonPulsing CTAs, GradientText metadata |
| `ui/OnboardingScreen.kt` | Polish: MassiveHero, FloatingShapes, GradientText, MaxButtonPulsing CTA |
| `ui/PermissionScreen.kt` | Polish: MassiveHero, FloatingShapes, MaxButtonPulsing CTA |

### EXTENDED Files
| File | Changes |
|------|---------|
| `MainActivity.kt` | Route to YearInReviewScreen → DashboardScreen → existing screens |

---

## Task List

### Phase 0: Font Assets (One-time setup)

#### Task 0.1: Add font .ttf files
**Files:**
- Create: `app/src/main/res/font/outfit_bold.ttf`, `outfit_black.ttf`, `dm_sans_regular.ttf`, `dm_sans_medium.ttf`, `dm_sans_bold.ttf`, `bungee_regular.ttf`
- Modify: `ui/theme/Type.kt` — register FontFamily with .ttf refs

- [ ] **Step 1:** Download Outfit (Bold 700, Black 900), DM Sans (Regular 400, Medium 500, Bold 700), Bungee (Regular 400) .ttf → place in `res/font/`
- [ ] **Step 2:** Update `Type.kt`:
```kotlin
internal val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_black, FontWeight.Black)
)
internal val DMSansFontFamily = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_bold, FontWeight.Bold)
)
internal val BungeeFontFamily = FontFamily(Font(R.font.bungee_regular, FontWeight.Normal))
```
- [ ] **Step 3:** Replace `DisplayFamily = OutfitFontFamily`, `HeadingFamily = OutfitFontFamily`, `BodyFamily = DMSansFontFamily`, add `DisplayFamily = BungeeFontFamily` for Display7xl/9xl
- [ ] **Step 4:** `./gradlew compileDebugKotlin` → verify no font resolution errors
- [ ] **Step 5:** Commit: `feat: add Outfit/DM Sans/Bungee font assets + Type.kt registration`

### Phase 1: Theme Primitives (Foundation)

#### Task 1.1: BorderWidths.kt
**Files:** Create `ui/theme/BorderWidths.kt`
- [ ] **Step 1:** Write object with Subtle(2dp), Standard(4dp), Heavy(8dp), Hero(12dp)
- [ ] **Step 2:** `./gradlew compileDebugKotlin` → verify
- [ ] **Step 3:** Commit: `feat(theme): add BorderWidths constants`

#### Task 1.2: Patterns.kt
**Files:** Create `ui/theme/Patterns.kt`
- [ ] **Step 1:** Implement `PatternDotsOverlay`, `PatternStripesOverlay`, `PatternCheckerOverlay`, `PatternMeshOverlay` composables (exact code from design)
- [ ] **Step 2:** Add `@Preview` for each pattern on 320dp×560dp
- [ ] **Step 3:** Commit: `feat(theme): add Pattern overlays (Dots, Stripes, Checker, Mesh)`

#### Task 1.3: Motion.kt
**Files:** Create `ui/theme/Motion.kt`
- [ ] **Step 1:** `MotionSpec` data class + `MotionSpecs` object (Float, FloatReverse, PulseGlow, Wiggle, BounceSubtle, GradientShift, SpinSlow)
- [ ] **Step 2:** Composable modifiers: `Modifier.float()`, `Modifier.pulseGlow()`, `Modifier.wiggle()`, `Modifier.bounceSubtle()`, `Modifier.gradientShift()` — all check `LocalReducedMotion.current`
- [ ] **Step 3:** Unit test: `MotionSpecs.Float.durationMs == 6000`
- [ ] **Step 4:** Commit: `feat(theme): add MotionSpecs + animated modifiers`

#### Task 1.4: Decor.kt
**Files:** Create `ui/theme/Decor.kt`
- [ ] **Step 1:** `FloatingShape` (Star/Spark/Ring/Square/Emoji), `BackdropText`, `GradientText`, `TextShadowStack`
- [ ] **Step 2:** `@Preview` each with different params
- [ ] **Step 3:** Commit: `feat(theme): add Decor components (FloatingShape, GradientText, BackdropText)`

#### Task 1.5: Extend Effects.kt
**Files:** Modify `ui/theme/Effects.kt`
- [ ] **Step 1:** Add `Modifier.glow(color, radius=24dp, alpha=0.5f)` using `drawBehind` + blur
- [ ] **Step 2:** Add `Modifier.clashingBorder(color, width=BorderWidths.Standard, style: BorderStyle)` where `BorderStyle = enum class { Solid, Dashed, Dotted, Double }` — implement Dashed/Dotted/Double via `drawBehind` with `PathEffect`
- [ ] **Step 3:** Commit: `feat(theme): add glow + clashingBorder modifiers`

#### Task 1.6: Extend Type.kt
**Files:** Modify `ui/theme/Type.kt`
- [ ] **Step 1:** Add `Display7xl` (128sp) and `Display9xl` (160sp) using `BungeeFontFamily`, FontWeight.Black
- [ ] **Step 2:** Commit: `feat(theme): add Display7xl/9xl typography`

### Phase 2: Extended Components

#### Task 2.1: MaxCard.kt — Chatty & Gradient variants
**Files:** Modify `ui/components/MaxCard.kt`
- [ ] **Step 1:** Add `MaxCardChatty(pulseGlow=true)` — wraps MaxCard with animated glow from `MotionSpecs.PulseGlow`
- [ ] **Step 2:** Add `MaxCardGradient(brush, borderColor, borderWidth=Heavy)` — uses `drawBehind` for stacked shadows + custom brush background
- [ ] **Step 3:** `@Preview` both variants
- [ ] **Step 4:** Commit: `feat(components): add MaxCardChatty + MaxCardGradient variants`

#### Task 2.2: MaxButton.kt — Pulsing variant
**Files:** Modify `ui/components/MaxButton.kt`
- [ ] **Step 1:** Add `MaxButtonPulsing(accentRotation=true)` — rotates `AccentsAt(accentIndex)` via infinite transition, applies `pulseGlow()`
- [ ] **Step 2:** `@Preview` with rotation
- [ ] **Step 3:** Commit: `feat(components): add MaxButtonPulsing with accent rotation`

#### Task 2.3: StreakChip.kt
**Files:** Create `ui/components/StreakChip.kt`
- [ ] **Step 1:** `StreakChip(dayCount: Int)` — MaxCard height 64dp, BorderYellow accent, pulseGlow, fire emoji + "DAY N" in labelLarge
- [ ] **Step 2:** `@Preview` with dayCount=7, dayCount=30
- [ ] **Step 3:** Commit: `feat(components): add StreakChip`

#### Task 2.4: MilestoneUnlockCard.kt
**Files:** Create `ui/components/MilestoneUnlockCard.kt`
- [ ] **Step 1:** `MilestoneUnlockCardLocked(title, progress, accentIndex)` — desaturated bg, dashed Subtle border, progress bar
- [ ] **Step 2:** `MilestoneUnlockCardUnlocked(title, accentIndex)` — gradient bg, Heavy clashing border, triple-shadow + pulseGlow, emoji decoration (🚀/📦/🔥)
- [ ] **Step 3:** `@Preview` both states
- [ ] **Step 4:** Commit: `feat(components): add MilestoneUnlockCard (Locked/Unlocked)`

#### Task 2.5: MilestoneUnlockDialog.kt
**Files:** Create `ui/components/MilestoneUnlockDialog.kt`
- [ ] **Step 1:** Full-screen modal: MaxBackground + PatternDots + PatternStripes + 3-5 FloatingShapes + MassiveHero-style backdrop "✦ UNLOCKED ✦"
- [ ] **Step 2:** Central MaxCardGradient + Heavy clashing border + triple-shadow + pulseGlow, GradientText hero, MaxButtonPulsing dismiss
- [ ] **Step 3:** `@Preview` (use Dialog on island)
- [ ] **Step 4:** Commit: `feat(components): add MilestoneUnlockDialog`

#### Task 2.6: MassiveHero.kt
**Files:** Create `ui/components/MassiveHero.kt`
- [ ] **Step 1:** Container: BackdropText (200sp, 0.2 opacity, bleed top) + 4-5 FloatingShapes (Emoji/Star) + pulse-glow ring
- [ ] **Step 2:** `@Preview`
- [ ] **Step 3:** Commit: `feat(components): add MassiveHero`

#### Task 2.7: DashboardTile.kt
**Files:** Create `ui/components/DashboardTile.kt`
- [ ] **Step 1:** `DashboardTile(label, value, accentIndex)` — MaxCardChatty, rotated accent per index, GradientText value, pulseGlow on refresh
- [ ] **Step 2:** `@Preview` row of 3 with different accents
- [ ] **Step 3:** Commit: `feat(components): add DashboardTile`

#### Task 2.8: NextMilestoneBar.kt
**Files:** Create `ui/components/NextMilestoneBar.kt`
- [ ] **Step 1:** `NextMilestoneBar(progress, label, accentIndex)` — gradient fill with clashing accent, pulseGlow, "⇡ N until 💎 VAULT" text
- [ ] **Step 2:** `@Preview`
- [ ] **Step 3:** Commit: `feat(components): add NextMilestoneBar`

#### Task 2.9: MonthHeatmap.kt
**Files:** Create `ui/components/MonthHeatmap.kt`
- [ ] **Step 1:** Calendar grid (6 rows × 7 cols), each cell colored by `AccentsAt(intensityIndex)`, mixed Solid/Dashed borders per index parity
- [ ] **Step 2:** `@Preview` with mock data
- [ ] **Step 3:** Commit: `feat(components): add MonthHeatmap`

#### Task 2.10: StreakLadder.kt
**Files:** Create `ui/components/StreakLadder.kt`
- [ ] **Step 1:** Horizontal stack of accent rectangles; streak row has glow + Float animation, others desaturated
- [ ] **Step 2:** `@Preview`
- [ ] **Step 3:** Commit: `feat(components): add StreakLadder`

#### Task 2.11: YIRPage.kt
**Files:** Create `ui/components/YIRPage.kt`
- [ ] **Step 1:** `YIRPage(content: @Composable () -> Unit)` — handles page padding, asymmetric offset helper
- [ ] **Step 2:** `@Preview`
- [ ] **Step 3:** Commit: `feat(components): add YIRPage`

#### Task 2.12: AsymmetricOffset.kt
**Files:** Create `ui/dashboard/AsymmetricOffset.kt`
- [ ] **Step 1:** `Modifier.asymmetricOffset(index: Int)` → `translateY = if (index % 2 == 0) 8.dp else -8.dp` + `rotate = (-1) * (1 + (index % 3))`
- [ ] **Step 2:** Commit: `feat(dashboard): add asymmetricOffset layout helper`

### Phase 3: Screen Rewrites (Existing Screens)

#### Task 3.1: SwipeScreen.kt — Full Max Rewrite
**Files:** Modify `ui/SwipeScreen.kt`
- [ ] **Step 1:** Wrap root in `MaxBackground { PatternDotsOverlay() + PatternStripesOverlay() + PatternMeshOverlay() }`
- [ ] **Step 2:** Card stack → `MaxCardChatty(pulseGlow=true, accentIndex=0)` for top card, behind cards use `MaxCard` with reduced alpha
- [ ] **Step 3:** Replace `SideSwipeLights` with `PatternStripesOverlay` driven by swipe progress (clashing accent)
- [ ] **Step 4:** Metadata bar → `MaxCardChatty` with `asymmetricOffset(0)`
- [ ] **Step 5:** Empty state → `MassiveHero` + `MaxButtonPulsing("START SWIPING")` + secondary outline `MaxButton("VIEW VAULT")`
- [ ] **Step 6:** Top bar badges → `MaxCard` chips with pulseGlow on bin/kept counts
- [ ] **Step 7:** `./gradlew compileDebugKotlin` → fix any compile errors
- [ ] **Step 8:** Commit: `feat(swipe): full Max rewrite — patterns, chatty cards, asymmetric bars`

#### Task 3.2: RecycleBinScreen.kt — Full Max Rewrite
**Files:** Modify `ui/RecycleBinScreen.kt`
- [ ] **Step 1:** `MaxBackground + PatternDotsOverlay(0.12) + PatternStripesOverlay(0.08)`
- [ ] **Step 2:** Session banner → `MaxCardChatty` with `accentIndex=3` (BorderOrange), pulseGlow
- [ ] **Step 3:** Grid items → `MaxCardChatty` with `accentIndex=i`, `FloatReverse` idle, `Wiggle` on tap
- [ ] **Step 4:** Badge → `GradientText` with `TextShadowStack` (3 clash layers)
- [ ] **Step 5:** Empty state → `MassiveHero` with "🗑️ BIN IS EMPTY" + `FloatingShape` emojis
- [ ] **Step 6:** TopAppBar "EMPTY BIN" → `MaxButtonPulsing` with `accentRotation=true`
- [ ] **Step 7:** Compile + commit: `feat(bin): full Max rewrite — chatty grid, gradient badges, floating empty state`

#### Task 3.3: KeptItemsScreen.kt — Full Max Rewrite
**Files:** Modify `ui/KeptItemsScreen.kt`
- [ ] **Step 1:** `MaxBackground + PatternMeshOverlay(0.15)`
- [ ] **Step 2:** Grid → `MaxCardChatty(accentIndex=i, pulseGlow=true)`
- [ ] **Step 3:** Empty state → `MassiveHero` with "💚 NO KEEPS YET" + `MaxButtonPulsing("START SWIPING")`
- [ ] **Step 4:** TopAppBar title → `GradientText` "KEPT ITEMS"
- [ ] **Step 5:** Compile + commit: `feat(kept): full Max rewrite — mesh bg, chatty grid`

#### Task 3.4: SettingsScreen.kt — Full Max Rewrite
**Files:** Modify `ui/SettingsScreen.kt`
- [ ] **Step 1:** `MaxBackground + PatternStripesOverlay(angle=45, alpha=0.08)`
- [ ] **Step 2:** Section headers → `GradientText` + `BackdropText` emoji
- [ ] **Step 3:** Each section card → `MaxCardChatty` with rotating accent
- [ ] **Step 3:** Slider track → `clashingBorder` + `pulseGlow` on thumb
- [ ] **Step 4:** "Empty Bin" button → `MaxButtonPulsing(accentRotation=true)`
- [ ] **Step 5:** Compile + commit: `feat(settings): Max rewrite — striped bg, chatty sections, pulsing empty bin`

#### Task 3.5: FullscreenViewer.kt — Full Max Rewrite
**Files:** Modify `ui/FullscreenViewer.kt`
- [ ] **Step 1:** Root → `MaxBackground + PatternCheckerOverlay(0.1)`
- [ ] **Step 2:** Single-item controls → `MaxCard` page indicator bottom-center
- [ ] **Step 3:** Gallery pager → `MaxCardChatty` per page, `FloatReverse`
- [ ] **Step 4:** Keep/Delete/Restore CTAs → `MaxButtonPulsing(accentIndex=2/0)` with gradient bg
- [ ] **Step 4:** Metadata → `GradientText` with `TextShadowStack` (3 clash layers)
- [ ] **Step 5:** Tutorial overlay → `MassiveHero` + `FloatingShape`
- [ ] **Step 6:** Compile + commit: `feat(fullscreen): Max rewrite — checker bg, gradient text, pulsing CTAs`

#### Task 3.6: OnboardingScreen.kt — Max Polish
**Files:** Modify `ui/OnboardingScreen.kt`
- [ ] **Step 1:** Background → `MassiveHero` with "KEEP" / "IX" `BackdropText`
- [ ] **Step 2:** Step cards → `MaxCardChatty` with `Float`/`FloatReverse`
- [ ] **Step 3:** CTA button → `MaxButtonPulsing(accentRotation=true)`
- [ ] **Step 4:** Page indicator dots → `FloatingShape.Spark` with `Wiggle`
- [ ] **Step 5:** Compile + commit: `feat(onboarding): Max polish — MassiveHero, chatty steps, pulsing CTA`

#### Task 3.7: PermissionScreen.kt — Max Polish
**Files:** Modify `ui/PermissionScreen.kt`
- [ ] **Step 1:** Background → `MassiveHero` with "🫶" `BackdropText`
- [ ] **Step 2:** Card → `MaxCardChatty`, grant button → `MaxButtonPulsing(accentRotation=true)`
- [ ] **Step 3:** Compile + commit: `feat(permission): Max polish — MassiveHero, pulsing grant`

### Phase 4: Retention Screens (New)

#### Task 4.1: DashboardScreen.kt
**Files:** Create `ui/screens/DashboardScreen.kt`
- [ ] **Step 1:** `MaxBackground + PatternDots + PatternStripes + PatternMesh + 4 FloatingShapes`
- [ ] **Step 2:** Top: `StreakChip(dayCount)` with pulseGlow
- [ ] **Step 3:** Hero row: 3 `DashboardTile` (photos swiped, bytes freed, minutes) — `asymmetricOffset(index)`
- [ ] **Step 4:** Mid: `NextMilestoneBar` with pulseGlow
- [ ] **Step 5:** Bottom: Primary `MaxButtonPulsing("START SWIPING")` + secondary outline `MaxButton("VIEW VAULT")` (dashed border)
- [ ] **Step 6:** Compile + commit: `feat(dashboard): new screen — streak, tiles, milestone bar, asymmetric layout`

#### Task 4.2: VaultScreen.kt
**Files:** Create `ui/screens/VaultScreen.kt`
- [ ] **Step 1:** `MaxBackground + PatternMesh + PatternDots + 6 FloatingShapes`
- [ ] **Step 2:** `LazyColumn` of `MilestoneUnlockCard` — unlocked first (by `unlockedAtEpochMs` desc), then locked (by progress %)
- [ ] **Step 3:** Each card `.asymmetricOffset(index)` (rotate ±1°, translate-y-8)
- [ ] **Step 4:** Tap unlocked → `Wiggle`; tap locked → show progress toast
- [ ] **Step 5:** Compile + commit: `feat(vault): new screen — milestone cards, asymmetric broken grid`

#### Task 4.3: YearInReviewScreen.kt
**Files:** Create `ui/screens/YearInReviewScreen.kt`
- [ ] **Step 1:** `HorizontalPager` (5-7 pages), 250ms bouncy easing
- [ ] **Step 2:** Page 1 (Cover): `MassiveHero` with "YOUR 202X" `GradientText`, 8 FloatingShapes, pulse ring
- [ ] **Step 3:** Page 2 (Total Swipes): `GradientText` counter animate 0→value 1.5s, triple-stack text shadow
- [ ] **Step 4:** Page 3 (Bytes Freed): Hero number + `MonthHeatmap`
- [ ] **Step 5:** Page 4 (Streaks): `StreakLadder` with glowing streak row
- [ ] **Step 6:** Page 5 (Monthly Rhythm): `MonthHeatmap` full calendar
- [ ] **Step 7:** Page 6-7 (Closing): Full chaos — 4 patterns, 10+ FloatingShapes, animated GradientText, `MaxButtonPulsing` dismiss
- [ ] **Step 8:** Skip button always visible top-right
- [ ] **Step 9:** Compile + commit: `feat(yir): new screen — 7-page story, animated counters, heatmap, ladder`

#### Task 4.4: MainActivity.kt — Routing + ViewModel Hooks
**Files:** Modify `MainActivity.kt`
- [ ] **Step 1:** Add `YearInReviewScreen` → `DashboardScreen` → existing screens nav graph
- [ ] **Step 2:** `LaunchedEffect` on start: check `app_meta["YEAR_IN_REVIEW_PENDING_FOR_YEAR"]` → route to YIR if pending
- [ ] **Step 3:** Collect `MilestoneUnlocked` SharedFlow from ViewModel → show `MilestoneUnlockDialog`
- [ ] **Step 4:** Compile + commit: `feat(main): YIR→Dashboard routing + milestone unlock dialog`

### Phase 5: Polish & QA

#### Task 5.1: Reduced Motion Audit
**Files:** All new components/screens
- [ ] **Step 1:** Verify every `Modifier.float/pulseGlow/wiggle/...` wraps `if (LocalReducedMotion.current) return this`
- [ ] **Step 2:** Test with Settings → Accessibility → Remove animations ON
- [ ] **Step 3:** Commit: `fix: reduced-motion compliance across all Max components`

#### Task 5.2: Font Loading Verification
**Files:** `Type.kt` + `res/font/`
- [ ] **Step 1:** Cold launch → verify Outfit/DM Sans/Bungee render (no SansSerif fallback)
- [ ] **Step 2:** Commit: `fix: font loading verified`

#### Task 5.3: Real-Device 60fps QA
**Device:** Pixel 6a / mid-range Android 13+
- [ ] **Step 1:** Swipe 50 cards — no frame drops (Profile GPU Rendering bars < 16ms)
- [ ] **Step 2:** Dashboard → Vault → YIR transitions smooth
- [ ] **Step 3:** Bin grid scroll 100 items — no jank
- [ ] **Step 4:** Fullscreen pager 20 items pinch/zoom — 60fps
- [ ] **Step 5:** Commit: `qa: 60fps verified on mid-range device`

#### Task 5.4: Final Integration Test
- [ ] **Step 1:** `./gradlew test` — all unit tests pass
- [ ] **Step 2:** `./gradlew connectedAndroidTest` — instrumented tests pass
- [ ] **Step 3:** Build signed release AAB → `apksigner verify`
- [ ] **Step 4:** Commit: `chore: final integration — all tests pass, release AAB verified`

---

## Execution Order Summary

| Phase | Tasks | Est. Time |
|-------|-------|-----------|
| 0 | Font assets | 30 min |
| 1 | Theme primitives (6 tasks) | 2-3 hrs |
| 2 | Extended components (12 tasks) | 4-5 hrs |
| 3 | Screen rewrites (7 tasks) | 6-8 hrs |
| 4 | Retention screens (4 tasks) | 4-5 hrs |
| 5 | Polish/QA (4 tasks) | 2-3 hrs |

**Total: ~20-25 hours of implementation work**

---

## Self-Review Checklist (Run Before Handoff)

- [ ] Every new component has `@Preview`
- [ ] Every animated modifier checks `LocalReducedMotion.current`
- [ ] Accent rotation uses `AccentsAt(index % 5)` consistently
- [ ] Border widths use `BorderWidths.Subtle/Standard/Heavy/Hero` constants
- [ ] No inline `drawBehind` with hardcoded colors — all via `AccentsAt`/`ClashAccent`
- [ ] Asymmetric offset applied via `Modifier.asymmetricOffset(index)` helper
- [ ] Fonts load from `res/font/` (no SansSerif in release)
- [ ] All screens wrap in `MaxBackground { PatternDots + PatternStripes + PatternMesh }`
- [ ] Commit messages follow `feat(scope): description` convention
- [ ] `./gradlew test connectedAndroidTest` passes

---

**Plan complete. Saved to:** `docs/superpowers/plans/2026-07-12-keepix-max-ui-redesign.md`

**Next:** Invoke `superpowers:subagent-driven-development` to execute tasks sequentially with per-task review.