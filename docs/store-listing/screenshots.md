# Keepix Store Screenshots — 5-Minute Capture Guide

## Prerequisites
- Android emulator (Pixel 7 Pro API 35 recommended) or physical device
- Debug build installed: `./gradlew installDebug`

## Three Required Screenshots

| Filename | Scene | How to Capture |
|---|---|---|
| `swipe.png` | Mid-swipe card with KEEP/DELETE chrome visible | 1. Launch app → grant permission → complete onboarding<br>2. Swipe a card ~40% right (green KEEP badge shows)<br>3. **Hold** + `adb exec-out screencap -p > docs/store-listing/swipe.png` |
| `bin.png` | Recycle Bin with 3–5 items + countdown badges | 1. From swipe screen, tap bin icon (top-right)<br>2. If bin empty, swipe-left 5 items first<br>3. `adb exec-out screencap -p > docs/store-listing/bin.png` |
| `settings-or-empty.png` | Settings screen with retention slider **OR** Empty Bin state | **Option A (Settings):** Tap settings gear → `screencap`<br>**Option B (Empty Bin):** Empty bin → `screencap` |

## One-Liner Capture (PowerShell / Bash)

```bash
# From repo root, after installing debug build
adb exec-out screencap -p > docs/store-listing/swipe.png
adb exec-out screencap -p > docs/store-listing/bin.png
adb exec-out screencap -p > docs/store-listing/settings-or-empty.png
```

## Verify
```bash
file docs/store-listing/*.png
# All three should show ~1080x1920 or similar phone aspect ratio
```

## Upload to Play Console
- Play Console → Store listing → Phone screenshots → Upload all three
- No need for tablet / Wear / TV screenshots (app is phone-only)