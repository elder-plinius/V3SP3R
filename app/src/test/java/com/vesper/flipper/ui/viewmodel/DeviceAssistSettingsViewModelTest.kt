package com.vesper.flipper.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAssistSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val preferences = MemoryPreferences()
    private val store = SettingsStore(preferences)
    private val models = ViewModelStore()
    private lateinit var vm: SettingsViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = SettingsViewModel(store, OpenRouterModelCatalog(), PermissionService(), OpenRouterClient(store))
        models.put("settings", vm)
    }

    @After fun teardown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun `editing remains local until save and reset removes persisted notes`() = runTest(dispatcher) {
        runCurrent()
        assertEquals("", vm.state.value.deviceAssistNotesDraft)
        vm.editDeviceAssistNotes("Verified firmware detail")
        runCurrent()
        assertEquals("", store.deviceAssistNotes.first())
        assertEquals("Verified firmware detail", vm.state.value.deviceAssistNotesDraft)
        vm.saveDeviceAssistNotes()
        runCurrent()
        assertEquals("Verified firmware detail", store.deviceAssistNotes.first())
        assertEquals("Flipper Assist Notes saved.", vm.state.value.deviceAssistNotesMessage)
        vm.resetDeviceAssistNotes()
        runCurrent()
        assertEquals("", store.deviceAssistNotes.first())
        assertEquals("", vm.state.value.deviceAssistNotesDraft)
        assertEquals("Flipper Assist Notes reset.", vm.state.value.deviceAssistNotesMessage)
    }

    @Test fun `oversized notes and failed writes preserve saved text and editable draft`() = runTest(dispatcher) {
        store.setDeviceAssistNotes("Original")
        runCurrent()
        vm.editDeviceAssistNotes("é".repeat(2049))
        vm.saveDeviceAssistNotes()
        runCurrent()
        assertEquals("Original", store.deviceAssistNotes.first())
        assertTrue(vm.state.value.deviceAssistNotesMessageIsError)
        assertEquals("é".repeat(2049), vm.state.value.deviceAssistNotesDraft)
        vm.editDeviceAssistNotes("Updated")
        preferences.failNextUpdate = true
        vm.saveDeviceAssistNotes()
        runCurrent()
        assertEquals("Original", store.deviceAssistNotes.first())
        assertEquals("Updated", vm.state.value.deviceAssistNotesDraft)
        assertTrue(vm.state.value.deviceAssistNotesMessageIsError)
    }

    @Test fun `late storage observation does not erase unsaved draft`() = runTest(dispatcher) {
        runCurrent()
        vm.editDeviceAssistNotes("Draft")
        store.setDeviceAssistNotes("External update")
        runCurrent()
        assertEquals("Draft", vm.state.value.deviceAssistNotesDraft)
        assertEquals("External update", vm.state.value.deviceAssistNotesSaved)
    }

    @Test fun `stale assist save cannot overwrite another settings writer`() = runTest(dispatcher) {
        store.setDeviceAssistNotes("A")
        runCurrent()
        vm.editDeviceAssistNotes("B")
        vm.saveDeviceAssistNotes()
        SettingsStore(preferences).setDeviceAssistNotes("C")
        runCurrent()
        assertEquals("C", store.deviceAssistNotes.first())
        assertEquals("B", vm.state.value.deviceAssistNotesDraft)
        assertTrue(vm.state.value.deviceAssistNotesMessageIsError)
    }

    @Test fun `reverted assist draft survives delayed external observation`() = runTest(dispatcher) {
        store.setDeviceAssistNotes("A")
        runCurrent()
        vm.editDeviceAssistNotes("B")
        vm.editDeviceAssistNotes("A")
        SettingsStore(preferences).setDeviceAssistNotes("B")
        runCurrent()
        assertEquals("A", vm.state.value.deviceAssistNotesDraft)
        assertEquals("B", vm.state.value.deviceAssistNotesSaved)
    }

    @Test fun `stale reset keeps newer notes and unsaved local draft`() = runTest(dispatcher) {
        store.setDeviceAssistNotes("A")
        runCurrent()
        vm.editDeviceAssistNotes("Draft")
        vm.resetDeviceAssistNotes()
        SettingsStore(preferences).setDeviceAssistNotes("C")
        runCurrent()
        assertEquals("C", store.deviceAssistNotes.first())
        assertEquals("Draft", vm.state.value.deviceAssistNotesDraft)
        assertTrue(vm.state.value.deviceAssistNotesMessageIsError)
    }

    @Test fun `reset clears unsaved draft when persisted notes are already empty`() = runTest(dispatcher) {
        runCurrent()
        vm.editDeviceAssistNotes("Unsaved draft")
        vm.resetDeviceAssistNotes()
        runCurrent()
        assertEquals("", store.deviceAssistNotes.first())
        assertEquals("", vm.state.value.deviceAssistNotesDraft)
        assertFalse(vm.state.value.deviceAssistNotesMessageIsError)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        var failNextUpdate = false
        override val data = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                if (failNextUpdate) {
                    failNextUpdate = false
                    throw IOException("Injected write failure")
                }
                transform(values.value).also { values.value = it }
            }
    }
}
