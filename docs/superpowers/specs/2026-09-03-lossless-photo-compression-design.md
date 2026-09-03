# Keepix Lossless Photo Compression — Design

- **Status:** Approved
- **Date:** 2026-09-03
- **Platform:** Android (Kotlin, Jetpack Compose)

---

## 1. Problem

Keepix reclaims space by deleting photos. Some photos the user wants to keep are
nonetheless larger than they need to be — modern phone JPEGs carry redundant
payloads alongside the actual image.

Reclaim that space **without altering a single pixel**.

## 2. The constraint that shapes everything

Truly lossless is not a preference here, it is the requirement. That rules out
the obvious approach: `Bitmap.compress()` decodes to a bitmap and re-encodes,
which discards the original DCT coefficients. Even at quality 100 the output is a
different — often larger — image, and re-compressing an already-compressed photo
causes cumulative generational loss.

Genuine lossless JPEG optimization (Huffman re-optimization, progressive
conversion) operates on DCT coefficients directly, which the Android platform
does not expose. It would require `jpegtran`-class native code.

**This design takes the no-native-code path:** strip redundant *container*
payloads without touching entropy-coded scan data. The output is bit-identical in
pixels because the pixel bytes are copied verbatim.

## 3. Goals

- Reclaim space with provably zero pixel change.
- Never corrupt a photo. This is the highest-value property in the design.
- Preserve everything the user or the OS depends on: capture date, orientation,
  GPS, color rendering.
- No new dependencies. No native code, no NDK, no APK growth.
- Answer the "is NDK worth it later?" question with measured data.

## 4. Non-goals

- Video compression. Separate subsystem, separate spec.
- Lossy or visually-lossless re-encoding. Explicitly rejected.
- Non-JPEG containers (PNG, HEIC, WebP). Skipped entirely.
- Recompressing photos the app did not originally leave alone.

## 5. What is strippable, and what absolutely is not

| Segment | Action | Reasoning |
|---|---|---|
| MPF secondary image (APP2, `MPF\0`), when confirmed to be a discardable duplicate | **Strip** | Often a dual-camera depth/secondary capture, hundreds of KB. But an MPF secondary is **not always a duplicate**: Google's Ultra HDR (Android 14+, the default JPEG output on Pixel 8+/Galaxy S24+ and much of the current fleet) and Apple's "Most Compatible" HDR JPEG store the **HDR gain map** the same way, and a Google/Samsung motion photo's video half can be appended after the primary EOI the same way too, sometimes alongside an MPF index and sometimes not. Both are part of the displayed result, not a duplicate of it. See §5.1. |
| EXIF thumbnail (APP1 → IFD1) | **Strip — phase 2** | Redundant preview, 10–20 KB, regenerable by any viewer. Requires TIFF-internal surgery; see §7. |
| **ICC profile (APP2, `ICC_PROFILE\0`)** | **KEEP** | Removing it leaves pixels identical but renders a Display-P3 photo oversaturated in any color-managed viewer. Visibly wrong output — exactly the failure this design exists to avoid. |
| XMP (APP1, Adobe ns) | **KEEP** | May carry edits, ratings, crop instructions. |
| EXIF IFD0 / ExifIFD / GPS | **KEEP** | Date, orientation, GPS. Keepix's own queue sorts on `DATE_ADDED` and the viewer honours orientation. |
| JFIF (APP0) | **KEEP** | Tiny; some decoders expect it. |
| Any segment the parser does not fully understand | **KEEP** | Unknown means unknown. Never drop on a guess. |

### 5.1 An MPF secondary is not always disposable

An earlier version of this table asserted the MPF secondary is "not the
displayed image." That is false, and dangerously so: it is true of a
dual-camera depth/secondary capture, but not of an **Ultra HDR gain map** or a
**motion photo's video half**, both of which are commonly stored as the same
"second JPEG (or MP4) concatenated after the primary EOI" shape an MPF
secondary uses — sometimes described by an MPF index, sometimes not.

- **Ultra HDR** (Android 14+; the default camera JPEG output on Pixel 8+,
  Galaxy S24+, and much of the current Android fleet) stores its HDR gain map
  as an MPF secondary: an SDR primary JPEG, an APP2/MPF index with two MP
  entries, then the gain-map JPEG appended after the primary's EOI. Apple's
  "Most Compatible" HDR JPEG output uses the same mechanism. Stripping it
  leaves every base-image pixel bit-identical — so every check this design
  performs would pass — while the photo silently loses HDR rendering in any
  Ultra-HDR-aware viewer. Irreversible once the backup is released.
- **Motion photos** (Google's Motion Photos, Samsung's equivalent) append a
  short MP4 after the primary EOI, described by XMP (`GCamera:MicroVideo` /
  `MicroVideoOffset` for the legacy format, `MotionPhoto` and the GContainer
  directory for the current one) — sometimes with **no MPF index at all**.
  Truncating at the primary EOI removes the video; the still photo survives
  untouched, which makes the loss easy to miss.

Both failures are the same shape ICC removal would be: pixels identical,
output visibly (or functionally) wrong. The MPF container format alone cannot
distinguish a discardable duplicate from either of these, so this design does
not rely on it alone. Before any MPF-based strip, both callers —
[`PhotoCompressionAnalyzer`](../../../app/src/main/java/com/sese/keepix/utils/PhotoCompressionAnalyzer.kt)
(so an ineligible file is never counted or offered) and
[`PhotoCompressor`](../../../app/src/main/java/com/sese/keepix/utils/PhotoCompressor.kt)
(so one is never rewritten even if something else offered it) — consult
[`AuxiliaryPayloadDetector`](../../../app/src/main/java/com/sese/keepix/utils/jpeg/AuxiliaryPayloadDetector.kt):
a fail-closed, header-only check that scans APP1 payload bytes for the marker
strings above (plus the GContainer `Item:Semantic` property) and treats any
hit as "not a discardable duplicate, do not strip." A plain dual-camera MPF
file carrying none of these markers remains eligible. Separately,
[`JpegRewriter.planStrip`](../../../app/src/main/java/com/sese/keepix/utils/jpeg/JpegRewriter.kt)
itself now requires an MPF segment to be present before it will offer any
saving at all — trailing bytes alone, with no MPF index, can never justify a
truncation — so the destructive rewrite path does not depend on either caller
remembering to filter first.

## 6. Architecture

Three phases. Each is independently useful and independently shippable.

```
Analyse  →  Rewrite  →  Commit
(read)      (memory)     (write, confirmed)
```

### 6.1 Analyse

Parse the JPEG marker chain and total what is strippable. **Writes nothing, and
needs no new permission** — it runs on the existing read access.

Surfaces as a "Reclaimable space" figure so the user sees the payoff before
granting anything. This phase also answers whether the NDK route is worth
pursuing later: if segment stripping reclaims very little on this user's actual
library, that is data, not speculation.

### 6.2 Rewrite

Reconstruct the file in memory:

1. Copy `SOI`.
2. Walk the marker chain. Copy each segment verbatim unless it is on the strip list.
3. On reaching `SOS`, copy the entropy-coded scan data **byte for byte**, unexamined.
4. Terminate at the primary image's `EOI`.

Step 4 is what removes MPF secondary images: they are stored as a **second
concatenated JPEG after the primary `EOI`**, so truncating there drops them.
The APP2/MPF index segment is dropped in step 2 because it would otherwise point
at bytes that no longer exist.

The scan data is never decoded. That is what makes the operation lossless by
construction rather than by careful parameter choice.

### 6.3 Commit — and the journal

Writing in place is the most dangerous thing this app would ever do. A failed
write mid-flight destroys an irreplaceable photo.

**Confirmation batches; writes serialise.** `MediaStore.createWriteRequest` takes a
collection, so the user confirms the whole set **once** — a dialog per photo would
be unusable. The writes that follow are then performed strictly one file at a
time. A batch confirmation is fine; a batch *write* is not.

**Copy-first journal**, mirroring the proven mark-then-confirm engine:

1. `MediaStore.createWriteRequest(uris)` → **one** user confirmation for the batch.
2. Then, per file, in sequence:
   a. Copy the original into app-private storage; record a journal row.
   b. Write the rewritten bytes.
   c. Re-read and verify (§8). On success, delete the backup and clear the row.
   d. On failure, restore from the backup and stop the run.
3. **On launch, any orphaned journal row means a write did not complete** — restore
   the original before doing anything else.

The backup is taken inside the per-file loop, not up front: backing up the entire
batch first could exhaust storage on a large run, and step 3 recovers correctly
either way since at most one file is ever mid-write.

## 7. Phasing, by risk

The two strip targets are not equally safe, and are deliberately not shipped together.

**Phase A — MPF only.** Pure segment-level work: find the marker, drop it,
truncate at `EOI`. No structure inside a segment is parsed or rewritten. This is
also the larger win, frequently hundreds of KB on dual-camera phones.

**Phase B — EXIF thumbnail.** IFD1 lives inside the *same* APP1 segment as IFD0,
ExifIFD and GPS. Removing it means parsing TIFF structure, deleting one IFD, and
rewriting the offset chain — every remaining pointer must be corrected. A mistake
corrupts the metadata the app itself depends on, for a 10–20 KB return.

Phase A first. Phase B only if Phase A's measured savings justify the risk.

## 8. Verification before commit

A rewrite is committed only if **all** hold:

- Output parses as a structurally valid JPEG (`SOI` … `EOI`, coherent marker chain).
- Decoded dimensions match the original exactly.
- EXIF date, orientation and GPS read back identically.
- Saving clears a floor of **>5% of the original file size AND >20 KB absolute**.
  Below either, keep the original — no churn, no risk, for a rounding error.

Any failure: discard the rewrite, keep the original, and record why.

## 9. Error handling

| Scenario | Behaviour |
|---|---|
| File is not a JPEG | Skipped during analysis; never opened for write |
| Marker chain unparseable / truncated | Skipped, counted as "not eligible" |
| Unknown segment encountered | Kept, file still eligible |
| Write confirmation cancelled | Nothing written, and no backup exists yet — confirmation precedes the first backup (§6.3). No re-prompt this session. |
| Write fails mid-flight | Restore from backup; surface via the existing `_error` channel |
| Process dies mid-write | Orphaned journal row detected on next launch; original restored |
| Verification fails after write | Restore from backup; mark the file ineligible so it is not retried |
| Storage full during backup | Abort before any write; the file is never touched |

## 10. Privacy

`PRIVACY.md` and `ui/PrivacyPolicyScreen.kt` both currently state that no media
file is copied or modified. That ceases to be true, and this is a **larger claim
change than the favorite feature's** — favoriting flips a MediaStore metadata
flag; this rewrites the user's actual files.

Both must be updated in lockstep, the screen as source of truth, and must say
plainly: Keepix rewrites the photo to remove redundant embedded data, with the
user's confirmation, without altering the image; a copy is kept until the result
is verified.

## 11. Testing

**JVM (the bulk — this is parser work, which is exactly what unit tests are for):**
- Marker-chain parsing across real-world shapes: EXIF-only, EXIF+ICC, EXIF+MPF,
  progressive, restart markers, `0xFF00` byte stuffing in scan data.
- Strip correctness: MPF removed, ICC/XMP/EXIF retained byte-for-byte.
- **Scan data is byte-identical** before and after — the core lossless claim,
  asserted directly.
- Truncated / malformed / non-JPEG input is skipped, never rewritten.
- The >5% and >20 KB floor is enforced.
- Journal state machine: write → verify → clear; write → fail → restore;
  orphaned row on launch → restore.

**Instrumented:**
- Round-trip on a real file: rewrite, verify EXIF and dimensions survive.
- Orphan recovery after a simulated interrupted write.

## 12. Files

| File | Responsibility |
|---|---|
| `utils/JpegSegments.kt` | **new** — marker-chain parse/rewrite. Pure, no Android deps, fully unit-testable. |
| `utils/PhotoCompressionAnalyzer.kt` | **new** — measure reclaimable bytes |
| `db/CompressionJournalEntity.kt` + Dao | **new** — the crash-safety journal |
| `db/AppDatabase.kt` | v6 + explicit migration |
| `utils/MediaWriteHandler.kt` | **new** — `createWriteRequest` wrapper, mirroring `MediaFavoriteHandler` |
| `ui/KeepixViewModel.kt` | analysis + commit state machine |
| `MainActivity.kt` | write launcher, behind the existing shared dialog gate |
| `ui/SettingsScreen.kt` | "Reclaimable space" entry point |
| `PRIVACY.md`, `ui/PrivacyPolicyScreen.kt` | rewritten claim, in lockstep |

## 13. Verification

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"
./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug
```

On a device:

1. Run analysis on a real library — does the reported figure look plausible?
2. Compress one photo; open it in Google Photos and a desktop viewer. Identical appearance, date and orientation intact.
3. Compare pixel data of original and result — must be byte-identical after decode.
4. Cancel the write confirmation → nothing changed, no backup left behind.
5. Kill the app mid-write → relaunch → original restored, no corruption.
6. A photo with no MPF and no thumbnail → correctly reported as nothing to reclaim, never rewritten.
