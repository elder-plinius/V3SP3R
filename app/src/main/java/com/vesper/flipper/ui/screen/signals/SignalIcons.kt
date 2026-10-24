package com.vesper.flipper.ui.screen.signals

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.ui.graphics.vector.ImageVector
import com.vesper.flipper.domain.model.SignalType

/** Neutral glyph per capture type. No emoji anywhere in the UI. */
fun signalTypeIcon(type: SignalType): ImageVector = when (type) {
    SignalType.SUBGHZ -> Icons.Rounded.Sensors
    SignalType.INFRARED -> Icons.Rounded.SettingsRemote
    SignalType.NFC -> Icons.Rounded.CreditCard
    SignalType.RFID -> Icons.Rounded.Memory
    SignalType.IBUTTON -> Icons.Rounded.Key
}
