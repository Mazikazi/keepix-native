# App Icon Export Instructions

## Target
- **512×512 PNG** — required for Play Console "Hi-res icon"
- **1024×1024 PNG** — optional future-proofing

## Quick Export (Inkscape CLI — recommended, no GUI needed)

```bash
# Install Inkscape if missing: https://inkscape.org/release/
inkscape docs/store-listing/icon-design.svg \
  --export-type=png \
  --export-filename=docs/store-listing/ic_launcher_512.png \
  --export-width=512 \
  --export-height=512

inkscape docs/store-listing/icon-design.svg \
  --export-type=png \
  --export-filename=docs/store-listing/ic_launcher_1024.png \
  --export-width=1024 \
  --export-height=1024
```

## Manual Export (Figma / Illustrator / Affinity / Photopea)
1. Open `icon-design.svg`
2. Export artboard at 512×512 PNG
3. Save as `docs/store-listing/ic_launcher_512.png`
4. (Optional) Export at 1024×1024 → `ic_launcher_1024.png`

## Verify
```bash
file docs/store-listing/ic_launcher_512.png
# Expect: PNG image data, 512 x 512, 8-bit/color RGBA
```

## Upload to Play Console
- Play Console → Store listing → App icon → Upload `ic_launcher_512.png`