package com.vesper.flipper.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceAssistNotesTest {
    @Test fun `notes start empty and persist independently of provider configuration`() = runBlocking {
        val data = MemoryPreferences()
        val store = SettingsStore(data)
        assertEquals("", store.deviceAssistNotes.first())
        val config = AiConnectionConfig(AiProvider.ORCAROUTER, "synthetic-key-not-real", "Vendor/Exact")
        store.saveProviderConfig(config)
        store.setProvider(AiProvider.ORCAROUTER)
        store.setDeviceAssistNotes("Flipper firmware: user-supplied observations")
        assertEquals("Flipper firmware: user-supplied observations", SettingsStore(data).deviceAssistNotes.first())
        assertEquals(config, store.aiConnectionConfig.first())
        store.setDeviceAssistNotes("")
        assertEquals("", SettingsStore(data).deviceAssistNotes.first())
        assertEquals(config, store.aiConnectionConfig.first())
    }

    @Test fun `notes accept 4096 UTF-8 bytes but reject larger without mutating saved text`() = runBlocking {
        val store = SettingsStore(MemoryPreferences())
        val maximum = "🔧".repeat(1024)
        store.setDeviceAssistNotes(maximum)
        assertEquals(maximum, store.deviceAssistNotes.first())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setDeviceAssistNotes(maximum + "x") }
        }
        assertEquals(maximum, store.deviceAssistNotes.first())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setDeviceAssistNotes("é".repeat(2049)) }
        }
        assertEquals(maximum, store.deviceAssistNotes.first())
    }
}
