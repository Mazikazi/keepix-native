package com.sese.keepix.utils

/**
 * Formats a byte count as a whole-megabyte string for display.
 *
 * Plain integer division by (1024 * 1024) rounds any value under 1 MB down
 * to "0 MB" -- including genuinely eligible, non-zero savings. That reads as
 * a contradiction on a feature whose entire pitch is honest sizing (see the
 * reclaimable-space card and the post-optimization status line). This makes
 * the zero/near-zero boundary explicit:
 *
 * - exactly 0 bytes is the genuine-nothing case: "0 MB"
 * - any non-zero value below 1 MB: "< 1 MB" (never "0 MB")
 * - 1 MB and above: whole MB, floored, as before
 *
 * Pure Kotlin, no Android dependencies, so it runs on the host JVM in tests.
 */
fun formatMegabytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val oneMegabyte = 1024L * 1024L
    if (bytes < oneMegabyte) return "< 1 MB"
    return "${bytes / oneMegabyte} MB"
}
