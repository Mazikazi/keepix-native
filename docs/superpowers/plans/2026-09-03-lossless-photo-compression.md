# Lossless Photo Compression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reclaim storage from kept JPEGs by stripping the MPF secondary image and its index segment, copying every pixel byte verbatim so the result is provably identical to the original.

**Architecture:** A pure, Android-free JPEG layer (`utils/jpeg/`) parses the marker chain, plans the strip, and rebuilds the file by copying byte spans — entropy-coded scan data is never decoded, which is what makes the operation lossless by construction rather than by careful parameter choice. An Android layer wraps that in a copy-first journal: back up, write, read back and compare, then drop the backup. An orphaned journal row on launch means a write did not finish, and the original is restored. Writes go through `MediaStore.createWriteRequest`, which batches one confirmation for the whole run while the writes themselves serialise one file at a time.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Coroutines, JUnit4 + MockK. No new dependencies.

## Global Constraints

Every task's requirements implicitly include this section.

- **minSdk 30**, compileSdk 35, targetSdk 35. `MediaStore.createWriteRequest` is API 30+.
- **No new dependencies.** No NDK, no native code, no `jpegtran`. If a task seems to need one, stop and report rather than adding it.
- **No `android.permission.INTERNET`**, and no code path may fetch a remote URL. The privacy policy states the binary contains no INTERNET permission; that must stay true.
- **Never decode and re-encode pixels.** `Bitmap.compress()` is banned in this feature — it discards the original DCT coefficients and is generationally lossy even at quality 100.
- **Room migrations stay explicit.** `fallbackToDestructiveMigration()` must not appear anywhere, including tests. It silently wiped user data once and was removed deliberately.
- **Never destroy an original without a verified replacement.** A backup exists on disk from before the first byte is written until after the read-back comparison passes.
- **Design language is `glassmorphism`.** Use the existing `GlassCard` / `GlassButton` components and the existing theme names (`TextPrimary`, `TextSecondary`, `TextMuted`, `AccentPurple`, `KeepGreen`, `DeleteRed`). Do not introduce `maxBox`, `AccentsAt`, `MaxCard`, or `MaxBackground` — those come from unbuilt design docs and do not compile.
- **`PRIVACY.md` and `ui/PrivacyPolicyScreen.kt` change in lockstep**, with the screen as source of truth. A stale claim on the screen is a false statement shown to users.
- **Git hygiene:** another session has uncommitted work in this worktree. Stage only the exact paths your task touches. Never `git add -A`, `git add .`, or `git commit -a`.
- **Build environment:** every Gradle invocation must first `export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"` (JDK 21). AGP 8.7.3 rejects the system JDK 22.
- **Commit trailer:** end every commit message with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## Scope: Phase A only

The spec (`docs/superpowers/specs/2026-09-03-lossless-photo-compression-design.md` §7) phases the work by risk. **This plan implements Phase A — MPF only.** Phase B (EXIF thumbnail removal) requires rewriting the TIFF offset chain inside the same APP1 segment that carries date, orientation and GPS, and is deliberately not in this plan. Do not implement it.

## Two decisions this plan makes that the spec left open

The spec did not pin these down. Both are load-bearing, so they are stated here rather than left to each implementer.

**1. The analysed set is `kept_items`, and analysis is header-only.**
Those are the photos the user has explicitly decided to keep — items still in the swipe queue may yet be deleted, and items in the bin will be. Scanning whole files across a library is not affordable (2,000 photos x 5 MB = 10 GB of reads), so the analyser reads only the header region of each file (64 KB at a time, capped at 1 MB) and derives the primary image's length from the **MPF index**, which states it directly. That is an *estimate*, and the UI says so.

**2. The rewriter does not trust that estimate.** It finds the primary `EOI` by walking the entropy-coded data itself, which is self-validating. The MPF index is then used only as a cross-check (Task 5). Two independent methods agreeing before a destructive write is the point.

## Design note: verification is byte-comparison, not `ExifInterface`

Spec §8 asks that decoded dimensions match and that EXIF date/orientation/GPS read back identically. This plan satisfies those requirements more strongly and more cheaply.

Because the rewriter copies every kept segment **verbatim**, EXIF equality is guaranteed by construction — the APP1 bytes in the output are the same bytes as in the input. Dimensions live in the SOF segment, also copied verbatim. So instead of re-reading tags with `ExifInterface`, verification re-reads the file from MediaStore and asserts the bytes on disk are **exactly** the bytes we intended to write, then re-parses them for structural validity.

This subsumes both spec requirements and additionally catches a **partial or truncated write** — which an `ExifInterface` round-trip would not reliably detect, because the EXIF segment sits at the front of the file and survives a truncation that destroys the image.

## Why EXIF offsets do not break when a segment is removed

An implementer may reasonably worry that dropping the APP2/MPF segment shifts every later segment earlier and invalidates internal pointers. It does not:

- **EXIF (APP1)** TIFF offsets are relative to the TIFF header *inside that segment*, not to the file. Relocating the segment is safe.
- **XMP (APP1, Adobe ns)** is XML. No offsets.
- **ICC (APP2, `ICC_PROFILE`)** is chunked with sequence numbers. No file offsets.
- **MPF (APP2, `MPF`)** *is* file-relative — which is exactly why it is dropped rather than kept.

Nothing that survives the strip carries a file-absolute offset.

## File Structure

**New — pure Kotlin, no Android imports, fully JVM-testable:**

| File | Responsibility |
|---|---|
| `utils/jpeg/JpegStructure.kt` | Marker constants and the parse result types. Data only. |
| `utils/jpeg/JpegParser.kt` | Walk the marker chain. `parseHeader` (prefix, stops at SOS) and `parseFull` (whole file, finds the primary EOI). |
| `utils/jpeg/MpfIndex.kt` | Read-only MPF index reader: the primary image's declared byte length. |
| `utils/jpeg/JpegRewriter.kt` | The §5 strip policy, the savings floor, and the byte-span rebuild. |

**New — Android:**

| File | Responsibility |
|---|---|
| `db/CompressionJournalEntity.kt` | One row per in-flight rewrite. |
| `db/CompressionJournalDao.kt` | Insert / list / delete. |
| `utils/MediaFileIo.kt` | The I/O seam: interface + `ContentResolver` implementation. |
| `utils/PhotoCompressor.kt` | Per-file engine: back up, write, verify, commit or restore. Plus `recover()`. |
| `utils/PhotoCompressionAnalyzer.kt` | Header-only estimate over the kept set. |
| `utils/MediaWriteHandler.kt` | `createWriteRequest` wrapper, mirroring `MediaFavoriteHandler`. |

**Modified:**

| File | Change |
|---|---|
| `db/AppDatabase.kt` | v6, `MIGRATION_5_6`, `compressionJournalDao()` |
| `ui/KeepixViewModel.kt` | Scan / request / run state machine, recovery on init |
| `MainActivity.kt` | Write launcher behind the existing `systemDialogInFlight` gate |
| `ui/SettingsScreen.kt` | STORAGE section |
| `PRIVACY.md`, `ui/PrivacyPolicyScreen.kt` | Rewritten claim, in lockstep |

The pure layer is split from the Android layer because the parser and rewriter are where a bug corrupts a photo, and they are the only part that can be exercised exhaustively on the host JVM. Everything above them is orchestration.

---

### Task 1: JPEG marker-chain parser

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/jpeg/JpegStructure.kt`
- Create: `app/src/main/java/com/sese/keepix/utils/jpeg/JpegParser.kt`
- Create: `app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt`
- Test: `app/src/test/java/com/sese/keepix/utils/jpeg/JpegParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `JpegSegment(marker: Int, offset: Int, length: Int, identifier: String?)` with `payloadOffset: Int` and `payloadLength: Int`
  - `JpegHeader(segments: List<JpegSegment>, scanStartOffset: Int)`
  - `JpegStructure(segments: List<JpegSegment>, primaryEndOffset: Int, totalLength: Int)` with `trailingBytes: Int`
  - `sealed interface HeaderResult { Ok(header), NeedMoreBytes, Malformed }`
  - `JpegParser.parseHeader(bytes: ByteArray, available: Int = bytes.size): HeaderResult`
  - `JpegParser.parseFull(bytes: ByteArray): JpegStructure?`
  - `JpegMarkers.SOI/EOI/SOS/APP0/APP1/APP2/APP15/RST0/RST7`
  - Test helper `JpegFixtures.segment / app / soi / eoi / sos`

**Background the implementer needs:** a JPEG is `FFD8` (SOI), then a chain of marker segments, then `FFDA` (SOS) followed by entropy-coded scan data, then `FFD9` (EOI). Each non-standalone marker segment is `FF <marker> <2-byte big-endian length including those 2 length bytes> <payload>`. Inside scan data, a literal `FF` byte is escaped as `FF 00`; `FFD0`–`FFD7` are restart markers and are also part of the scan. Any other `FF xx` ends the scan. Progressive JPEGs have several SOS segments, so the walker must return to marker-chain mode after each scan rather than assuming one scan.

- [ ] **Step 1: Write the fixture builder**

This is test-only support, written first because every later test in Tasks 1–3 depends on it. Create `app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

/**
 * Assembles synthetic JPEGs byte-by-byte so parser and rewriter tests can state
 * exactly what structure they are exercising. Deliberately hand-built rather
 * than loaded from binary test resources: a test that fails here should point at
 * a named shape ("progressive with two scans"), not at an opaque blob.
 */
object JpegFixtures {

    fun soi(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    fun eoi(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** `FF <marker> <len hi> <len lo> <payload>`, where len covers itself + payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        require(len <= 0xFFFF) { "segment payload too large for a 16-bit length" }
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(),
            (len shr 8).toByte(), (len and 0xFF).toByte()
        ) + payload
    }

    /** An APPn segment carrying a NUL-terminated ASCII identifier. */
    fun app(marker: Int, identifier: String, body: ByteArray = ByteArray(0)): ByteArray =
        segment(marker, identifier.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + body)

    /** A minimal SOF0 so fixtures look like real images. */
    fun sof0(width: Int = 8, height: Int = 8): ByteArray = segment(
        0xC0,
        byteArrayOf(
            8,
            (height shr 8).toByte(), (height and 0xFF).toByte(),
            (width shr 8).toByte(), (width and 0xFF).toByte(),
            1, 1, 0x11, 0
        )
    )

    /** A SOS segment header followed by raw entropy bytes. */
    fun sos(scan: ByteArray): ByteArray =
        segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + scan

    /** Entropy data containing a stuffed FF, a restart marker, and plain bytes. */
    fun trickyScan(): ByteArray = byteArrayOf(
        0x12, 0x34,
        0xFF.toByte(), 0x00,             // stuffed FF -- must NOT end the scan
        0x56,
        0xFF.toByte(), 0xD0.toByte(),    // RST0 -- must NOT end the scan
        0x78, 0x9A.toByte()
    )

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }
}
```

- [ ] **Step 2: Write the failing parser tests**

Create `app/src/test/java/com/sese/keepix/utils/jpeg/JpegParserTest.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegFixtures.trickyScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegParserTest {

    @Test
    fun parseFull_baselineFile_findsEverySegmentAndTheEoi() {
        val bytes = concat(
            soi(),
            app(JpegMarkers.APP0, "JFIF", ByteArray(9)),
            app(JpegMarkers.APP1, "Exif", ByteArray(20)),
            sof0(),
            sos(trickyScan()),
            eoi()
        )

        val s = JpegParser.parseFull(bytes)!!

        assertEquals(
            listOf(JpegMarkers.APP0, JpegMarkers.APP1, 0xC0, JpegMarkers.SOS),
            s.segments.map { it.marker }
        )
        assertEquals(listOf("JFIF", "Exif", null, null), s.segments.map { it.identifier })
        assertEquals("primary must end at the file end", bytes.size, s.primaryEndOffset)
        assertEquals(0, s.trailingBytes)
    }

    @Test
    fun parseFull_stuffedFfAndRestartMarkers_doNotTerminateTheScan() {
        // trickyScan() contains FF 00 and FF D0. A naive "first FF ends the scan"
        // walker stops at the stuffed byte and reports a truncated file.
        val bytes = concat(soi(), sof0(), sos(trickyScan()), eoi())
        val s = JpegParser.parseFull(bytes)!!
        assertEquals(bytes.size, s.primaryEndOffset)
    }

    @Test
    fun parseFull_progressiveWithTwoScans_walksBackIntoTheMarkerChain() {
        val bytes = concat(
            soi(), sof0(),
            sos(byteArrayOf(0x11, 0x22)),
            JpegFixtures.segment(0xC4, ByteArray(6)),   // DHT between scans
            sos(byteArrayOf(0x33, 0x44)),
            eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        assertEquals(
            listOf(0xC0, JpegMarkers.SOS, 0xC4, JpegMarkers.SOS),
            s.segments.map { it.marker }
        )
        assertEquals(bytes.size, s.primaryEndOffset)
    }

    @Test
    fun parseFull_mpfSecondaryImage_isReportedAsTrailingBytes() {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(byteArrayOf(0x01, 0x02)), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(byteArrayOf(0x03)), eoi())
        val bytes = concat(primary, secondary)

        val s = JpegParser.parseFull(bytes)!!

        assertEquals(primary.size, s.primaryEndOffset)
        assertEquals(secondary.size, s.trailingBytes)
        assertTrue(s.segments.any { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" })
    }

    @Test
    fun parseFull_rejectsNonJpegAndTruncatedInput() {
        assertNull(JpegParser.parseFull(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(JpegParser.parseFull(ByteArray(0)))
        // SOI + a segment claiming more payload than the file holds.
        assertNull(
            JpegParser.parseFull(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte()))
        )
        // Well-formed prefix but no EOI anywhere.
        assertNull(JpegParser.parseFull(concat(soi(), sof0(), sos(byteArrayOf(1, 2, 3)))))
    }

    @Test
    fun parseHeader_stopsAtSos_andReportsSegmentOffsets() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(10)), sof0(), sos(byteArrayOf(1)), eoi())
        val r = JpegParser.parseHeader(bytes)
        assertTrue(r is HeaderResult.Ok)
        val h = (r as HeaderResult.Ok).header
        assertEquals(listOf(JpegMarkers.APP1, 0xC0), h.segments.map { it.marker })
        assertEquals(2, h.segments[0].offset)
        assertEquals(bytes[h.scanStartOffset + 1].toInt() and 0xFF, JpegMarkers.SOS)
    }

    @Test
    fun parseHeader_prefixTooShort_asksForMoreBytesRatherThanFailing() {
        // This distinction is what lets the analyser read 64 KB at a time.
        val full = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(500)), sof0(), sos(byteArrayOf(1)), eoi())
        assertTrue(JpegParser.parseHeader(full, available = 20) is HeaderResult.NeedMoreBytes)
        assertTrue(JpegParser.parseHeader(full) is HeaderResult.Ok)
    }

    @Test
    fun parseHeader_nonJpeg_isMalformedNotNeedMoreBytes() {
        assertTrue(JpegParser.parseHeader(byteArrayOf(0x00, 0x01, 0x02, 0x03)) is HeaderResult.Malformed)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.JpegParserTest"
```

Expected: compilation failure — `Unresolved reference: JpegParser` / `JpegMarkers` / `HeaderResult`.

- [ ] **Step 4: Write the types**

Create `app/src/main/java/com/sese/keepix/utils/jpeg/JpegStructure.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

/**
 * JPEG marker bytes -- the SECOND byte of each `FF xx` pair.
 *
 * Deliberately no Android imports anywhere in this package: everything here is
 * exercised on the host JVM, because this is the layer where a bug corrupts a
 * user's photo.
 */
object JpegMarkers {
    const val TEM = 0x01
    const val RST0 = 0xD0
    const val RST7 = 0xD7
    const val SOI = 0xD8
    const val EOI = 0xD9
    const val SOS = 0xDA
    const val APP0 = 0xE0
    const val APP1 = 0xE1
    const val APP2 = 0xE2
    const val APP15 = 0xEF

    /** Markers that carry no length field and no payload. */
    fun isStandalone(marker: Int): Boolean =
        marker == SOI || marker == EOI || marker == TEM || marker in RST0..RST7

    fun isApp(marker: Int): Boolean = marker in APP0..APP15
}

/**
 * One marker segment.
 *
 * @param marker the second byte of the `FF xx` pair.
 * @param offset index of the leading `0xFF` in the file.
 * @param length total bytes: `FF`, marker, the 2 length bytes, and the payload.
 * @param identifier for APPn segments, the NUL-terminated ASCII tag that names
 *   the payload format (`"Exif"`, `"ICC_PROFILE"`, `"MPF"`, `"JFIF"`). Null for
 *   non-APP segments and for an APP segment whose leading bytes are not a
 *   plausible identifier -- an unrecognised segment is KEPT, so failing to name
 *   one is always safe.
 */
data class JpegSegment(
    val marker: Int,
    val offset: Int,
    val length: Int,
    val identifier: String?
) {
    /** First byte after the 2-byte length field. */
    val payloadOffset: Int get() = offset + 4
    val payloadLength: Int get() = length - 4
}

/** Everything up to (not including) the first SOS. */
data class JpegHeader(
    val segments: List<JpegSegment>,
    /** Index of the `0xFF` of the `FFDA` pair. */
    val scanStartOffset: Int
)

/** A fully-walked file. */
data class JpegStructure(
    val segments: List<JpegSegment>,
    /** Exclusive: the index just past the primary image's `FFD9`. */
    val primaryEndOffset: Int,
    val totalLength: Int
) {
    /**
     * Bytes after the primary EOI. For a dual-camera JPEG this is the MPF
     * secondary image, stored as a whole second JPEG concatenated onto the first.
     */
    val trailingBytes: Int get() = totalLength - primaryEndOffset
}

/**
 * The outcome of parsing a PREFIX of a file. The three-way result is what lets
 * the analyser read 64 KB at a time instead of whole multi-megabyte files:
 * [NeedMoreBytes] means "read more and ask again", which a nullable return could
 * not distinguish from "this is not a JPEG".
 */
sealed interface HeaderResult {
    data class Ok(val header: JpegHeader) : HeaderResult
    data object NeedMoreBytes : HeaderResult
    data object Malformed : HeaderResult
}
```

- [ ] **Step 5: Write the parser**

Create `app/src/main/java/com/sese/keepix/utils/jpeg/JpegParser.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

/**
 * Walks a JPEG's marker chain. Reads only; never modifies and never decodes.
 *
 * The walker has two modes and alternates between them, which is what makes it
 * correct for progressive JPEGs as well as baseline ones: marker-chain mode
 * reads `FF xx <len> <payload>` segments, and after a SOS it switches to
 * entropy mode, which skips opaque scan bytes until a real marker appears.
 */
object JpegParser {

    /** Cap on how far an APPn identifier is searched for its NUL terminator. */
    private const val MAX_IDENTIFIER_LENGTH = 32

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    /**
     * Parses the marker chain of the first [available] bytes of [bytes], stopping
     * at the SOS. Use this when only a prefix of the file has been read.
     */
    fun parseHeader(bytes: ByteArray, available: Int = bytes.size): HeaderResult {
        val n = minOf(available, bytes.size)
        if (n < 2) return HeaderResult.NeedMoreBytes
        if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != JpegMarkers.SOI) return HeaderResult.Malformed

        val segments = mutableListOf<JpegSegment>()
        var p = 2
        while (true) {
            if (p >= n) return HeaderResult.NeedMoreBytes
            if (u8(bytes, p) != 0xFF) return HeaderResult.Malformed

            // A run of FF bytes before a marker is legal fill.
            var q = p
            while (q < n && u8(bytes, q) == 0xFF) q++
            if (q >= n) return HeaderResult.NeedMoreBytes

            val marker = u8(bytes, q)
            if (marker == JpegMarkers.SOS) return HeaderResult.Ok(JpegHeader(segments, q - 1))
            if (marker == JpegMarkers.EOI) return HeaderResult.Malformed  // EOI before any scan
            if (JpegMarkers.isStandalone(marker)) { p = q + 1; continue }

            if (q + 2 >= n) return HeaderResult.NeedMoreBytes
            val declared = u16(bytes, q + 1)
            if (declared < 2) return HeaderResult.Malformed

            val segStart = q - 1
            val segLength = 2 + declared
            if (segStart + segLength > n) {
                // Could be either a truncated read or a corrupt length. Only the
                // full file can tell, so ask for more rather than condemning it.
                return HeaderResult.NeedMoreBytes
            }
            segments += JpegSegment(marker, segStart, segLength, identifierAt(bytes, marker, segStart, segLength))
            p = segStart + segLength
        }
    }

    /**
     * Walks the whole of [bytes] and locates the primary image's EOI. Returns null
     * for anything that is not a structurally coherent JPEG -- a null here means
     * "skip this file", never "rewrite it anyway".
     */
    fun parseFull(bytes: ByteArray): JpegStructure? {
        val n = bytes.size
        if (n < 4) return null
        if (u8(bytes, 0) != 0xFF || u8(bytes, 1) != JpegMarkers.SOI) return null

        val segments = mutableListOf<JpegSegment>()
        var p = 2
        while (true) {
            if (p >= n) return null
            if (u8(bytes, p) != 0xFF) return null

            var q = p
            while (q < n && u8(bytes, q) == 0xFF) q++
            if (q >= n) return null

            val marker = u8(bytes, q)
            if (marker == JpegMarkers.EOI) return JpegStructure(segments, q + 1, n)
            if (JpegMarkers.isStandalone(marker)) { p = q + 1; continue }

            if (q + 2 >= n) return null
            val declared = u16(bytes, q + 1)
            if (declared < 2) return null

            val segStart = q - 1
            val segLength = 2 + declared
            if (segStart + segLength > n) return null
            segments += JpegSegment(marker, segStart, segLength, identifierAt(bytes, marker, segStart, segLength))
            p = segStart + segLength

            if (marker == JpegMarkers.SOS) {
                p = skipEntropyCodedData(bytes, p, n) ?: return null
            }
        }
    }

    /**
     * Advances past entropy-coded scan data and returns the index of the `0xFF`
     * that begins the next real marker.
     *
     * Two byte sequences inside a scan look like markers but are not, and treating
     * either as one truncates the image:
     *  - `FF 00` is a stuffed literal `0xFF` sample byte.
     *  - `FF D0`..`FF D7` are restart markers, which are part of the scan.
     *
     * The scan bytes themselves are never interpreted beyond this -- that is the
     * whole basis of the lossless claim.
     */
    private fun skipEntropyCodedData(bytes: ByteArray, from: Int, n: Int): Int? {
        var i = from
        while (i < n) {
            if (u8(bytes, i) != 0xFF) { i++; continue }
            var j = i
            while (j < n && u8(bytes, j) == 0xFF) j++
            if (j >= n) return null
            val next = u8(bytes, j)
            if (next == 0x00 || next in JpegMarkers.RST0..JpegMarkers.RST7) { i = j + 1; continue }
            return j - 1
        }
        return null
    }

    /**
     * Reads an APPn segment's NUL-terminated ASCII identifier. Returns null unless
     * the payload really does start with printable ASCII followed by a NUL --
     * anything else is left unidentified, and an unidentified segment is kept.
     */
    private fun identifierAt(bytes: ByteArray, marker: Int, segStart: Int, segLength: Int): String? {
        if (!JpegMarkers.isApp(marker)) return null
        val start = segStart + 4
        val limit = minOf(segStart + segLength, start + MAX_IDENTIFIER_LENGTH, bytes.size)
        var i = start
        while (i < limit) {
            val c = u8(bytes, i)
            if (c == 0) {
                if (i == start) return null
                return String(bytes, start, i - start, Charsets.US_ASCII)
            }
            if (c < 0x20 || c > 0x7E) return null
            i++
        }
        return null
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.JpegParserTest"
```

Expected: `BUILD SUCCESSFUL`, 8 tests passing.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/jpeg/JpegStructure.kt app/src/main/java/com/sese/keepix/utils/jpeg/JpegParser.kt app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt app/src/test/java/com/sese/keepix/utils/jpeg/JpegParserTest.kt
```

Then commit with the message body:

```
feat(jpeg): add a pure JPEG marker-chain parser

Walks the marker chain in two alternating modes so progressive files with
several scans parse the same as baseline ones, and treats FF00 stuffing and
RSTn as scan content rather than markers -- mistaking either truncates the
image at the first sample byte that happens to be 0xFF.

No Android imports, so every shape is exercised on the host JVM.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 2: MPF index reader

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/jpeg/MpfIndex.kt`
- Modify: `app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt`
- Test: `app/src/test/java/com/sese/keepix/utils/jpeg/MpfIndexTest.kt`

**Interfaces:**
- Consumes: `JpegSegment`, `JpegFixtures` (Task 1).
- Produces:
  - `MpfIndex.readPrimaryImageSize(bytes: ByteArray, segment: JpegSegment): Long?`
  - `JpegFixtures.mpfPayload(imageSizes: List<Long>, littleEndian: Boolean = false): ByteArray` —
    **Tasks 5 and 6 both use this.** It lives in the shared fixture file rather than
    being re-derived privately in each test class, because three hand-rolled TIFF
    writers that must agree byte-for-byte is three chances to encode the same
    misunderstanding differently.

**Background:** an APP2/MPF payload is `"MPF\u0000"` followed by a TIFF structure: a byte-order mark (`II` little-endian or `MM` big-endian), the 16-bit magic `0x002A`, and a 32-bit offset to the first IFD — **all offsets are relative to the byte-order mark**, not to the file. An IFD is a 16-bit entry count, then that many 12-byte entries (`tag:u16, type:u16, count:u32, valueOrOffset:u32`). Tag `0xB002` is the MP Entry list: `count` bytes of payload at `valueOrOffset`, 16 bytes per image. Within one MP entry, bytes 4–7 are the **individual image size** and bytes 8–11 its offset. Entry 0 is the primary image, whose size is measured from the start of the file.

This reader is **read-only and advisory**. Its output is used for the analyser's estimate and as a cross-check before a write. It is never used to decide where to truncate.

- [ ] **Step 1: Add the shared MPF fixture builder**

Append to `app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt`, inside the object:

```kotlin
    /**
     * An APP2/MPF payload declaring [imageSizes].size images with those byte
     * lengths. Layout after `"MPF "`: the 8-byte TIFF header, an IFD holding
     * one entry (2 + 12 + 4 bytes), then the MP entry array.
     *
     * Shared rather than re-derived per test class: three hand-rolled TIFF
     * writers that must agree byte-for-byte is three chances to encode the same
     * misunderstanding three different ways, and a fixture bug here would look
     * exactly like a parser bug. Tasks 2, 5 and 6 all build on this.
     */
    fun mpfPayload(imageSizes: List<Long>, littleEndian: Boolean = false): ByteArray {
        val tiff = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            if (littleEndian) { tiff.write(v and 0xFF); tiff.write(v shr 8) }
            else { tiff.write(v shr 8); tiff.write(v and 0xFF) }
        }
        fun u32(v: Long) {
            val b = intArrayOf(
                ((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(),
                ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt()
            )
            if (littleEndian) for (i in 3 downTo 0) tiff.write(b[i]) else for (i in 0..3) tiff.write(b[i])
        }

        if (littleEndian) { tiff.write('I'.code); tiff.write('I'.code) }
        else { tiff.write('M'.code); tiff.write('M'.code) }
        u16(0x002A)
        u32(8L)                              // the first IFD follows the header
        u16(1)                               // one entry
        u16(0xB002)                          // MP Entry
        u16(7)                               // UNDEFINED
        u32((imageSizes.size * 16).toLong())
        u32(8L + 2 + 12 + 4)                 // entry array offset, TIFF-relative
        u32(0L)                              // no next IFD
        for (s in imageSizes) { u32(0L); u32(s); u32(0L); u16(0); u16(0) }

        return "MPF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + tiff.toByteArray()
    }
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/sese/keepix/utils/jpeg/MpfIndexTest.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.mpfPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpfIndexTest {

    private fun segmentFor(payload: ByteArray): Pair<ByteArray, JpegSegment> {
        val file = JpegFixtures.concat(JpegFixtures.soi(), JpegFixtures.segment(JpegMarkers.APP2, payload))
        val seg = JpegParser.parseHeader(
            JpegFixtures.concat(file, JpegFixtures.sof0(), JpegFixtures.sos(byteArrayOf(1)), JpegFixtures.eoi())
        ).let { (it as HeaderResult.Ok).header.segments.first() }
        return JpegFixtures.concat(file, JpegFixtures.sof0(), JpegFixtures.sos(byteArrayOf(1)), JpegFixtures.eoi()) to seg
    }

    @Test
    fun readPrimaryImageSize_bigEndian_returnsFirstEntrySize() {
        val (bytes, seg) = segmentFor(mpfPayload(listOf(123456L, 7890L)))
        assertEquals(123456L, MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_littleEndian_returnsFirstEntrySize() {
        val (bytes, seg) = segmentFor(mpfPayload(listOf(999L, 111L), littleEndian = true))
        assertEquals(999L, MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_notAnMpfSegment_returnsNull() {
        val (bytes, seg) = segmentFor("ICC_PROFILE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(20))
        assertNull(MpfIndex.readPrimaryImageSize(bytes, seg))
    }

    @Test
    fun readPrimaryImageSize_truncatedOrCorrupt_returnsNullRatherThanGuessing() {
        // Valid identifier, garbage TIFF.
        val (b1, s1) = segmentFor("MPF".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + ByteArray(12))
        assertNull(MpfIndex.readPrimaryImageSize(b1, s1))

        // Correct header, but the entry array offset points past the segment.
        val good = mpfPayload(listOf(500L))
        val truncated = good.copyOf(good.size - 8)
        val (b2, s2) = segmentFor(truncated)
        assertNull(MpfIndex.readPrimaryImageSize(b2, s2))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.MpfIndexTest"
```

Expected: `Unresolved reference: MpfIndex`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/sese/keepix/utils/jpeg/MpfIndex.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

/**
 * Reads the primary image's declared byte length out of an APP2/MPF index.
 *
 * READ-ONLY AND ADVISORY. Two uses, neither of which trusts it with a
 * destructive decision:
 *  - the analyser's estimate, which only needs a file's header rather than all
 *    of it, so a whole library can be measured affordably;
 *  - a cross-check against the authoritative EOI walk before a rewrite.
 *
 * Every parse failure returns null. An MPF index that cannot be read is not an
 * error -- it simply means the estimate falls back to zero and the cross-check
 * is skipped.
 */
object MpfIndex {

    private const val MP_ENTRY_TAG = 0xB002
    private const val MP_ENTRY_SIZE = 16
    private const val TIFF_MAGIC = 0x002A

    fun readPrimaryImageSize(bytes: ByteArray, segment: JpegSegment): Long? {
        if (segment.marker != JpegMarkers.APP2 || segment.identifier != "MPF") return null

        // The TIFF header starts right after "MPF\0"; every offset below is
        // relative to it, not to the file.
        val tiff = segment.payloadOffset + 4
        val end = segment.offset + segment.length
        if (end > bytes.size || tiff + 8 > end) return null

        val little = when {
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> true
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(bytes, tiff + 2, little) != TIFF_MAGIC) return null

        val ifdOffset = u32(bytes, tiff + 4, little)
        val ifd = tiff + ifdOffset.toInt()
        if (ifdOffset <= 0 || ifd + 2 > end) return null

        val entryCount = u16(bytes, ifd, little)
        if (entryCount <= 0 || ifd + 2 + entryCount * 12 > end) return null

        for (i in 0 until entryCount) {
            val e = ifd + 2 + i * 12
            if (u16(bytes, e, little) != MP_ENTRY_TAG) continue

            val valueCount = u32(bytes, e + 4, little)
            if (valueCount < MP_ENTRY_SIZE) return null

            val arrayOffset = u32(bytes, e + 8, little)
            val array = tiff + arrayOffset.toInt()
            if (arrayOffset <= 0 || array + MP_ENTRY_SIZE > end) return null

            // Bytes 4..7 of MP entry 0: the primary image's size, measured from
            // the start of the file.
            val size = u32(bytes, array + 4, little)
            return if (size > 0) size else null
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) (u8(b, i + 1) shl 8) or u8(b, i)
        else (u8(b, i) shl 8) or u8(b, i + 1)

    private fun u32(b: ByteArray, i: Int, little: Boolean): Long =
        if (little) {
            (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or
                (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()
        } else {
            (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
                (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
        }
}
```

- [ ] **Step 5: Run it to verify it passes**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.MpfIndexTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/jpeg/MpfIndex.kt app/src/test/java/com/sese/keepix/utils/jpeg/MpfIndexTest.kt app/src/test/java/com/sese/keepix/utils/jpeg/JpegFixtures.kt
```

Message body:

```
feat(jpeg): read the MPF index's primary image size

Advisory only. It lets the analyser estimate a whole library from file headers
instead of reading every byte of every photo, and it gives the rewriter a second
opinion to cross-check the EOI walk against. Any parse failure returns null, so
an unreadable index degrades the estimate rather than blocking a file.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 3: Strip planner and rewriter

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/jpeg/JpegRewriter.kt`
- Test: `app/src/test/java/com/sese/keepix/utils/jpeg/JpegRewriterTest.kt`

**Interfaces:**
- Consumes: `JpegStructure`, `JpegSegment`, `JpegMarkers`, `JpegParser` (Task 1).
- Produces:
  - `StripPlan(droppedSegmentOffsets: Set<Int>, truncateAt: Int, bytesSaved: Int, outputSize: Int)`
  - `JpegRewriter.planStrip(structure: JpegStructure): StripPlan`
  - `JpegRewriter.meetsSavingFloor(plan: StripPlan, originalSize: Int): Boolean`
  - `JpegRewriter.rewrite(bytes: ByteArray, structure: JpegStructure, plan: StripPlan): ByteArray`
  - `JpegRewriter.MIN_SAVING_BYTES`, `JpegRewriter.MIN_SAVING_RATIO`

This is where the spec's §5 policy table lives. **Only APP2 segments whose identifier is exactly `"MPF"` are dropped. Everything else is copied verbatim, including any segment the parser could not identify.**

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/sese/keepix/utils/jpeg/JpegRewriterTest.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegRewriterTest {

    private val scan = ByteArray(2048) { (it % 251).toByte() }

    /** A dual-camera-shaped file: MPF index segment plus a whole trailing JPEG. */
    private fun fileWithMpf(secondaryPayload: Int = 200_000): ByteArray {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP0, "JFIF", ByteArray(9)),
            app(JpegMarkers.APP1, "Exif", ByteArray(2000)),
            app(JpegMarkers.APP2, "ICC_PROFILE", ByteArray(3000)),
            app(JpegMarkers.APP2, "MPF", ByteArray(80)),
            app(JpegMarkers.APP1, "http://ns.adobe.com/xap/1.0/", ByteArray(400)),
            sof0(), sos(scan), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(ByteArray(secondaryPayload) { 7 }), eoi())
        return concat(primary, secondary)
    }

    @Test
    fun planStrip_dropsOnlyTheMpfSegment_andTruncatesAtThePrimaryEoi() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)

        val mpf = s.segments.single { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" }
        assertEquals(setOf(mpf.offset), plan.droppedSegmentOffsets)
        assertEquals(s.primaryEndOffset, plan.truncateAt)
        assertEquals(mpf.length + s.trailingBytes, plan.bytesSaved)
        assertEquals(bytes.size - plan.bytesSaved, plan.outputSize)
    }

    @Test
    fun rewrite_preservesScanDataByteForByte() {
        // The core lossless claim, asserted directly against the actual bytes.
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))

        val outStruct = JpegParser.parseFull(out)!!
        val inSos = s.segments.last { it.marker == JpegMarkers.SOS }
        val outSos = outStruct.segments.last { it.marker == JpegMarkers.SOS }

        val inScan = bytes.copyOfRange(inSos.offset + inSos.length, s.primaryEndOffset)
        val outScan = out.copyOfRange(outSos.offset + outSos.length, outStruct.primaryEndOffset)
        assertArrayEquals("entropy-coded scan data must be untouched", inScan, outScan)
    }

    @Test
    fun rewrite_keepsIccXmpExifAndJfifByteForByte() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))
        val outStruct = JpegParser.parseFull(out)!!

        val kept = listOf("JFIF", "Exif", "ICC_PROFILE", "http://ns.adobe.com/xap/1.0/")
        for (id in kept) {
            val a = s.segments.single { it.identifier == id }
            val b = outStruct.segments.single { it.identifier == id }
            assertArrayEquals(
                "segment $id must survive byte-for-byte",
                bytes.copyOfRange(a.offset, a.offset + a.length),
                out.copyOfRange(b.offset, b.offset + b.length)
            )
        }
        assertTrue(
            "MPF must be gone",
            outStruct.segments.none { it.marker == JpegMarkers.APP2 && it.identifier == "MPF" }
        )
    }

    @Test
    fun rewrite_outputIsAStructurallyValidJpegWithNoTrailingBytes() {
        val bytes = fileWithMpf()
        val s = JpegParser.parseFull(bytes)!!
        val out = JpegRewriter.rewrite(bytes, s, JpegRewriter.planStrip(s))

        val outStruct = JpegParser.parseFull(out)
        assertNotNull(outStruct)
        assertEquals(0, outStruct!!.trailingBytes)
        assertEquals(out.size, outStruct.primaryEndOffset)
    }

    @Test
    fun planStrip_fileWithNothingToStrip_savesNothing() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(100)), sof0(), sos(scan), eoi())
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)

        assertEquals(0, plan.bytesSaved)
        assertTrue(plan.droppedSegmentOffsets.isEmpty())
        assertFalse(JpegRewriter.meetsSavingFloor(plan, bytes.size))
    }

    @Test
    fun meetsSavingFloor_requiresBothAbsoluteAndRelativeGains() {
        fun plan(saved: Int) = StripPlan(emptySet(), 0, saved, 0)

        // 25 KB saved out of 10 MB is 0.24% -- fails the ratio.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(25 * 1024), 10 * 1024 * 1024))
        // 10 KB saved out of 100 KB is 10% -- fails the absolute floor.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(10 * 1024), 100 * 1024))
        // Exactly at each boundary: the spec says strictly greater.
        assertFalse(JpegRewriter.meetsSavingFloor(plan(20 * 1024), 400 * 1024))
        // 200 KB out of 3 MB clears both.
        assertTrue(JpegRewriter.meetsSavingFloor(plan(200 * 1024), 3 * 1024 * 1024))
    }

    @Test
    fun planStrip_keepsAnyAppSegmentItCannotIdentify() {
        // Unknown means unknown. Never drop on a guess.
        val bytes = concat(
            soi(),
            JpegFixtures.segment(JpegMarkers.APP2, ByteArray(64) { 0x5A }),  // no NUL-terminated id
            sof0(), sos(scan), eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)
        assertTrue(plan.droppedSegmentOffsets.isEmpty())

        val out = JpegRewriter.rewrite(bytes, s, plan)
        assertArrayEquals("an unrecognised segment must be copied unchanged", bytes, out)
    }

    @Test
    fun rewrite_multipleMpfSegments_dropsAllOfThem() {
        val bytes = concat(
            soi(),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            app(JpegMarkers.APP1, "Exif", ByteArray(100)),
            app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(scan), eoi()
        )
        val s = JpegParser.parseFull(bytes)!!
        val plan = JpegRewriter.planStrip(s)
        assertEquals(2, plan.droppedSegmentOffsets.size)

        val out = JpegRewriter.rewrite(bytes, s, plan)
        assertEquals(plan.outputSize, out.size)
        assertNotNull(JpegParser.parseFull(out))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.JpegRewriterTest"
```

Expected: `Unresolved reference: JpegRewriter` / `StripPlan`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/sese/keepix/utils/jpeg/JpegRewriter.kt`:

```kotlin
package com.sese.keepix.utils.jpeg

import java.io.ByteArrayOutputStream

/**
 * What a rewrite of one file would remove.
 *
 * @param droppedSegmentOffsets [JpegSegment.offset] of every segment to omit.
 * @param truncateAt exclusive end of the output, i.e. just past the primary EOI.
 * @param bytesSaved dropped segments plus everything after the primary EOI.
 * @param outputSize the resulting file's size.
 */
data class StripPlan(
    val droppedSegmentOffsets: Set<Int>,
    val truncateAt: Int,
    val bytesSaved: Int,
    val outputSize: Int
)

/**
 * Rebuilds a JPEG without its MPF payload.
 *
 * The strip policy is the whole of spec §5 and lives here, next to the mechanism
 * that applies it, because the two change together. Its shape is a deliberate
 * allow-nothing default: a segment is dropped ONLY if positively identified as
 * MPF. Everything else -- ICC, XMP, EXIF, JFIF, and anything the parser could
 * not name -- is copied verbatim.
 *
 * Removing ICC in particular would leave pixels identical while rendering a
 * Display-P3 photo oversaturated in any colour-managed viewer: visibly wrong
 * output, which is exactly the failure this feature exists to avoid.
 */
object JpegRewriter {

    /** Spec §8: a rewrite must clear BOTH floors, strictly. */
    const val MIN_SAVING_BYTES: Int = 20 * 1024
    const val MIN_SAVING_RATIO: Double = 0.05

    private fun isMpf(s: JpegSegment): Boolean =
        s.marker == JpegMarkers.APP2 && s.identifier == "MPF"

    /**
     * The MPF secondary image is a whole second JPEG concatenated after the
     * primary EOI, so truncating there removes it. Its APP2 index segment goes
     * too -- left behind, it would point at bytes that no longer exist.
     */
    fun planStrip(structure: JpegStructure): StripPlan {
        val dropped = structure.segments.filter(::isMpf)
        val saved = dropped.sumOf { it.length } + structure.trailingBytes
        return StripPlan(
            droppedSegmentOffsets = dropped.map { it.offset }.toSet(),
            truncateAt = structure.primaryEndOffset,
            bytesSaved = saved,
            outputSize = structure.totalLength - saved
        )
    }

    /**
     * Below either floor the original is kept. Churning a user's photo library
     * for a rounding error is all risk and no reward, and every rewrite carries
     * some risk however small.
     */
    fun meetsSavingFloor(plan: StripPlan, originalSize: Int): Boolean {
        if (originalSize <= 0) return false
        return plan.bytesSaved > MIN_SAVING_BYTES &&
            plan.bytesSaved.toDouble() / originalSize > MIN_SAVING_RATIO
    }

    /**
     * Copies [bytes] into a new array, omitting the planned segments and stopping
     * at the primary EOI.
     *
     * Everything between two dropped segments is copied as one raw span, so the
     * entropy-coded scan data is moved without ever being examined -- the output
     * is bit-identical in pixels because the pixel bytes ARE the input's bytes.
     */
    fun rewrite(bytes: ByteArray, structure: JpegStructure, plan: StripPlan): ByteArray {
        require(plan.truncateAt <= bytes.size) { "truncateAt is past the end of the input" }
        val out = ByteArrayOutputStream(plan.outputSize)
        var cursor = 0
        // segments are in ascending offset order by construction.
        for (segment in structure.segments) {
            if (segment.offset !in plan.droppedSegmentOffsets) continue
            out.write(bytes, cursor, segment.offset - cursor)
            cursor = segment.offset + segment.length
        }
        out.write(bytes, cursor, plan.truncateAt - cursor)
        return out.toByteArray()
    }
}
```

- [ ] **Step 4: Run to verify the tests pass**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.jpeg.JpegRewriterTest"
```

Expected: `BUILD SUCCESSFUL`, 8 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/jpeg/JpegRewriter.kt app/src/test/java/com/sese/keepix/utils/jpeg/JpegRewriterTest.kt
```

Message body:

```
feat(jpeg): strip MPF payloads by copying byte spans

planStrip drops only an APP2 segment positively identified as MPF and truncates
at the primary EOI, which is where the concatenated secondary image begins.
Everything else -- ICC especially, whose removal would render a Display-P3 photo
oversaturated -- is copied verbatim, as is anything the parser could not name.

rewrite() moves the entropy-coded scan as an opaque span, so the output's pixel
bytes are literally the input's. A test asserts that byte-for-byte.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 4: Compression journal schema

**Files:**
- Create: `app/src/main/java/com/sese/keepix/db/CompressionJournalEntity.kt`
- Create: `app/src/main/java/com/sese/keepix/db/CompressionJournalDao.kt`
- Modify: `app/src/main/java/com/sese/keepix/db/AppDatabase.kt`
- Test: `app/src/androidTest/java/com/sese/keepix/db/RoomMigration5To6Test.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CompressionJournalEntity(mediaUri: String, backupPath: String, originalSize: Long, startedAt: Long)`, primary key `mediaUri`
  - `CompressionJournalDao.insert(entry)`, `.getAll(): List<CompressionJournalEntity>`, `.count(): Int`, `.deleteByUri(uri: String)`
  - `AppDatabase.compressionJournalDao()`, `AppDatabase.MIGRATION_5_6`

- [ ] **Step 1: Write the entity**

Create `app/src/main/java/com/sese/keepix/db/CompressionJournalEntity.kt`:

```kotlin
package com.sese.keepix.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One in-flight rewrite.
 *
 * A row exists from just before the first byte is written until after the
 * read-back comparison passes, and for exactly that window a backup of the
 * original sits at [backupPath]. So a row still present at launch means a write
 * did not finish, and the original must be restored before anything else runs.
 *
 * [mediaUri] is the primary key: one rewrite per file at a time, and re-entering
 * recovery for the same file is idempotent.
 */
@Entity(tableName = "compression_journal")
data class CompressionJournalEntity(
    @PrimaryKey val mediaUri: String,
    /** Absolute path of the app-private copy of the original. */
    val backupPath: String,
    val originalSize: Long,
    val startedAt: Long
)
```

- [ ] **Step 2: Write the DAO**

Create `app/src/main/java/com/sese/keepix/db/CompressionJournalDao.kt`:

```kotlin
package com.sese.keepix.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompressionJournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CompressionJournalEntity)

    /**
     * Oldest first, so recovery repairs files in the order they were damaged.
     * A suspend list rather than a Flow: recovery reads this once at launch and
     * acts on it, and a Flow would re-fire as recovery deleted its own rows.
     */
    @Query("SELECT * FROM compression_journal ORDER BY startedAt ASC")
    suspend fun getAll(): List<CompressionJournalEntity>

    @Query("SELECT COUNT(*) FROM compression_journal")
    suspend fun count(): Int

    @Query("DELETE FROM compression_journal WHERE mediaUri = :mediaUri")
    suspend fun deleteByUri(mediaUri: String)
}
```

- [ ] **Step 3: Bump the database to v6**

In `app/src/main/java/com/sese/keepix/db/AppDatabase.kt`, change the `@Database` annotation and add the accessor:

```kotlin
@Database(
    entities = [BinItemEntity::class, KeptItemEntity::class, CompressionJournalEntity::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun binItemDao(): BinItemDao
    abstract fun keptItemDao(): KeptItemDao
    abstract fun compressionJournalDao(): CompressionJournalDao
```

Add this migration inside the `companion object`, immediately after `MIGRATION_4_5`:

```kotlin
        /**
         * Adds the `compression_journal` table. Purely additive: no existing row
         * is read or rewritten, and an upgraded database starts with an empty
         * journal, which is correct -- nothing was ever mid-write.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `compression_journal` (
                        `mediaUri` TEXT NOT NULL,
                        `backupPath` TEXT NOT NULL,
                        `originalSize` INTEGER NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaUri`)
                    )
                    """.trimIndent()
                )
            }
        }
```

And register it in `getDatabase`:

```kotlin
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
```

`fallbackToDestructiveMigration()` must not be added. If the migration is wrong, the app should fail loudly, not wipe the user's kept library.

- [ ] **Step 4: Write the migration test**

Create `app/src/androidTest/java/com/sese/keepix/db/RoomMigration5To6Test.kt`:

```kotlin
package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-5-6"

/**
 * Regression coverage for [AppDatabase.MIGRATION_5_6]. Modelled on
 * `RoomMigration4To5Test`, including its third test, which is the one that
 * matters: the first two apply the migration explicitly and would stay green
 * through a revert that dropped it from `getDatabase()`.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration5To6Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate5To6_addsAnEmptyJournal_andPreservesKeptRows() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO kept_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     keptAt, width, height, durationMs, isFavorite, pendingFavoriteSync)
                VALUES
                    (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                     'IMAGE', 100, 200, 4, 3, 0, 1, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        val kept = migrated.query("SELECT id, isFavorite FROM kept_items")
        assertTrue(kept.moveToFirst())
        assertEquals(1L, kept.getLong(kept.getColumnIndexOrThrow("id")))
        assertEquals(1, kept.getInt(kept.getColumnIndexOrThrow("isFavorite")))
        assertEquals(1, kept.count)
        kept.close()

        val journal = migrated.query("SELECT COUNT(*) FROM compression_journal")
        assertTrue(journal.moveToFirst())
        assertEquals("a fresh journal must be empty", 0, journal.getInt(0))
        journal.close()

        migrated.close()
    }

    /**
     * See the identical test in `RoomMigration4To5Test` for the full reasoning:
     * this is the only one that exercises [AppDatabase.getDatabase], the single
     * place a real user's database is opened, so it is the only one that would
     * catch MIGRATION_5_6 being dropped from `.addMigrations(...)` or
     * `fallbackToDestructiveMigration()` reappearing there.
     */
    @Test
    fun getDatabase_singleton_migratesTheRealOnDiskDatabaseWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realDbName = "keepix_database"

        context.deleteDatabase(realDbName)
        resetGetDatabaseSingleton()

        var realDb: AppDatabase? = null
        try {
            helper.createDatabase(realDbName, 5).apply {
                execSQL(
                    """
                    INSERT INTO kept_items
                        (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                         keptAt, width, height, durationMs, isFavorite, pendingFavoriteSync)
                    VALUES
                        (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                         'IMAGE', 100, 200, 4, 3, 0, 0, 0)
                    """.trimIndent()
                )
                close()
            }

            realDb = AppDatabase.getDatabase(context)
            val ids = kotlinx.coroutines.runBlocking { realDb!!.keptItemDao().getAllKeptMediaIds() }
            assertEquals("getDatabase must migrate, not wipe", listOf(42L), ids)

            val journalCount = kotlinx.coroutines.runBlocking {
                realDb!!.compressionJournalDao().count()
            }
            assertEquals(0, journalCount)
        } finally {
            realDb?.close()
            resetGetDatabaseSingleton()
            context.deleteDatabase(realDbName)
        }
    }

    /** Kotlin puts INSTANCE's backing field on AppDatabase, not on Companion. */
    private fun resetGetDatabaseSingleton() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }.set(null, null)
    }
}
```

- [ ] **Step 5: Compile and confirm the schema is exported**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, and `app/schemas/com.sese.keepix.db.AppDatabase/6.json` now exists. If Room reports "Migration didn't properly handle", the `CREATE TABLE` above does not match the generated schema — read `6.json` and align the SQL to it exactly rather than adjusting the entity.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/db/CompressionJournalEntity.kt app/src/main/java/com/sese/keepix/db/CompressionJournalDao.kt app/src/main/java/com/sese/keepix/db/AppDatabase.kt app/src/androidTest/java/com/sese/keepix/db/RoomMigration5To6Test.kt app/schemas/com.sese.keepix.db.AppDatabase/6.json
```

Message body:

```
feat(db): add the compression journal, database v6

A row exists only while a rewrite is mid-flight and a backup of the original is
on disk, so a row surviving a launch means a write did not finish and the
original must be restored. Additive migration; no existing row is touched.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 5: The compressor engine

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/MediaFileIo.kt`
- Create: `app/src/main/java/com/sese/keepix/utils/PhotoCompressor.kt`
- Test: `app/src/test/java/com/sese/keepix/utils/PhotoCompressorTest.kt`

**Interfaces:**
- Consumes: `JpegParser`, `JpegRewriter`, `StripPlan`, `MpfIndex` (Tasks 1–3); `CompressionJournalDao`, `CompressionJournalEntity` (Task 4).
- Produces:
  - `interface MediaFileIo { suspend fun readAll(uriString: String): ByteArray; suspend fun overwrite(uriString: String, bytes: ByteArray) }`
  - `sealed interface CompressionOutcome { Compressed(uriString, bytesSaved: Int); Skipped(uriString, reason: String); Failed(uriString, reason: String, restored: Boolean) }`
  - `class PhotoCompressor(io: MediaFileIo, journalDao: CompressionJournalDao, backupDir: File)` with
    `suspend fun compress(uriString: String): CompressionOutcome` and
    `suspend fun recover(): List<CompressionOutcome>`

**Why the `MediaFileIo` seam exists:** this is the only code in the feature that can destroy a photo, so it must be testable on the host JVM, where a fake can inject a mid-write failure, a short write, and a process death. `ContentResolver` and `Uri` cannot be exercised there, so the compressor works in URI *strings* and reads and writes through this interface. The Android implementation is in the same file and is deliberately trivial.

- [ ] **Step 1: Write the I/O interface and its Android implementation**

Create `app/src/main/java/com/sese/keepix/utils/MediaFileIo.kt`:

```kotlin
package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

/**
 * Reads and writes a MediaStore file's whole contents.
 *
 * Exists so [PhotoCompressor] -- the only code here that can destroy a photo --
 * runs on the host JVM against a fake that can inject a mid-write failure or a
 * short write. Callers pass URI *strings*, because `android.net.Uri` has no
 * usable implementation off-device.
 */
interface MediaFileIo {
    suspend fun readAll(uriString: String): ByteArray
    suspend fun overwrite(uriString: String, bytes: ByteArray)
}

/**
 * The real implementation.
 *
 * `"wt"` truncates before writing, which matters: the output is always smaller
 * than the input, and without truncation the tail of the old file would survive
 * past the new EOI as garbage.
 *
 * `IS_PENDING` is deliberately NOT set around the write. It would hide a torn
 * read during the write window, but if the process died while it was set, the
 * photo would be invisible in every gallery app until recovery ran -- and
 * recovery can be declined. A file truncated mid-write fails to decode cleanly
 * and is repaired on next launch; a file that has silently vanished from the
 * user's gallery looks like data loss. The window is one file write long, and
 * the failure it would prevent is strictly less bad than the one it introduces.
 */
class ContentResolverMediaFileIo(private val context: Context) : MediaFileIo {

    override suspend fun readAll(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uriString for reading")
    }

    override suspend fun overwrite(uriString: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "wt")
            ?: throw IOException("Cannot open $uriString for writing")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.write(bytes)
                out.flush()
                // fsync before the descriptor closes. Without it a crash can
                // leave the file's blocks unwritten while the journal row has
                // already been deleted, which is the one ordering this design
                // must never produce.
                it.fileDescriptor.sync()
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing compressor tests**

Create `app/src/test/java/com/sese/keepix/utils/PhotoCompressorTest.kt`:

```kotlin
package com.sese.keepix.utils

import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.utils.jpeg.JpegFixtures
import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegMarkers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * In-memory [MediaFileIo] with fault injection. Each fault fires once, so a test
 * can inject a failure and then observe the restore path succeeding.
 */
private class FakeMediaFileIo(initial: Map<String, ByteArray>) : MediaFileIo {
    val files = initial.mapValues { it.value.copyOf() }.toMutableMap()
    var writeCount = 0
    /** Throw on the next overwrite, after leaving the file truncated. */
    var failNextWriteAfterTruncating = false
    /** Silently write only the first half of the bytes on the next overwrite. */
    var truncateNextWrite = false

    override suspend fun readAll(uriString: String): ByteArray =
        files[uriString]?.copyOf() ?: throw IOException("no such file: $uriString")

    override suspend fun overwrite(uriString: String, bytes: ByteArray) {
        writeCount++
        if (failNextWriteAfterTruncating) {
            failNextWriteAfterTruncating = false
            files[uriString] = ByteArray(0)
            throw IOException("simulated write failure")
        }
        if (truncateNextWrite) {
            truncateNextWrite = false
            files[uriString] = bytes.copyOf(bytes.size / 2)
            return
        }
        files[uriString] = bytes.copyOf()
    }
}

/** Minimal in-memory journal. */
private class FakeJournalDao : CompressionJournalDao {
    val rows = LinkedHashMap<String, CompressionJournalEntity>()
    override suspend fun insert(entry: CompressionJournalEntity) { rows[entry.mediaUri] = entry }
    override suspend fun getAll(): List<CompressionJournalEntity> = rows.values.sortedBy { it.startedAt }
    override suspend fun count(): Int = rows.size
    override suspend fun deleteByUri(mediaUri: String) { rows.remove(mediaUri) }
}

class PhotoCompressorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val uri = "content://media/external/images/media/42"
    private lateinit var original: ByteArray

    @Before
    fun setUp() {
        val primary = concat(
            soi(),
            app(JpegMarkers.APP1, "Exif", ByteArray(4000)),
            app(JpegMarkers.APP2, "MPF", ByteArray(80)),
            sof0(), sos(ByteArray(60_000) { (it % 251).toByte() }), eoi()
        )
        val secondary = concat(soi(), sof0(), sos(ByteArray(300_000) { 9 }), eoi())
        original = concat(primary, secondary)
    }

    private fun compressor(io: FakeMediaFileIo, dao: FakeJournalDao) =
        PhotoCompressor(io, dao, temp.newFolder("backups"))

    @Test
    fun compress_happyPath_shrinksTheFileAndLeavesNoJournalRowOrBackup() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        val backupDir = temp.newFolder("backups2")
        val outcome = PhotoCompressor(io, dao, backupDir).compress(uri)

        assertTrue("expected Compressed but got $outcome", outcome is CompressionOutcome.Compressed)
        val saved = (outcome as CompressionOutcome.Compressed).bytesSaved
        assertTrue(saved > 300_000)
        assertEquals(original.size - saved, io.files[uri]!!.size)
        assertTrue("journal must be empty after success", dao.rows.isEmpty())
        assertEquals("backup must be deleted after success", 0, backupDir.listFiles()!!.size)
    }

    @Test
    fun compress_preservesTheExifSegmentAndTheScanExactly() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        compressor(io, FakeJournalDao()).compress(uri)

        val out = io.files[uri]!!
        val inStruct = com.sese.keepix.utils.jpeg.JpegParser.parseFull(original)!!
        val outStruct = com.sese.keepix.utils.jpeg.JpegParser.parseFull(out)!!

        val a = inStruct.segments.single { it.identifier == "Exif" }
        val b = outStruct.segments.single { it.identifier == "Exif" }
        assertArrayEquals(
            original.copyOfRange(a.offset, a.offset + a.length),
            out.copyOfRange(b.offset, b.offset + b.length)
        )

        val aSos = inStruct.segments.last { it.marker == JpegMarkers.SOS }
        val bSos = outStruct.segments.last { it.marker == JpegMarkers.SOS }
        assertArrayEquals(
            original.copyOfRange(aSos.offset + aSos.length, inStruct.primaryEndOffset),
            out.copyOfRange(bSos.offset + bSos.length, outStruct.primaryEndOffset)
        )
    }

    @Test
    fun compress_writeThrows_restoresTheOriginalAndClearsTheJournal() = runTest {
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        val backupDir = temp.newFolder("backups3")
        io.failNextWriteAfterTruncating = true

        val outcome = PhotoCompressor(io, dao, backupDir).compress(uri)

        assertTrue(outcome is CompressionOutcome.Failed)
        assertTrue((outcome as CompressionOutcome.Failed).restored)
        assertArrayEquals("the original must come back byte-for-byte", original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, backupDir.listFiles()!!.size)
    }

    @Test
    fun compress_shortWrite_isCaughtByReadBackAndRestored() = runTest {
        // The failure ExifInterface verification would miss: the front of the
        // file, where EXIF lives, is intact while the image is destroyed.
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        io.truncateNextWrite = true

        val outcome = compressor(io, dao).compress(uri)

        assertTrue("a short write must not be accepted", outcome is CompressionOutcome.Failed)
        assertArrayEquals(original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun compress_belowTheSavingFloor_skipsWithoutWritingAnything() = runTest {
        val small = concat(
            soi(), app(JpegMarkers.APP2, "MPF", ByteArray(40)),
            sof0(), sos(ByteArray(500_000) { 3 }), eoi()
        )
        val io = FakeMediaFileIo(mapOf(uri to small))
        val dao = FakeJournalDao()

        val outcome = compressor(io, dao).compress(uri)

        assertTrue(outcome is CompressionOutcome.Skipped)
        assertEquals("nothing may be written for a skip", 0, io.writeCount)
        assertArrayEquals(small, io.files[uri])
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun compress_nonJpeg_isSkippedAndNeverOpenedForWrite() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(1_000_000)
        val io = FakeMediaFileIo(mapOf(uri to png))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue(outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
    }

    @Test
    fun compress_mpfIndexDisagreesWithTheEoiWalk_skipsRatherThanTruncating() = runTest {
        // An MPF index claiming the primary is SHORTER than where the EOI walk
        // found it means the two disagree about where the image ends. Skip.
        val payload = JpegFixtures.mpfPayload(listOf(10L))
        val bytes = concat(
            soi(), app(JpegMarkers.APP2, "MPF", payload),
            sof0(), sos(ByteArray(400_000) { 5 }), eoi(),
            soi(), sof0(), sos(ByteArray(400_000) { 6 }), eoi()
        )
        val io = FakeMediaFileIo(mapOf(uri to bytes))
        val outcome = compressor(io, FakeJournalDao()).compress(uri)

        assertTrue("expected Skipped but got $outcome", outcome is CompressionOutcome.Skipped)
        assertEquals(0, io.writeCount)
    }

    @Test
    fun recover_orphanedRow_restoresTheOriginalAndClearsTheRow() = runTest {
        val backupDir = temp.newFolder("backups4")
        val backup = java.io.File(backupDir, "orphan.bak")
        backup.writeBytes(original)

        // A half-written file plus a journal row: exactly the state a process
        // death between the write and the read-back leaves behind.
        val io = FakeMediaFileIo(mapOf(uri to original.copyOf(1000)))
        val dao = FakeJournalDao()
        dao.insert(CompressionJournalEntity(uri, backup.absolutePath, original.size.toLong(), 1L))

        val outcomes = PhotoCompressor(io, dao, backupDir).recover()

        assertEquals(1, outcomes.size)
        assertArrayEquals(original, io.files[uri])
        assertTrue(dao.rows.isEmpty())
        assertFalse("the backup must be released after a successful restore", backup.exists())
    }

    @Test
    fun recover_missingBackupFile_dropsTheRowInsteadOfLoopingForever() = runTest {
        val backupDir = temp.newFolder("backups5")
        val io = FakeMediaFileIo(mapOf(uri to original))
        val dao = FakeJournalDao()
        dao.insert(
            CompressionJournalEntity(uri, java.io.File(backupDir, "gone.bak").absolutePath, 1L, 1L)
        )

        val outcomes = PhotoCompressor(io, dao, backupDir).recover()

        assertTrue(outcomes.single() is CompressionOutcome.Failed)
        assertEquals("nothing to restore from, so nothing may be written", 0, io.writeCount)
        assertTrue("an unrecoverable row must not be retried every launch", dao.rows.isEmpty())
    }

}
```

- [ ] **Step 3: Run to verify failure**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.PhotoCompressorTest"
```

Expected: `Unresolved reference: PhotoCompressor` / `CompressionOutcome`.

- [ ] **Step 4: Write the compressor**

Create `app/src/main/java/com/sese/keepix/utils/PhotoCompressor.kt`:

```kotlin
package com.sese.keepix.utils

import android.util.Log
import com.sese.keepix.db.CompressionJournalDao
import com.sese.keepix.db.CompressionJournalEntity
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import com.sese.keepix.utils.jpeg.JpegRewriter
import com.sese.keepix.utils.jpeg.MpfIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "PhotoCompressor"

sealed interface CompressionOutcome {
    val uriString: String

    data class Compressed(override val uriString: String, val bytesSaved: Int) : CompressionOutcome
    data class Skipped(override val uriString: String, val reason: String) : CompressionOutcome
    data class Failed(
        override val uriString: String,
        val reason: String,
        /**
         * True when the file is known to be in its original state -- either it
         * was never written, or the backup was successfully put back. False is
         * the serious case: a write happened and could not be undone, and the
         * journal row and backup have deliberately been left in place so the
         * next launch tries again.
         */
        val restored: Boolean
    ) : CompressionOutcome
}

/**
 * Rewrites one photo at a time, crash-safely.
 *
 * The ordering is the whole design, and it is deliberately paranoid:
 *
 *  1. Everything that could reject the file happens BEFORE the file is opened
 *     for writing -- parse, plan, floor check, MPF cross-check, and a self-check
 *     that the rewritten bytes re-parse. A file that is going to be skipped is
 *     never touched at all.
 *  2. The backup is written and fsynced, and the journal row inserted, before
 *     the first byte of the real write.
 *  3. After the write, the file is read back and compared to the exact bytes
 *     that were meant to land. Anything short of equality restores.
 *  4. Only then are the backup and the journal row released.
 *
 * The backup is taken per file rather than for the whole batch: copying an
 * entire run up front could exhaust storage, and this ordering still recovers
 * correctly because at most one file is ever mid-write.
 *
 * [backupDir] must be app-private storage -- it holds unmodified copies of the
 * user's photos, briefly.
 */
class PhotoCompressor(
    private val io: MediaFileIo,
    private val journalDao: CompressionJournalDao,
    private val backupDir: File
) {

    suspend fun compress(uriString: String): CompressionOutcome {
        val original = try {
            io.readAll(uriString)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $uriString", e)
            return CompressionOutcome.Failed(uriString, "unreadable", restored = true)
        }

        val structure = JpegParser.parseFull(original)
            ?: return CompressionOutcome.Skipped(uriString, "not a parseable JPEG")

        val plan = JpegRewriter.planStrip(structure)
        if (!JpegRewriter.meetsSavingFloor(plan, original.size)) {
            return CompressionOutcome.Skipped(uriString, "below the saving floor")
        }

        // Cross-check against the MPF index, which states the primary image's
        // length independently of the EOI walk. Asymmetric on purpose: padding
        // between images can legitimately put the real EOI BEFORE the declared
        // end, but nothing legitimate puts it after. A declared end that is
        // shorter than where the EOI actually is means the two disagree about
        // where the image ends, and truncating on a disagreement is how a photo
        // gets destroyed. An unreadable index is not a disagreement -- it just
        // skips the check.
        val mpfSegment = structure.segments.firstOrNull {
            it.marker == JpegMarkers.APP2 && it.identifier == "MPF"
        }
        if (mpfSegment != null) {
            val declared = MpfIndex.readPrimaryImageSize(original, mpfSegment)
            if (declared != null && structure.primaryEndOffset > declared) {
                return CompressionOutcome.Skipped(uriString, "MPF index disagrees with the EOI walk")
            }
        }

        val rewritten = try {
            JpegRewriter.rewrite(original, structure, plan)
        } catch (e: Exception) {
            Log.w(TAG, "Rewrite failed for $uriString", e)
            return CompressionOutcome.Skipped(uriString, "rewrite failed")
        }

        // Self-check before touching the file: if our own output does not parse,
        // the bug is ours and the user's photo stays untouched.
        if (JpegParser.parseFull(rewritten) == null) {
            return CompressionOutcome.Skipped(uriString, "rewritten bytes failed the self-check")
        }

        val backup = File(backupDir, "${UUID.randomUUID()}.bak")
        try {
            writeBackup(backup, original)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a backup for $uriString", e)
            backup.delete()
            return CompressionOutcome.Skipped(uriString, "could not create a backup")
        }

        journalDao.insert(
            CompressionJournalEntity(
                mediaUri = uriString,
                backupPath = backup.absolutePath,
                originalSize = original.size.toLong(),
                startedAt = System.currentTimeMillis()
            )
        )

        try {
            io.overwrite(uriString, rewritten)

            val readBack = io.readAll(uriString)
            if (!readBack.contentEquals(rewritten)) {
                // Catches a short or torn write. This is why verification
                // compares bytes rather than re-reading EXIF: EXIF sits at the
                // front of the file and survives a truncation that destroys the
                // image.
                return restore(uriString, backup, "read-back did not match what was written")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed for $uriString", e)
            return restore(uriString, backup, e.message ?: "write failed")
        }

        // Committed. Release the backup last -- while it exists, the file is
        // recoverable no matter what happens.
        releaseBackup(uriString, backup)
        return CompressionOutcome.Compressed(uriString, plan.bytesSaved)
    }

    /**
     * Repairs anything left mid-write by a previous run.
     *
     * A row can only survive if the process died before the read-back passed, so
     * restoring is always the right move -- worst case it undoes a rewrite that
     * had in fact just succeeded, which costs the user nothing but a repeat run.
     *
     * Requires write access to the URIs. The caller is responsible for having
     * obtained it (see MainActivity's write-request effect); this method assumes
     * it and reports failure otherwise.
     */
    suspend fun recover(): List<CompressionOutcome> {
        val rows = journalDao.getAll()
        if (rows.isEmpty()) return emptyList()

        return rows.map { row ->
            val backup = File(row.backupPath)
            if (!backup.exists()) {
                // Nothing to restore from. Drop the row anyway: keeping it would
                // re-request write access on every launch forever with no way to
                // ever succeed.
                Log.w(TAG, "Journal row for ${row.mediaUri} has no backup file; dropping it")
                journalDao.deleteByUri(row.mediaUri)
                CompressionOutcome.Failed(row.mediaUri, "backup missing", restored = false)
            } else {
                restore(row.mediaUri, backup, "recovered an interrupted write")
            }
        }
    }

    /** Puts the original back, then releases the backup and the journal row. */
    private suspend fun restore(uriString: String, backup: File, reason: String): CompressionOutcome {
        return try {
            val bytes = withContext(Dispatchers.IO) { backup.readBytes() }
            io.overwrite(uriString, bytes)
            releaseBackup(uriString, backup)
            CompressionOutcome.Failed(uriString, reason, restored = true)
        } catch (e: Exception) {
            // The backup stays on disk and the journal row stays put, so the next
            // launch tries again. This is the one path that must NOT clean up.
            Log.e(TAG, "Could not restore $uriString from ${backup.absolutePath}", e)
            CompressionOutcome.Failed(uriString, "restore failed: ${e.message}", restored = false)
        }
    }

    private suspend fun releaseBackup(uriString: String, backup: File) {
        withContext(Dispatchers.IO) { backup.delete() }
        journalDao.deleteByUri(uriString)
    }

    private suspend fun writeBackup(backup: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        backup.parentFile?.mkdirs()
        FileOutputStream(backup).use { out ->
            out.write(bytes)
            out.flush()
            // An unsynced backup is not a backup: the very crash it exists to
            // survive is the one that would lose it.
            out.fd.sync()
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.PhotoCompressorTest"
```

Expected: `BUILD SUCCESSFUL`, 9 tests passing.

`android.util.Log` is referenced but is a JVM stub returning 0 in unit tests, which is fine — no test asserts on logging. If the build fails with `Method ... not mocked`, add `testOptions { unitTests.isReturnDefaultValues = true }` to `app/build.gradle.kts`'s `android` block and note it in the commit.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/MediaFileIo.kt app/src/main/java/com/sese/keepix/utils/PhotoCompressor.kt app/src/test/java/com/sese/keepix/utils/PhotoCompressorTest.kt
```

Message body:

```
feat(compress): add the crash-safe per-file compressor

Every rejection happens before the file is opened for writing, the fsynced
backup and journal row exist before the first byte lands, and the write is
verified by reading the file back and comparing bytes -- which catches a short
write that an EXIF round-trip would miss, since EXIF sits at the front of the
file and survives a truncation that destroys the image.

An MPF index shorter than where the EOI walk ended means the two disagree about
where the image ends, and the file is skipped rather than truncated.

The MediaFileIo seam exists so all of this runs on the host JVM against injected
write failures.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 6: Library analyser

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/PhotoCompressionAnalyzer.kt`
- Test: `app/src/test/java/com/sese/keepix/utils/ReclaimEstimateTest.kt`

**Interfaces:**
- Consumes: `JpegParser.parseHeader`, `HeaderResult`, `MpfIndex`, `JpegRewriter.meetsSavingFloor`, `StripPlan` (Tasks 1–3); `KeptItemEntity`.
- Produces:
  - `data class ReclaimEstimate(scannedCount: Int, eligibleCount: Int, estimatedBytes: Long, eligibleUris: List<String>)`
  - `PhotoCompressionAnalyzer.estimateFromHeader(header: JpegHeader, bytes: ByteArray, fileSize: Long): Long` — pure, testable
  - `class PhotoCompressionAnalyzer(context: Context)` with
    `suspend fun analyze(items: List<KeptItemEntity>, onProgress: (Int, Int) -> Unit): ReclaimEstimate`

Only `mediaType == "IMAGE"` rows are considered. Videos are out of scope (spec §4).

- [ ] **Step 1: Write the failing test for the pure estimate**

Create `app/src/test/java/com/sese/keepix/utils/ReclaimEstimateTest.kt`:

```kotlin
package com.sese.keepix.utils

import com.sese.keepix.utils.jpeg.HeaderResult
import com.sese.keepix.utils.jpeg.JpegFixtures
import com.sese.keepix.utils.jpeg.JpegFixtures.app
import com.sese.keepix.utils.jpeg.JpegFixtures.concat
import com.sese.keepix.utils.jpeg.JpegFixtures.eoi
import com.sese.keepix.utils.jpeg.JpegFixtures.sof0
import com.sese.keepix.utils.jpeg.JpegFixtures.soi
import com.sese.keepix.utils.jpeg.JpegFixtures.sos
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ReclaimEstimateTest {

    @Test
    fun estimateFromHeader_mpfPresent_countsTheTrailingImageAndTheIndexSegment() {
        val payload = JpegFixtures.mpfPayload(listOf(2_000_000L))
        val bytes = concat(
            // segment(), NOT app(): mpfPayload already begins with "MPF ", and app()
            // would prepend a second copy, leaving MpfIndex unable to read the index.
            soi(), JpegFixtures.segment(JpegMarkers.APP2, payload), sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        val mpfSegment = header.segments.single { it.identifier == "MPF" }

        val estimate = PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 2_500_000L)

        assertEquals(500_000L + mpfSegment.length, estimate)
    }

    @Test
    fun estimateFromHeader_noMpfSegment_estimatesZero() {
        val bytes = concat(soi(), app(JpegMarkers.APP1, "Exif", ByteArray(50)), sof0(), sos(byteArrayOf(1)), eoi())
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 1_000_000L))
    }

    @Test
    fun estimateFromHeader_unreadableIndex_estimatesZeroRatherThanGuessing() {
        val bytes = concat(
            soi(), app(JpegMarkers.APP2, "MPF", ByteArray(12)), sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 5_000_000L))
    }

    @Test
    fun estimateFromHeader_declaredSizeExceedsTheFile_estimatesZero() {
        // A nonsensical index must not produce a negative or inflated figure.
        val bytes = concat(
            soi(), JpegFixtures.segment(JpegMarkers.APP2, JpegFixtures.mpfPayload(listOf(9_000_000L))),
            sof0(), sos(byteArrayOf(1)), eoi()
        )
        val header = (JpegParser.parseHeader(bytes) as HeaderResult.Ok).header
        assertEquals(0L, PhotoCompressionAnalyzer.estimateFromHeader(header, bytes, fileSize = 1_000_000L))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.ReclaimEstimateTest"
```

Expected: `Unresolved reference: PhotoCompressionAnalyzer`.

- [ ] **Step 3: Write the analyser**

Create `app/src/main/java/com/sese/keepix/utils/PhotoCompressionAnalyzer.kt`:

```kotlin
package com.sese.keepix.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sese.keepix.db.KeptItemEntity
import com.sese.keepix.utils.jpeg.HeaderResult
import com.sese.keepix.utils.jpeg.JpegHeader
import com.sese.keepix.utils.jpeg.JpegMarkers
import com.sese.keepix.utils.jpeg.JpegParser
import com.sese.keepix.utils.jpeg.JpegRewriter
import com.sese.keepix.utils.jpeg.MpfIndex
import com.sese.keepix.utils.jpeg.StripPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.coroutineContext

private const val TAG = "CompressionAnalyzer"

/** How much of a file is read per attempt while looking for the SOS. */
private const val HEADER_CHUNK_BYTES = 64 * 1024

/** Hard cap on the header region. Beyond this the file is treated as ineligible. */
private const val MAX_HEADER_BYTES = 1024 * 1024

/**
 * @param scannedCount files actually opened and parsed.
 * @param eligibleCount files that clear the saving floor.
 * @param estimatedBytes total reclaimable bytes across [eligibleUris]. An
 *   estimate, derived from each file's MPF index rather than a full walk.
 * @param eligibleUris the URIs a compression run would target, in scan order.
 */
data class ReclaimEstimate(
    val scannedCount: Int,
    val eligibleCount: Int,
    val estimatedBytes: Long,
    val eligibleUris: List<String>
)

/**
 * Measures how much a compression run would reclaim, without writing anything
 * and without needing any permission beyond the read access the app already has.
 *
 * Reads only each file's HEADER -- 64 KB at a time, capped at 1 MB -- and takes
 * the primary image's length from the MPF index, which states it directly. A
 * full walk per file would be authoritative but unaffordable: a 2,000-photo
 * library is roughly 10 GB of reads. The figure is therefore an estimate, and
 * the UI says so; [PhotoCompressor] re-derives the real number authoritatively
 * before it writes anything.
 */
class PhotoCompressionAnalyzer(private val context: Context) {

    suspend fun analyze(
        items: List<KeptItemEntity>,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): ReclaimEstimate = withContext(Dispatchers.IO) {
        val photos = items.filter { it.mediaType == "IMAGE" }
        var scanned = 0
        var total = 0L
        val eligible = mutableListOf<String>()

        for (item in photos) {
            // Cooperative cancellation: the user can leave Settings mid-scan.
            coroutineContext.ensureActive()

            val estimate = try {
                estimateForUri(item.mediaUri)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${item.mediaUri} during analysis", e)
                null
            }
            scanned++
            onProgress(scanned, photos.size)

            if (estimate != null && estimate.second > 0) {
                val plan = StripPlan(emptySet(), 0, estimate.second.toInt(), 0)
                if (JpegRewriter.meetsSavingFloor(plan, estimate.first.toInt())) {
                    total += estimate.second
                    eligible += item.mediaUri
                }
            }
        }

        ReclaimEstimate(scanned, eligible.size, total, eligible)
    }

    /** Returns (fileSize, estimatedSavings) or null if the file is not usable. */
    private fun estimateForUri(uriString: String): Pair<Long, Long>? {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver

        val fileSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        if (fileSize <= 0) return null

        resolver.openInputStream(uri)?.use { input ->
            val header = readHeader(input) ?: return null
            return fileSize to estimateFromHeader(header.first, header.second, fileSize)
        }
        return null
    }

    /**
     * Reads growing prefixes until the marker chain reaches the SOS. Returns the
     * parsed header and the bytes it was parsed from, or null if the file is not
     * a JPEG or its header exceeds [MAX_HEADER_BYTES].
     */
    private fun readHeader(input: InputStream): Pair<JpegHeader, ByteArray>? {
        var buffer = ByteArray(HEADER_CHUNK_BYTES)
        var filled = 0
        while (true) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read <= 0) return null   // EOF before a SOS
            filled += read

            when (val result = JpegParser.parseHeader(buffer, filled)) {
                is HeaderResult.Ok -> return result.header to buffer
                HeaderResult.Malformed -> return null
                HeaderResult.NeedMoreBytes -> {
                    if (filled == buffer.size) {
                        if (buffer.size >= MAX_HEADER_BYTES) return null
                        buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_HEADER_BYTES))
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Reclaimable bytes for one file, from its header alone: everything after
         * the primary image (the MPF secondary), plus the index segment that
         * describes it.
         *
         * Returns 0 whenever the index is absent, unreadable, or nonsensical.
         * Under-reporting makes the feature look less useful than it is;
         * over-reporting promises space that is not there. Zero is the honest
         * answer to "I could not tell".
         */
        fun estimateFromHeader(header: JpegHeader, bytes: ByteArray, fileSize: Long): Long {
            val mpf = header.segments.firstOrNull {
                it.marker == JpegMarkers.APP2 && it.identifier == "MPF"
            } ?: return 0L

            val declaredPrimarySize = MpfIndex.readPrimaryImageSize(bytes, mpf) ?: return 0L
            if (declaredPrimarySize <= 0 || declaredPrimarySize > fileSize) return 0L

            val trailing = fileSize - declaredPrimarySize
            return trailing + mpf.length
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.utils.ReclaimEstimateTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/PhotoCompressionAnalyzer.kt app/src/test/java/com/sese/keepix/utils/ReclaimEstimateTest.kt
```

Message body:

```
feat(compress): estimate reclaimable space from file headers

Reads 64 KB at a time, capped at 1 MB, and takes the primary image's length from
the MPF index rather than walking each file to its EOI -- a full walk across a
2,000-photo library would be roughly 10 GB of reads. Writes nothing and needs no
permission the app does not already hold.

Returns zero whenever the index is absent or nonsensical: over-reporting would
promise space that is not there.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 7: ViewModel compression state machine

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/KeepixViewModel.kt`
- Test: `app/src/test/java/com/sese/keepix/ui/CompressionStateMachineTest.kt`
- Modify: `app/src/test/java/com/sese/keepix/testutil/ViewModelTestHarness.kt`

**Interfaces:**
- Consumes: `PhotoCompressor`, `CompressionOutcome`, `PhotoCompressionAnalyzer`, `ReclaimEstimate`, `ContentResolverMediaFileIo` (Tasks 5–6); `CompressionJournalDao` (Task 4).
- Produces on `KeepixViewModel`:
  - `enum class WriteRequestKind { RECOVERY, COMPRESSION }`
  - `data class PendingWriteRequest(val kind: WriteRequestKind, val uris: List<String>)`
  - `val pendingWrite: StateFlow<PendingWriteRequest?>`
  - `val compressionEstimate: StateFlow<ReclaimEstimate?>`
  - `val compressionScanProgress: StateFlow<Pair<Int, Int>?>`
  - `val compressionStatus: StateFlow<String?>`
  - `fun scanForReclaimableSpace()`, `fun cancelCompressionScan()`
  - `fun requestCompression()`
  - `fun onWriteGranted()`, `fun onWriteDenied()`
  - `fun checkForInterruptedCompressions()`
  - `fun discardInterruptedWrites(uris: List<String>)`

**The priority rule this task establishes:** `_pendingWrite` holds at most one request. `checkForInterruptedCompressions()` runs from `init` and sets a `RECOVERY` request if the journal is non-empty. `requestCompression()` refuses while `_pendingWrite != null`. Recovery therefore always precedes new work, without a second competing effect in `MainActivity`.

- [ ] **Step 1: Add the new fields to the test harness**

In `app/src/test/java/com/sese/keepix/testutil/ViewModelTestHarness.kt`, extend `newViewModel(...)`. Add the parameter:

```kotlin
    fun newViewModel(
        repository: MediaRepository = mockk(relaxed = true),
        binItemDao: BinItemDao = mockk(relaxed = true),
        keptItemDao: KeptItemDao = mockk(relaxed = true),
        prefs: KeepixPreferences = fakePrefs(),
        photoCompressor: PhotoCompressor = mockk(relaxed = true)
    ): KeepixViewModel {
```

and, immediately before `return vm`, wire the compression fields. Each public `StateFlow` is a separate backing field from its private `MutableStateFlow` and its property initializer never ran, so both must be set to the same instance — exactly as `favoritePromptedThisSession` already is:

```kotlin
        setField(vm, "photoCompressor", photoCompressor)
        val pendingWrite = MutableStateFlow<KeepixViewModel.PendingWriteRequest?>(null)
        setField(vm, "_pendingWrite", pendingWrite)
        setField(vm, "pendingWrite", pendingWrite.asStateFlow())
        val estimate = MutableStateFlow<ReclaimEstimate?>(null)
        setField(vm, "_compressionEstimate", estimate)
        setField(vm, "compressionEstimate", estimate.asStateFlow())
        val progress = MutableStateFlow<Pair<Int, Int>?>(null)
        setField(vm, "_compressionScanProgress", progress)
        setField(vm, "compressionScanProgress", progress.asStateFlow())
        val status = MutableStateFlow<String?>(null)
        setField(vm, "_compressionStatus", status)
        setField(vm, "compressionStatus", status.asStateFlow())
        setField<Any?>(vm, "scanJob", null)
```

Add the imports `com.sese.keepix.ui.KeepixViewModel`, `com.sese.keepix.utils.PhotoCompressor` and `com.sese.keepix.utils.ReclaimEstimate` at the top of that file.

- [ ] **Step 2: Write the failing state-machine test**

Create `app/src/test/java/com/sese/keepix/ui/CompressionStateMachineTest.kt`:

```kotlin
package com.sese.keepix.ui

import com.sese.keepix.testutil.ViewModelTestHarness
import com.sese.keepix.utils.CompressionOutcome
import com.sese.keepix.utils.PhotoCompressor
import com.sese.keepix.utils.ReclaimEstimate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The compression request/grant state machine, exercised through the real
 * KeepixViewModel methods via [ViewModelTestHarness] -- see that file for why a
 * real constructor call is not viable on the host JVM.
 *
 * The property under test throughout is that a MediaStore write only ever
 * happens after an explicit grant, and that recovery of an interrupted write
 * always takes precedence over starting new work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompressionStateMachineTest {

    private lateinit var compressor: PhotoCompressor

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        compressor = mockk(relaxed = true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun estimate(vararg uris: String) =
        ReclaimEstimate(uris.size, uris.size, 1_000_000L, uris.toList())

    @Test
    fun requestCompression_armsAPendingWriteButCompressesNothingYet() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")

        vm.requestCompression()

        val pending = vm.pendingWrite.value
        assertNotNull(pending)
        assertEquals(KeepixViewModel.WriteRequestKind.COMPRESSION, pending!!.kind)
        assertEquals(listOf("uri://a", "uri://b"), pending.uris)
        coVerify(exactly = 0) { compressor.compress(any()) }
    }

    @Test
    fun onWriteGranted_compressionKind_compressesEveryUriInSequence() = runTest {
        coEvery { compressor.compress(any()) } answers {
            CompressionOutcome.Compressed(firstArg(), 100_000)
        }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a", "uri://b")
        vm.requestCompression()

        vm.onWriteGranted()

        coVerify(exactly = 1) { compressor.compress("uri://a") }
        coVerify(exactly = 1) { compressor.compress("uri://b") }
        assertNull("the request must clear once it has run", vm.pendingWrite.value)
    }

    @Test
    fun onWriteDenied_writesNothingAndClearsTheRequest() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a")
        vm.requestCompression()

        vm.onWriteDenied()

        coVerify(exactly = 0) { compressor.compress(any()) }
        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun onWriteGranted_recoveryKind_recoversAndDoesNotCompress() = runTest {
        coEvery { compressor.recover() } returns
            listOf(CompressionOutcome.Failed("uri://x", "recovered", restored = true))
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
            vm, "_pendingWrite"
        ).value = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x")
        )

        vm.onWriteGranted()

        coVerify(exactly = 1) { compressor.recover() }
        coVerify(exactly = 0) { compressor.compress(any()) }
        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun requestCompression_yieldsWhileARecoveryIsPending() = runTest {
        // Recovery repairs a possibly-damaged file; starting new rewrites while
        // one is outstanding would queue a second dialog behind the first and
        // Android would silently drop one of them.
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        val pendingField = ViewModelTestHarness
            .getField<kotlinx.coroutines.flow.MutableStateFlow<KeepixViewModel.PendingWriteRequest?>>(
                vm, "_pendingWrite"
            )
        pendingField.value = KeepixViewModel.PendingWriteRequest(
            KeepixViewModel.WriteRequestKind.RECOVERY, listOf("uri://x")
        )
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = estimate("uri://a")

        vm.requestCompression()

        assertEquals(
            "a pending recovery must not be replaced by a compression request",
            KeepixViewModel.WriteRequestKind.RECOVERY,
            pendingField.value!!.kind
        )
    }

    @Test
    fun requestCompression_withNoEligibleFiles_armsNothing() = runTest {
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = ReclaimEstimate(10, 0, 0L, emptyList())

        vm.requestCompression()

        assertNull(vm.pendingWrite.value)
    }

    @Test
    fun onWriteGranted_capsOneRunAtTheBatchLimit() = runTest {
        coEvery { compressor.compress(any()) } answers {
            CompressionOutcome.Compressed(firstArg(), 100_000)
        }
        val many = (1..120).map { "uri://$it" }
        val vm = ViewModelTestHarness.newViewModel(photoCompressor = compressor)
        ViewModelTestHarness.getField<kotlinx.coroutines.flow.MutableStateFlow<ReclaimEstimate?>>(
            vm, "_compressionEstimate"
        ).value = ReclaimEstimate(many.size, many.size, 1L, many)

        vm.requestCompression()
        assertEquals(MAX_COMPRESSION_BATCH, vm.pendingWrite.value!!.uris.size)

        vm.onWriteGranted()
        coVerify(exactly = MAX_COMPRESSION_BATCH) { compressor.compress(any()) }
    }
}
```

- [ ] **Step 3: Run to verify failure**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest --tests "com.sese.keepix.ui.CompressionStateMachineTest"
```

Expected: `Unresolved reference: pendingWrite` / `requestCompression`.

- [ ] **Step 4: Add the state machine to the ViewModel**

In `app/src/main/java/com/sese/keepix/ui/KeepixViewModel.kt`, add these imports:

```kotlin
import com.sese.keepix.utils.ContentResolverMediaFileIo
import com.sese.keepix.utils.PhotoCompressionAnalyzer
import com.sese.keepix.utils.PhotoCompressor
import com.sese.keepix.utils.ReclaimEstimate
import kotlinx.coroutines.Job
import java.io.File
```

Add this top-level constant just below the existing `private const val TAG = "KeepixViewModel"`:

```kotlin
/**
 * URIs per compression run. `createWriteRequest` takes a collection and the user
 * confirms once, but each confirmed file is then rewritten one at a time, so a
 * run's real cost is bytes moved, not dialogs shown. 50 files of a few MB each is
 * a few hundred MB of I/O -- enough to be worth doing, small enough that a user
 * who changes their mind has not committed to an hour of work.
 */
const val MAX_COMPRESSION_BATCH = 50
```

Add the collaborators alongside the existing `repository` / DAO properties:

```kotlin
    private val compressionJournalDao = AppDatabase.getDatabase(application).compressionJournalDao()

    private val photoCompressor = PhotoCompressor(
        io = ContentResolverMediaFileIo(application),
        journalDao = compressionJournalDao,
        backupDir = File(application.filesDir, "compression_backups")
    )

    private val compressionAnalyzer = PhotoCompressionAnalyzer(application)
```

Append this section at the end of the class, after `rearmFavoritePrompt()`:

```kotlin
    // Compression state

    enum class WriteRequestKind { RECOVERY, COMPRESSION }

    /**
     * One outstanding `createWriteRequest`. At most one exists at a time, which
     * is what enforces "recovery before new work" without a second Activity
     * effect competing for the shared system-dialog gate: [requestCompression]
     * refuses while this is non-null, and [checkForInterruptedCompressions] sets
     * it from `init`, before any user action can.
     */
    data class PendingWriteRequest(val kind: WriteRequestKind, val uris: List<String>)

    private val _pendingWrite = MutableStateFlow<PendingWriteRequest?>(null)
    val pendingWrite: StateFlow<PendingWriteRequest?> = _pendingWrite.asStateFlow()

    private val _compressionEstimate = MutableStateFlow<ReclaimEstimate?>(null)
    val compressionEstimate: StateFlow<ReclaimEstimate?> = _compressionEstimate.asStateFlow()

    /** (scanned, total) while a scan runs; null otherwise. */
    private val _compressionScanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val compressionScanProgress: StateFlow<Pair<Int, Int>?> = _compressionScanProgress.asStateFlow()

    /** Human-readable result of the last run, for the Settings screen. */
    private val _compressionStatus = MutableStateFlow<String?>(null)
    val compressionStatus: StateFlow<String?> = _compressionStatus.asStateFlow()

    private var scanJob: Job? = null

    /**
     * Repairs anything a previous run left mid-write. Called from `init`, so a
     * recovery request is always armed before the user can arm a compression one.
     *
     * The restore itself needs write access, so this only ARMS the request; the
     * Activity obtains the grant and calls [onWriteGranted]. If the app already
     * holds write access for those URIs the system resolves the request without
     * showing anything, so the usual case is invisible.
     */
    fun checkForInterruptedCompressions() {
        viewModelScope.launch {
            try {
                val rows = compressionJournalDao.getAll()
                if (rows.isEmpty()) return@launch
                Log.w(TAG, "Found ${rows.size} interrupted rewrite(s); arming recovery")
                _pendingWrite.value = PendingWriteRequest(
                    WriteRequestKind.RECOVERY, rows.map { it.mediaUri }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the compression journal", e)
            }
        }
    }

    /**
     * Releases journal rows whose media file is confirmed gone.
     *
     * Without this, a row for a photo the user deleted elsewhere would re-arm a
     * recovery request on every single launch, forever, with no way to ever
     * succeed -- the file it wants to restore does not exist. Mirrors how the
     * favorite effect calls [confirmFavoriteSync] for its own missing URIs.
     *
     * Only ever called with URIs [MediaUriFilter] has *proven* absent, never with
     * ones it merely could not verify.
     */
    fun discardInterruptedWrites(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                val rows = compressionJournalDao.getAll().filter { it.mediaUri in uris.toSet() }
                for (row in rows) {
                    withContext(Dispatchers.IO) { File(row.backupPath).delete() }
                    compressionJournalDao.deleteByUri(row.mediaUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not discard journal rows for missing media", e)
            }
        }
    }

    /** Measures reclaimable space across the kept library. Writes nothing. */
    fun scanForReclaimableSpace() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _compressionStatus.value = null
            _compressionScanProgress.value = 0 to 0
            try {
                val estimate = compressionAnalyzer.analyze(keptItems.value) { scanned, total ->
                    _compressionScanProgress.value = scanned to total
                }
                _compressionEstimate.value = estimate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                _error.value = "Couldn't measure reclaimable space."
            } finally {
                _compressionScanProgress.value = null
            }
        }
    }

    fun cancelCompressionScan() {
        scanJob?.cancel()
        scanJob = null
        _compressionScanProgress.value = null
    }

    /**
     * Arms a write request for the eligible files found by the last scan. Writes
     * nothing: the actual rewrites wait for [onWriteGranted].
     */
    fun requestCompression() {
        // Never displace a pending recovery. A second IntentSender launched while
        // one is outstanding is silently dropped by Android, and the loser's work
        // would then wait forever behind a latched guard -- the exact shape that
        // caused three defects in the earlier correctness pass.
        if (_pendingWrite.value != null) return

        val uris = _compressionEstimate.value?.eligibleUris.orEmpty().take(MAX_COMPRESSION_BATCH)
        if (uris.isEmpty()) return
        _pendingWrite.value = PendingWriteRequest(WriteRequestKind.COMPRESSION, uris)
    }

    /**
     * The system granted write access. Runs the request that was armed.
     *
     * Files are rewritten strictly one at a time. The confirmation batches; the
     * writes do not -- at most one file may ever be mid-write, which is what
     * makes the journal's single-row recovery sufficient.
     */
    fun onWriteGranted() {
        val request = _pendingWrite.value ?: return
        viewModelScope.launch {
            try {
                when (request.kind) {
                    WriteRequestKind.RECOVERY -> {
                        val outcomes = photoCompressor.recover()
                        val restored = outcomes.count { it is CompressionOutcome.Failed && it.restored }
                        if (restored > 0) {
                            _compressionStatus.value =
                                "Restored $restored photo${if (restored == 1) "" else "s"} after an interrupted optimization."
                        }
                    }
                    WriteRequestKind.COMPRESSION -> {
                        var compressed = 0
                        var saved = 0L
                        var failed = 0
                        for (uri in request.uris) {
                            when (val outcome = photoCompressor.compress(uri)) {
                                is CompressionOutcome.Compressed -> {
                                    compressed++
                                    saved += outcome.bytesSaved
                                }
                                is CompressionOutcome.Failed -> failed++
                                is CompressionOutcome.Skipped -> Unit
                            }
                        }
                        _compressionStatus.value = buildString {
                            append("Optimized $compressed photo${if (compressed == 1) "" else "s"}")
                            append(", reclaiming ${saved / (1024 * 1024)} MB")
                            if (failed > 0) append(". $failed could not be changed and were left as they were")
                            append(".")
                        }
                        // The estimate is now stale by construction.
                        _compressionEstimate.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Compression run failed", e)
                _error.value = "Something went wrong while optimizing. Your photos were left unchanged."
            } finally {
                _pendingWrite.value = null
            }
        }
    }

    /**
     * The user declined, or the request could not be launched. Nothing was
     * written and no backup exists yet -- the grant is obtained before the first
     * backup, so a decline leaves the file system exactly as it was.
     */
    fun onWriteDenied() {
        _pendingWrite.value = null
    }
```

Add `import com.sese.keepix.utils.CompressionOutcome`, `import kotlinx.coroutines.CancellationException` and `import kotlinx.coroutines.withContext` alongside the others.

Note for the implementer: `scanForReclaimableSpace()` reads `keptItems.value`, which is a `stateIn` property whose initializer never runs under `ViewModelTestHarness` (see that file's doc). No test in this task exercises the scan, so this does not bite — but a test that does must wire `keptItems` itself first.

Finally, call the recovery check from `init`, immediately after the existing `performLaunchCleanup()` call:

```kotlin
        checkForInterruptedCompressions()
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, with the 7 new `CompressionStateMachineTest` cases passing and every pre-existing test still green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/KeepixViewModel.kt app/src/test/java/com/sese/keepix/ui/CompressionStateMachineTest.kt app/src/test/java/com/sese/keepix/testutil/ViewModelTestHarness.kt
```

Message body:

```
feat(vm): add the compression scan/request/grant state machine

A single pendingWrite slot holds at most one outstanding write request, which is
what makes "recovery before new work" true without adding a second Activity
effect to race the shared system-dialog gate: init arms recovery before any user
action can arm a compression run, and requestCompression yields while one is
outstanding.

The confirmation batches; the writes serialise. At most one file is ever
mid-write, which is what makes single-row journal recovery sufficient.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 8: Write request in MainActivity, and the privacy policy

**Files:**
- Create: `app/src/main/java/com/sese/keepix/utils/MediaWriteHandler.kt`
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt`
- Modify: `app/src/main/java/com/sese/keepix/ui/PrivacyPolicyScreen.kt`
- Modify: `PRIVACY.md`

**Interfaces:**
- Consumes: `viewModel.pendingWrite`, `viewModel.onWriteGranted()`, `viewModel.onWriteDenied()`, `viewModel.discardInterruptedWrites(List<String>)`, `KeepixViewModel.WriteRequestKind` (Task 7); `MediaUriFilter.filterExistingUris` (existing). `com.sese.keepix.ui.*` is already imported, so the nested `WriteRequestKind` needs no new import.
- Produces: `MediaWriteHandler.getWriteIntent(context: Context, uris: List<Uri>): PendingIntent`

**This task changes what the app is allowed to claim.** The moment it can write file contents, `PRIVACY.md`'s "No media files are copied, uploaded, or transmitted" and the screen's identical line become false. Both change here, in the same commit as the capability, not afterwards.

**The gate:** `MainActivity` already serialises deletion and favorite prompts through one `systemDialogInFlight` flag, because Android shows one `IntentSender` and silently drops the other. This adds a third prompt to that same gate, at the lowest priority. Deletion and favorite both destroy or change user-visible state on a timer; compression is entirely user-initiated and can always wait.

- [ ] **Step 1: Write the write-request handler**

Create `app/src/main/java/com/sese/keepix/utils/MediaWriteHandler.kt`:

```kotlin
package com.sese.keepix.utils

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The only way this app obtains permission to write a media file's contents.
 *
 * Mirrors [MediaFavoriteHandler] and [MediaDeletionHandler]: the app does not own
 * these MediaStore rows, so writing requires [MediaStore.createWriteRequest] and
 * the system confirmation it produces. Nothing is written until that returns
 * `RESULT_OK`.
 *
 * Unlike `createFavoriteRequest`, this carries no per-batch mode flag, so one
 * request can cover a whole run.
 */
object MediaWriteHandler {

    /**
     * @param uris must be non-empty and already filtered by
     *   [MediaUriFilter.filterExistingUris]; a URI whose row no longer exists
     *   makes the whole request fail.
     */
    fun getWriteIntent(context: Context, uris: List<Uri>): PendingIntent {
        require(uris.isNotEmpty()) { "getWriteIntent requires a non-empty URI list" }
        return MediaStore.createWriteRequest(context.contentResolver, uris)
    }
}
```

- [ ] **Step 2: Add the write prompt to MainActivity**

In `app/src/main/java/com/sese/keepix/MainActivity.kt`, add the import:

```kotlin
import com.sese.keepix.utils.MediaWriteHandler
```

Just above the favorite `LaunchedEffect`, add the symmetric armed flag for the favorite prompt, next to the existing `deletionPromptArmed`:

```kotlin
    // Mirror of deletionPromptArmed, for the compression effect below to yield
    // to. Derived to a Boolean for the same reason: as a key, the list itself
    // would restart an in-progress pass whenever the pending set's content
    // changed, where only the armed/not-armed transition matters.
    val favoritePromptArmed = pendingFavoriteSync.isNotEmpty() && !favoritePromptedThisSession
```

Then, after the favorite `LaunchedEffect` closes, add:

```kotlin
    val pendingWrite by viewModel.pendingWrite.collectAsState()

    // rememberSaveable for the same reason as deletionInFlightIds: an Activity
    // recreated while the write dialog is showing comes back with the
    // plain-remember gate reset to false, and without this the effect would
    // launch a second request behind the one still on screen.
    var writeRequestInFlight by rememberSaveable { mutableStateOf(false) }

    val writeResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        writeRequestInFlight = false
        systemDialogInFlight = false
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onWriteGranted()
        } else {
            // Nothing was written and no backup exists yet: the grant is
            // obtained before the first backup, so a decline leaves the file
            // system exactly as it was.
            viewModel.onWriteDenied()
        }
    }

    // The third and lowest-priority claimant of the shared system-dialog gate.
    // Deletion and favorite both act on state the user already committed to and
    // that changes on a timer; a compression run is entirely user-initiated and
    // can always wait for the next pass. Yielding to both is what keeps this
    // from becoming a coin flip between three effects that all suspend in
    // filterExistingUris.
    LaunchedEffect(
        pendingWrite, hasPermission, resumeTick, systemDialogInFlight,
        deletionPromptArmed, favoritePromptArmed
    ) {
        if (!hasPermission) return@LaunchedEffect
        val request = pendingWrite ?: return@LaunchedEffect
        if (systemDialogInFlight || writeRequestInFlight) return@LaunchedEffect
        if (deletionInFlightIds != null || deletionPromptArmed) return@LaunchedEffect
        if (favoriteInFlightIds != null || favoritePromptArmed) return@LaunchedEffect
        // Cheap pre-filter bail-out, not the check that makes the launch safe.
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }

        val uris = request.uris.map { Uri.parse(it) }
        val filterResult = MediaUriFilter.filterExistingUris(
            context, uris, hasOnlyPartialMediaAccess = hasOnlyPartialMediaAccess(context)
        )
        // A recovery row whose file is CONFIRMED gone can never be restored, and
        // leaving it would re-arm this request on every launch forever. Release
        // those rows and their backups. Only proven-absent URIs qualify --
        // filterExistingUris routes anything it merely could not verify to
        // `existing`, and those are still worth attempting.
        //
        // Not done for a COMPRESSION request: nothing is journalled yet at this
        // point, so a vanished file needs no cleanup and is simply dropped from
        // the batch by the filter.
        if (request.kind == KeepixViewModel.WriteRequestKind.RECOVERY &&
            filterResult.missing.isNotEmpty()
        ) {
            viewModel.discardInterruptedWrites(filterResult.missing.map { it.toString() })
        }

        val sendable = filterResult.existing
        if (sendable.isEmpty()) {
            // Nothing left to ask about. Clear the request; for a recovery the
            // rows were just released above, so this cannot re-arm.
            viewModel.onWriteDenied()
            return@LaunchedEffect
        }

        // Re-check RESUMED and the gate after the suspension above, in the same
        // position and for the same reason as the deletion and favorite effects:
        // filterExistingUris does one blocking ContentResolver query per URI, so
        // the app can easily be backgrounded during it, and both this effect and
        // the other two suspend there. resumeTick re-fires this effect on
        // foreground return, so the request is simply retried.
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }
        if (systemDialogInFlight || writeRequestInFlight) return@LaunchedEffect

        try {
            val intent = MediaWriteHandler.getWriteIntent(context, sendable)
            writeRequestInFlight = true
            systemDialogInFlight = true
            writeResultLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        } catch (e: Exception) {
            // createWriteRequest is a synchronous call into MediaProvider and can
            // throw; an uncaught throw here would kill the process. Release the
            // gate wherever the flag is released, or a throw would latch it and
            // kill all three prompts.
            Log.e(TAG, "Failed to build/launch the system write request", e)
            writeRequestInFlight = false
            systemDialogInFlight = false
            viewModel.onWriteDenied()
            viewModel.reportError("Couldn't open the write confirmation. Please try again.")
        }
    }
```

- [ ] **Step 3: Update the privacy policy screen — the source of truth**

In `app/src/main/java/com/sese/keepix/ui/PrivacyPolicyScreen.kt`, replace the sentence in section 1:

```kotlin
                BodyText("All decisions happen on your device. No media files are copied, uploaded, or transmitted.")
```

with:

```kotlin
                BodyText("All decisions happen on your device. Nothing is ever uploaded or transmitted.")
```

Then, in section 4, after the existing favorite paragraph, add:

```kotlin
                BodyText(
                    "Optimizing a photo rewrites that file on your device to remove " +
                        "redundant data some cameras embed alongside the picture — a " +
                        "duplicate second copy of the shot. The image itself is not " +
                        "altered: every pixel is copied across untouched, along with " +
                        "the date, orientation, location and colour profile. Android " +
                        "shows you a confirmation dialog first, and nothing is written " +
                        "unless you confirm. Keepix keeps its own copy of the original " +
                        "until it has read the result back and checked it, and restores " +
                        "the original if anything goes wrong."
                )
```

And in section 3, replace:

```kotlin
                BodyText("These are read-only. Keepix never requests WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE.")
```

with:

```kotlin
                BodyText(
                    "Keepix never requests WRITE_EXTERNAL_STORAGE or " +
                        "MANAGE_EXTERNAL_STORAGE. Deleting, favoriting and optimizing " +
                        "each go through Android's own confirmation dialog instead, one " +
                        "file set at a time, and only ever affect files you selected."
                )
```

- [ ] **Step 4: Mirror the same changes into `PRIVACY.md`**

Bump the header:

```markdown
**Effective Date:** 2026-07-09  
**Last Updated:** 2026-09-03  
**Version:** 1.2
```

In section 1, replace the closing line with:

```markdown
All decisions happen on your device. Nothing is ever uploaded or transmitted.
```

In section 3, replace the paragraph beginning "These are **read-only**" with:

```markdown
Keepix never requests `WRITE_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`. Deleting, favoriting and optimizing each go through Android's own confirmation dialog instead, one file set at a time, and only ever affect files you selected. Keepix's minimum supported Android version is API 30 (Android 11); Android 10 (API 29) and earlier are no longer supported.
```

In section 4, add a `compression_journal` row to the table:

```markdown
| `compression_journal` | `mediaUri`, `backupPath`, `originalSize`, `startedAt` | Tracks a photo optimization that is mid-write, so an interrupted one can be undone |
```

and, after the existing favorite paragraph, add:

```markdown
Optimizing a photo rewrites that file on your device to remove redundant data some cameras embed alongside the picture — a duplicate second copy of the shot. The image itself is not altered: every pixel is copied across untouched, along with the date, orientation, location and colour profile. Android shows you a confirmation dialog first, and nothing is written unless you confirm. Keepix keeps its own copy of the original in app-private storage until it has read the result back and checked it, and restores the original if anything goes wrong. That copy is deleted as soon as the result is verified.
```

Also update the "**No media bytes are stored.**" line, which is no longer strictly true while a backup exists:

```markdown
**No media bytes are stored long-term.** Only URIs and metadata — except for the brief window during an optimization, when one original is held in app-private storage until the result is verified.
```

- [ ] **Step 5: Verify the two documents agree**

This is a manual read-through, not a command. Open both and confirm every claim matches. The screen is the source of truth; if they differ, `PRIVACY.md` is wrong. A stale claim on the screen is a false statement shown to users.

- [ ] **Step 6: Build and run the full check**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest lintDebug
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sese/keepix/utils/MediaWriteHandler.kt app/src/main/java/com/sese/keepix/MainActivity.kt app/src/main/java/com/sese/keepix/ui/PrivacyPolicyScreen.kt PRIVACY.md
```

Message body:

```
feat(compress): request write access, and update the privacy claim

The write prompt is the third claimant of the shared systemDialogInFlight gate
and takes the lowest priority: deletion and favorite act on state the user
already committed to, while a compression run is user-initiated and can wait.
Same post-suspension RESUMED and gate re-checks as the other two, for the same
reason -- all three suspend in filterExistingUris.

The privacy claim changes in the same commit as the capability, not after it.
"No media files are copied or modified" stops being true the moment this ships,
and the policy screen is what users actually read.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

### Task 9: Settings entry point

**Files:**
- Modify: `app/src/main/java/com/sese/keepix/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/sese/keepix/MainActivity.kt` (the `SettingsScreen(...)` call site)

**Interfaces:**
- Consumes: `viewModel.compressionEstimate`, `.compressionScanProgress`, `.compressionStatus`, `.scanForReclaimableSpace()`, `.cancelCompressionScan()`, `.requestCompression()` (Task 7); `ReclaimEstimate` (Task 6).
- Produces: no new types.

The section reads as one of three states: nothing scanned yet, scanning, or a result. The figure is labelled as an estimate because it is one — it comes from each file's declared MPF index, not a full walk.

- [ ] **Step 1: Add the parameters**

In `app/src/main/java/com/sese/keepix/ui/SettingsScreen.kt`, add the import:

```kotlin
import com.sese.keepix.utils.ReclaimEstimate
```

and extend the signature, keeping `onBack` last as it already is:

```kotlin
fun SettingsScreen(
    currentRetentionDays: Int,
    binCount: Int,
    onRetentionChanged: (Int) -> Unit,
    onEmptyBin: () -> Unit,
    // True only on API 34+ when the user chose "Select photos…" instead of
    // "Allow all": the app functions normally against that reduced set (see
    // checkMediaPermission's doc in MainActivity.kt), but the swipe queue
    // will look incomplete unless the user understands why.
    hasOnlyPartialMediaAccess: Boolean = false,
    reclaimEstimate: ReclaimEstimate? = null,
    scanProgress: Pair<Int, Int>? = null,
    compressionStatus: String? = null,
    onScanForReclaimableSpace: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onOptimize: () -> Unit = {},
    onBack: () -> Unit
) {
```

Add one more local state declaration alongside the existing ones:

```kotlin
    var showOptimizeConfirmation by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the STORAGE section**

Insert this immediately after the BIN section's closing `Spacer(modifier = Modifier.height(24.dp))` and before the `// ABOUT section` comment:

```kotlin
                // STORAGE section
                Text(
                    text = "STORAGE",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentPurple,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Optimize photos",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Some cameras save a second copy of every shot inside the " +
                                "photo file. Removing it frees space without changing " +
                                "the picture — every pixel is kept exactly as it is.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        when {
                            scanProgress != null -> {
                                val (scanned, total) = scanProgress
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (total > 0) "Checking $scanned of $total…" else "Checking…",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = onCancelScan) {
                                        Text("Cancel", color = TextMuted)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (total > 0) scanned.toFloat() / total else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = AccentPurple
                                )
                            }

                            reclaimEstimate == null -> {
                                com.sese.keepix.ui.components.GlassButton(
                                    onClick = onScanForReclaimableSpace,
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 12.dp,
                                    tintColor = AccentPurple,
                                    tintAlpha = 0.2f
                                ) {
                                    Text("Check for reclaimable space")
                                }
                            }

                            reclaimEstimate.eligibleCount == 0 -> {
                                Text(
                                    "Nothing to reclaim — checked ${reclaimEstimate.scannedCount} " +
                                        "photo${if (reclaimEstimate.scannedCount == 1) "" else "s"}.",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            else -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Reclaimable space",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        // "About", because this is derived from each
                                        // file's declared index rather than a full
                                        // walk of every byte. The real figure is
                                        // computed per file at write time.
                                        "about ${reclaimEstimate.estimatedBytes / (1024 * 1024)} MB",
                                        color = KeepGreen,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "across ${reclaimEstimate.eligibleCount} " +
                                        "photo${if (reclaimEstimate.eligibleCount == 1) "" else "s"}",
                                    color = TextMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                com.sese.keepix.ui.components.GlassButton(
                                    onClick = { showOptimizeConfirmation = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 12.dp,
                                    tintColor = KeepGreen,
                                    tintAlpha = 0.2f
                                ) {
                                    Text("Optimize")
                                }
                            }
                        }

                        if (compressionStatus != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                compressionStatus,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
```

- [ ] **Step 3: Add the confirmation dialog**

Next to the existing `showEmptyConfirmation` `AlertDialog` at the bottom of the file, add:

```kotlin
    if (showOptimizeConfirmation) {
        AlertDialog(
            onDismissRequest = { showOptimizeConfirmation = false },
            containerColor = DarkSurface,
            title = { Text("Optimize photos?", color = TextPrimary) },
            text = {
                Text(
                    "Keepix will rewrite ${reclaimEstimate?.eligibleCount ?: 0} " +
                        "photo${if (reclaimEstimate?.eligibleCount == 1) "" else "s"} on " +
                        "your device to remove the duplicate copy stored inside each " +
                        "file. The picture itself does not change — every pixel, and " +
                        "the date, location and orientation, are kept exactly as they " +
                        "are.\n\nAndroid will ask you to confirm. Keepix keeps a copy " +
                        "of each original until it has checked the result.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptimizeConfirmation = false
                    onOptimize()
                }) {
                    Text("Optimize", color = KeepGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOptimizeConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
```

`DarkSurface` is what the existing `showEmptyConfirmation` dialog at `SettingsScreen.kt:300` uses, so the two match.

Note on `LinearProgressIndicator`: this project is on Compose BOM `2024.04.01` (Material3 1.2.1) and nothing in the app uses a progress indicator yet, so the overload is unverified. If `progress = { ... }` does not resolve, use the older `progress = if (total > 0) scanned.toFloat() / total else 0f` form instead. Do not add or bump a dependency for this.

- [ ] **Step 4: Wire it up in MainActivity**

At the `SettingsScreen(...)` call site in `MainActivity.kt`, collect the new state and pass it through:

```kotlin
            val reclaimEstimate by viewModel.compressionEstimate.collectAsState()
            val scanProgress by viewModel.compressionScanProgress.collectAsState()
            val compressionStatus by viewModel.compressionStatus.collectAsState()

            SettingsScreen(
                currentRetentionDays = retentionDays,
                binCount = binCount,
                onRetentionChanged = { /* existing */ },
                onEmptyBin = { /* existing */ },
                hasOnlyPartialMediaAccess = hasOnlyPartialMediaAccess,
                reclaimEstimate = reclaimEstimate,
                scanProgress = scanProgress,
                compressionStatus = compressionStatus,
                onScanForReclaimableSpace = { viewModel.scanForReclaimableSpace() },
                onCancelScan = { viewModel.cancelCompressionScan() },
                onOptimize = { viewModel.requestCompression() },
                onBack = { navController.popBackStack() }
            )
```

Keep the existing lambdas for `onRetentionChanged` and `onEmptyBin` exactly as they are — only the new arguments are added.

- [ ] **Step 5: Build and verify**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sese/keepix/ui/SettingsScreen.kt app/src/main/java/com/sese/keepix/MainActivity.kt
```

Message body:

```
feat(ui): add the Optimize photos entry point to Settings

Three states in one card: unscanned, scanning with a cancel, or a result. The
figure says "about" because it is derived from each file's declared MPF index
rather than a full walk -- the authoritative number is computed per file at
write time, and a promise the run cannot keep is worse than a vaguer one.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Verification

Full check, from the worktree root:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew compileDebugKotlin testDebugUnitTest assembleDebugAndroidTest lintDebug
```

If a build fails with no error output, the Gradle daemon is stale:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr" && ./gradlew --stop
```

Then re-run.

### On a device

Nothing in this feature has run on hardware. These are the checks that cannot be made on the JVM, in the order they should be run — 6 and 7 are the ones that matter most, because they are the failure modes the whole journal exists for.

1. **Scan a real library.** Settings → Optimize photos → Check for reclaimable space. Progress advances; the figure is plausible. On a phone with a dual-camera portrait mode it should be hundreds of MB.
2. **Optimize one photo.** Confirm the Android dialog. Open the photo in Google Photos and in a desktop viewer: identical appearance, date and orientation intact, no colour shift.
3. **Compare pixels.** Pull the file before and after (`adb pull`) and decode both. The decoded pixel data must be byte-identical. A visual match is not sufficient evidence here.
4. **Cancel the write confirmation.** Nothing changes, the journal stays empty, and no file appears under `files/compression_backups`.
5. **A photo with no MPF payload.** Reported as nothing to reclaim, and never rewritten.
6. **Kill the app mid-write.** Start a run over many photos and force-stop the app while it is working. Relaunch: the interrupted photo is restored, opens correctly, and the journal is empty.
7. **Deny the recovery write request.** Force an interrupted write as in 6, relaunch, and decline the Android dialog. The journal row and its backup must survive, and the next launch must offer recovery again. Nothing may be silently dropped.
8. **Compression alongside the other two dialogs.** Bin an item, favorite another, and start a compression run in the same session. All three dialogs appear one after another, in that order, and none is swallowed. This is the shared-gate regression test.
9. **Storage pressure.** Fill the device until free space is under a photo's size, then run a compression. It must abort before writing, not part-way through.
10. **A journalled photo deleted from another app.** Force an interrupted write as in 6, then delete that photo in Google Photos before relaunching Keepix. The journal row and its backup must be released, and the recovery request must not re-appear on the launch after that. This is the path `discardInterruptedWrites` exists for, and the failure mode it prevents is a dialog that returns every single launch and can never succeed.

## Self-review notes

Checked against the spec section by section:

- §2 (no `Bitmap.compress`) — enforced as a Global Constraint; the rewriter copies byte spans and never decodes.
- §5 (the strip/keep table) — Task 3, `planStrip`, as an allow-nothing default: only a positively-identified `"MPF"` APP2 is dropped. ICC, XMP, EXIF, JFIF and unidentified segments are covered by tests.
- §6.1 (analyse, no new permission) — Task 6; read-only, header-only.
- §6.2 (rewrite in memory, truncate at EOI) — Tasks 1 and 3.
- §6.3 (confirmation batches, writes serialise; copy-first journal; orphan recovery on launch) — Tasks 4, 5, 7, 8.
- §7 (Phase A only) — stated at the top; Phase B is explicitly out of scope.
- §8 (verification and the saving floor) — Task 3 for the floor, Task 5 for verification. The dimensions and EXIF requirements are met by byte-identity rather than `ExifInterface`; the reasoning and the strengthening are documented above.
- §9 (the error table) — every row maps to a `CompressionOutcome` branch in Task 5, and the two recovery rows to `recover()`.
- §10 (privacy in lockstep) — Task 8, in the same commit as the capability.
- §11 (testing) — Tasks 1, 2, 3, 5, 6 for JVM; Task 4 for the instrumented migration. The instrumented round-trip and orphan-recovery cases from §11 are covered by device checks 3 and 6 rather than as instrumented tests, because both need a real camera JPEG with an MPF payload, which cannot be synthesised meaningfully.
- §12 (files) — every file in the spec's table appears, with `JpegSegments.kt` split into four focused files and `MediaFileIo.kt` added as the test seam.

One spec requirement is deliberately **not** implemented: §5's EXIF thumbnail row is marked "phase 2" in the spec itself and §7 defers it. No task implements it.
