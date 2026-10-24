package com.vesper.flipper.ui.screen.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.vesper.flipper.domain.model.ChatMessage
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Consecutive messages from one author inside this window share one timestamp. */
internal const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/**
 * A group boundary is a new author, a long pause, or the start of the thread.
 * Timestamps render only on these boundaries; every bubble carrying a clock
 * turns the list into noise.
 */
internal fun isGroupBoundary(previous: ChatMessage?, current: ChatMessage): Boolean {
    if (previous == null) return true
    if (previous.role != current.role) return true
    return current.timestamp - previous.timestamp > GROUP_WINDOW_MS
}

internal fun formatMessageTimestamp(timestamp: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
}

/** Relative label for history: "Just now", "12 minutes ago", "Yesterday", then a date. */
internal fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - timestamp).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "Just now"
        delta < 3_600_000L -> {
            val minutes = (delta / 60_000L).toInt()
            if (minutes == 1) "1 minute ago" else minutes.toString() + " minutes ago"
        }
        delta < 86_400_000L -> {
            val hours = (delta / 3_600_000L).toInt()
            if (hours == 1) "1 hour ago" else hours.toString() + " hours ago"
        }
        delta < 7 * 86_400_000L -> {
            val days = (delta / 86_400_000L).toInt()
            if (days == 1) "Yesterday" else days.toString() + " days ago"
        }
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
    }
}

/** Copy helper used by the bubble action row and the long-press gesture. */
internal fun copyToClipboard(context: Context, text: String, label: String = "Message") {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own copy confirmation; a toast would double it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Turns a snake_case command action into sentence case, keeping the acronyms
 * the Flipper world actually uses. "execute_cli" -> "Execute CLI".
 */
internal fun commandActionLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "Tool call"
    val words = raw.lowercase().split('_', ' ', '-').filter { it.isNotBlank() }
    if (words.isEmpty()) return "Tool call"
    return words.mapIndexed { index, word ->
        val acronym = when (word) {
            "cli" -> "CLI"
            "subghz" -> "Sub-GHz"
            "ir" -> "IR"
            "nfc" -> "NFC"
            "rfid" -> "RFID"
            "badusb" -> "BadUSB"
            "faphub" -> "FapHub"
            "led" -> "LED"
            "ble" -> "BLE"
            "usb" -> "USB"
            "id" -> "ID"
            else -> null
        }
        when {
            acronym != null -> acronym
            index == 0 -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }.joinToString(" ")
}

/** Single-line, length-capped preview for tool arguments and paths. */
internal fun previewText(value: String, maxLength: Int = 140): String {
    val singleLine = value.replace('\n', ' ').replace('\r', ' ').trim()
    return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1) + "\u2026"
}
