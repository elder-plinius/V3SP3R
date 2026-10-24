package com.vesper.flipper.ui.screen.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human byte size used by rows, tiles, and the preview sheet. */
fun formatFileSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe < 1024L -> safe.toString() + " B"
        safe < 1024L * 1024L -> (safe / 1024L).toString() + " KB"
        safe < 1024L * 1024L * 1024L -> (safe / (1024L * 1024L)).toString() + " MB"
        else -> String.format(Locale.US, "%.1f GB", safe.toDouble() / (1024.0 * 1024.0 * 1024.0))
    }
}

/** Modified date, or a clear fallback when the firmware does not report one. */
fun formatFileDate(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "Date unknown"
    val formatter = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
