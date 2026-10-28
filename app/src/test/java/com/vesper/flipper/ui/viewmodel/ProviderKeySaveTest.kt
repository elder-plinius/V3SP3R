package com.vesper.flipper.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.data.*
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderKeySaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val preferences = KeyPreferences()
    private val store = SettingsStore(preferences)
    private val models = ViewModelStore()
    private lateinit var vm: SettingsViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = SettingsViewModel(store, OpenRouterModelCatalog(), PermissionService(), OpenRouterClient(store))
        models.put("settings", vm)
    }
    @After fun teardown() { models.clear(); Dispatchers.resetMain() }

    @Test fun `key-only save rejects malformed token without replacing persisted configuration`() = runTest(dispatcher) {
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/Original")
        store.saveProviderConfig(original)
        runCurrent()
        vm.setApiKey(" invalid key ")
        runCurrent()
        assertEquals(original, store.aiConnectionConfig.first())
        assertTrue(vm.state.value.providerMessageIsError)
    }

    @Test fun `key-only save confirms local persistence with no model selection`() = runTest(dispatcher) {
        runCurrent()
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        vm.setApiKey("unit-local-key")
        runCurrent()
        assertEquals("unit-local-key", store.aiConnectionConfig.first().apiKey)
        assertEquals("", store.aiConnectionConfig.first().model)
        assertEquals("Key saved on this device.", vm.state.value.providerMessage)
        assertFalse(vm.state.value.providerMessageIsError)
    }

    @Test fun `key save is synchronously busy rejects duplicates and leaves every model untouched`() = runTest(dispatcher) {
        store.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "unit-old", "Org/Exact:free"))
        store.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "unit-other", "Other/Exact"))
        runCurrent()
        val before = preferences.writes
        val gate = CompletableDeferred<Unit>()
        preferences.pauseNext = gate
        vm.saveApiKey(AiProvider.OPENROUTER, "unit-new")
        assertTrue(vm.state.value.isSavingKey)
        vm.saveApiKey(AiProvider.OPENROUTER, "unit-duplicate")
        vm.saveProviderSettings("unit-atomic", "Org/Wrong")
        vm.saveProviderSettings(null, "Org/Wrong")
        vm.testProviderConnection()
        assertFalse(vm.state.value.isTestingProvider)
        runCurrent()
        assertEquals(before + 1, preferences.writes)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isSavingKey)
        assertEquals("unit-new", vm.state.value.apiKey)
        assertEquals("Org/Exact:free", vm.state.value.selectedModel)
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        assertEquals("unit-other", vm.state.value.apiKey)
        assertEquals("Other/Exact", vm.state.value.selectedModel)
    }

    @Test fun `failed key save retains saved config and error survives later observer emission`() = runTest(dispatcher) {
        store.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "unit-old", "Org/Model"))
        runCurrent()
        preferences.failNext = java.io.IOException("unit-sensitive-do-not-display")
        vm.setApiKey("unit-new")
        runCurrent()
        assertFalse(vm.state.value.isSavingKey)
        assertTrue(vm.state.value.keySaveIsError)
        assertEquals("unit-old", vm.state.value.apiKey)
        assertFalse(vm.state.value.keySaveMessage.orEmpty().contains("unit-sensitive"))
        store.setSelectedModel("Org/External", AiProvider.OPENROUTER)
        runCurrent()
        assertTrue(vm.state.value.keySaveIsError)
        assertNotNull(vm.state.value.visibleProviderMessage(true))
        vm.providerDraftEdited(AiProvider.OPENROUTER, true)
        assertNull(vm.state.value.keySaveMessage)
    }

    @Test fun `editing during key save suppresses late success even when edit is undone`() = runTest(dispatcher) {
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.pauseNext = gate
        vm.setApiKey("unit-submitted")
        runCurrent()
        vm.providerDraftEdited(AiProvider.OPENROUTER, true)
        vm.providerDraftEdited(AiProvider.OPENROUTER, true)
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.keySaveMessage)
        assertNull(vm.state.value.providerMessage)
        assertEquals("unit-submitted", vm.state.value.apiKey)
        assertFalse(vm.state.value.isSavingKey)
    }

    @Test fun `late key save cannot report success after switching away and back`() = runTest(dispatcher) {
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.pauseNext = gate
        vm.setApiKey("unit-captured")
        runCurrent()
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        vm.setProvider(AiProvider.OPENROUTER)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.keySaveMessage)
        assertNull(vm.state.value.providerMessage)
        assertEquals("unit-captured", vm.state.value.apiKey)
    }

    @Test fun `stale callback with another captured provider never saves into current provider`() = runTest(dispatcher) {
        runCurrent()
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        val before = preferences.writes
        vm.saveApiKey(AiProvider.OPENROUTER, "unit-stale")
        runCurrent()
        assertEquals(before, preferences.writes)
        assertEquals("", vm.state.value.apiKey)
        assertFalse(vm.state.value.isSavingKey)
    }

    @Test fun `cancelled key write clears busy without false success or error`() = runTest(dispatcher) {
        runCurrent()
        preferences.failNext = CancellationException("cancelled write")
        vm.setApiKey("unit-key")
        runCurrent()
        assertFalse(vm.state.value.isSavingKey)
        assertNull(vm.state.value.keySaveMessage)
        assertFalse(vm.state.value.keySaveIsError)
        vm.setApiKey("unit-retry")
        runCurrent()
        assertEquals("unit-retry", vm.state.value.apiKey)
    }

    @Test fun `key save cannot race an already pending atomic settings save`() = runTest(dispatcher) {
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.pauseNext = gate
        vm.saveProviderSettings("unit-atomic", "Org/Atomic")
        runCurrent()
        val writes = preferences.writes
        vm.setApiKey("unit-key-only")
        runCurrent()
        assertEquals(writes, preferences.writes)
        gate.complete(Unit)
        runCurrent()
        assertEquals("unit-atomic", vm.state.value.apiKey)
    }

    @Test fun `atomic save completion after local edit cannot resurrect success`() = runTest(dispatcher) {
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        preferences.pauseNext = gate
        vm.saveProviderSettings("unit-atomic", "Org/Atomic")
        runCurrent()
        vm.providerDraftEdited(AiProvider.OPENROUTER, false)
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.providerMessage)
    }

    @Test fun `cancellation before key coroutine starts cannot leave busy stuck`() = runTest(dispatcher) {
        runCurrent()
        vm.setApiKey("unit-key")
        assertTrue(vm.state.value.isSavingKey)
        models.clear()
        runCurrent()
        assertFalse(vm.state.value.isSavingKey)
        assertNull(vm.state.value.keySaveMessage)
    }

    @Test fun `external configuration change clears old atomic save success`() = runTest(dispatcher) {
        runCurrent()
        vm.saveProviderSettings("unit-key", "Org/Model")
        runCurrent()
        assertNotNull(vm.state.value.providerMessage)
        store.setSelectedModel("Org/External", AiProvider.OPENROUTER)
        runCurrent()
        assertNull(vm.state.value.providerMessage)
    }

    private class KeyPreferences : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        var writes = 0
        var pauseNext: CompletableDeferred<Unit>? = null
        var failNext: Exception? = null
        override val data = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            val pause = pauseNext.also { pauseNext = null }
            pause?.await()
            failNext?.let { failNext = null; throw it }
            return transform(values.value).also { values.value = it }
        }
    }
}
