package com.vesper.flipper.ui.screen.signals

import com.vesper.flipper.domain.model.SignalCapture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One label/value pair in the capture detail pane. */
data class SignalDetail(
    val label: String,
    val value: String
)

fun formatFrequency(hz: Long): String = when {
    hz >= 1_000_000_000 -> String.format(Locale.US, "%.3f GHz", hz / 1_000_000_000.0)
    hz >= 1_000_000 -> String.format(Locale.US, "%.3f MHz", hz / 1_000_000.0)
    hz >= 1_000 -> String.format(Locale.US, "%.3f kHz", hz / 1_000.0)
    else -> "$hz Hz"
}

fun formatDuration(durationMs: Float?): String? = when {
    durationMs == null || durationMs <= 0f -> null
    durationMs >= 1000f -> String.format(Locale.US, "%.2f s", durationMs / 1000f)
    durationMs >= 1f -> String.format(Locale.US, "%.0f ms", durationMs)
    else -> String.format(Locale.US, "%.2f ms", durationMs)
}

fun formatCaptureDate(timestamp: Long?): String? = timestamp
    ?.takeIf { it > 0L }
    ?.let { value ->
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(value))
    }

/** Protocol plus the identifying numbers, tuned per capture type. */
fun signalSubtitle(signal: SignalCapture): String = when (signal) {
    is SignalCapture.SubGhz -> "${signal.protocol} · ${formatFrequency(signal.frequency)}"
    is SignalCapture.Infrared -> "${signal.protocol} · ${signal.command}"
    is SignalCapture.Nfc -> "${signal.deviceType} · ${signal.uid}"
    is SignalCapture.Rfid -> "${signal.protocol} · ${signal.keyData}"
    is SignalCapture.IButton -> "${signal.protocol} · ${signal.keyData}"
}

/** Detail rows for the selected capture, in reading order. */
fun signalDetails(signal: SignalCapture): List<SignalDetail> = buildList {
    when (signal) {
        is SignalCapture.SubGhz -> {
            add(SignalDetail("Protocol", signal.protocol))
            add(SignalDetail("Frequency", formatFrequency(signal.frequency)))
            if (signal.preset.isNotBlank()) add(SignalDetail("Preset", presetLabel(signal.preset)))
            signal.bitLength?.let { add(SignalDetail("Bit length", it.toString())) }
            signal.keyData?.takeIf { it.isNotBlank() }?.let { add(SignalDetail("Key", it)) }
        }

        is SignalCapture.Infrared -> {
            add(SignalDetail("Protocol", signal.protocol))
            add(SignalDetail("Address", signal.address))
            add(SignalDetail("Command", signal.command))
        }

        is SignalCapture.Nfc -> {
            add(SignalDetail("Type", signal.deviceType))
            add(SignalDetail("UID", signal.uid))
            signal.atqa?.takeIf { it.isNotBlank() }?.let { add(SignalDetail("ATQA", it)) }
            signal.sak?.takeIf { it.isNotBlank() }?.let { add(SignalDetail("SAK", it)) }
            val blocks = signal.dataBlocks.orEmpty()
            blocks.take(4).forEach { block ->
                add(SignalDetail("Block ${block.blockNumber}", block.data))
            }
            if (blocks.size > 4) add(SignalDetail("Blocks", "${blocks.size} total"))
        }

        is SignalCapture.Rfid -> {
            add(SignalDetail("Protocol", signal.protocol))
            add(SignalDetail("Data", signal.keyData))
        }

        is SignalCapture.IButton -> {
            add(SignalDetail("Protocol", signal.protocol))
            add(SignalDetail("Key", signal.keyData))
        }
    }
    add(SignalDetail("File", signal.path.substringAfterLast('/')))
}

/** "FuriHalSubGhzPresetOok650Async" reads better as "Ook 650 Async". */
fun presetLabel(preset: String): String {
    val trimmed = preset.removePrefix("FuriHalSubGhzPreset")
    if (trimmed.isEmpty()) return preset
    return trimmed.replace(
        Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])"),
        " "
    )
}
