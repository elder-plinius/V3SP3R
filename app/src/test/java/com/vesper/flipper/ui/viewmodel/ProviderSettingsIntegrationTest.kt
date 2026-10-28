package com.vesper.flipper.ui.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.data.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsIntegrationTest {
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

    @Test fun `pasted website URL is not stored as model ID`() = runTest(dispatcher) {
        runCurrent()
        vm.setSelectedModel("https://www.orcarouter.ai/console/catalog")
        runCurrent()
        assertEquals(SettingsStore.DEFAULT_MODEL, store.selectedModel.first())
    }

    @Test fun `save preserves exact provider model ID`() = runTest(dispatcher) {
        runCurrent()
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        vm.saveProviderSettings("unit-orca-not-a-real-key", "Vendor/MixedCase.Model-v2:free")
        runCurrent()
        val saved = store.aiConnectionConfig.first()
        assertEquals(AiProvider.ORCAROUTER, saved.provider)
        assertEquals("Vendor/MixedCase.Model-v2:free", saved.model)
        assertEquals("unit-orca-not-a-real-key", saved.apiKey)
        assertEquals(saved.model, vm.state.value.selectedModel)
    }

    @Test fun `provider switch clears old credentials immediately and restores separate profile`() = runTest(dispatcher) {
        store.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "unit-openrouter-key", "Org/OpenModel"))
        store.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "unit-orca-key", "Org/OrcaModel"))
        runCurrent()
        assertEquals("unit-openrouter-key", vm.state.value.apiKey)
        vm.setProvider(AiProvider.ORCAROUTER)
        assertEquals("", vm.state.value.apiKey)
        assertEquals("", vm.state.value.selectedModel)
        assertFalse(vm.state.value.providerReady)
        runCurrent()
        assertEquals("unit-orca-key", vm.state.value.apiKey)
        assertEquals("Org/OrcaModel", vm.state.value.selectedModel)
        vm.setProvider(AiProvider.OPENROUTER)
        runCurrent()
        assertEquals("unit-openrouter-key", vm.state.value.apiKey)
        assertEquals("Org/OpenModel", vm.state.value.selectedModel)
    }

    @Test fun `invalid draft does not partially overwrite saved key or model`() = runTest(dispatcher) {
        store.saveProviderConfig(AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/Original"))
        runCurrent()
        vm.saveProviderSettings("unit-new-key", " Org/Wrong ")
        runCurrent()
        assertEquals("unit-original-key", store.aiConnectionConfig.first().apiKey)
        assertEquals("Org/Original", store.aiConnectionConfig.first().model)
        assertTrue(vm.state.value.providerMessageIsError)
    }

    @Test fun `key can be saved before choosing model from authenticated catalog`() = runTest(dispatcher) {
        runCurrent()
        vm.setProvider(AiProvider.ABLITERATED)
        runCurrent()
        vm.saveProviderSettings("unit-abliteration-key", "")
        runCurrent()
        assertEquals("unit-abliteration-key", store.aiConnectionConfig.first().apiKey)
        assertEquals("", store.aiConnectionConfig.first().model)
        vm.testProviderConnection()
        assertTrue(vm.state.value.providerMessageIsError)
        assertFalse(vm.state.value.isTestingProvider)
    }

    @Test fun `unconfigured test stays local with actionable error`() = runTest(dispatcher) {
        runCurrent()
        vm.setProvider(AiProvider.ABLITERATED)
        runCurrent()
        vm.testProviderConnection()
        runCurrent()
        assertFalse(vm.state.value.isTestingProvider)
        assertTrue(vm.state.value.providerMessageIsError)
        assertTrue(vm.state.value.providerMessage.orEmpty().contains("API key"))
    }

    @Test fun `non provider settings emission cannot overwrite selected provider fields`() = runTest(dispatcher) {
        store.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "unit-orca-key", "Org/Model"))
        store.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        store.setDarkMode(false)
        runCurrent()
        assertEquals(AiProvider.ORCAROUTER, vm.state.value.provider)
        assertEquals("unit-orca-key", vm.state.value.apiKey)
        assertEquals("Org/Model", vm.state.value.selectedModel)
        assertFalse(vm.state.value.darkMode)
        assertFalse(vm.state.value.autoApproveHigh)
    }

    @Test fun `failed save remains visible with unsaved drafts`() = runTest(dispatcher) {
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/Original")
        store.saveProviderConfig(original)
        runCurrent()
        preferences.failNextUpdate = true
        vm.saveProviderSettings("unit-new-key", "Org/New")
        runCurrent()
        assertEquals(original, store.aiConnectionConfig.first())
        assertEquals(original.apiKey, vm.state.value.apiKey)
        assertEquals(original.model, vm.state.value.selectedModel)
        assertTrue(vm.state.value.providerMessageIsError)
        assertEquals("Could not save provider settings. Please retry.",
            vm.state.value.visibleProviderMessage(dirty = true))
    }

    @Test fun `dirty drafts suppress prior test success but not errors`() {
        val success = SettingsState(providerMessage = "Test succeeded.", providerMessageIsError = false)
        assertEquals("Test succeeded.", success.visibleProviderMessage(dirty = false))
        assertNull(success.visibleProviderMessage(dirty = true))
        val failure = success.copy(providerMessage = "Could not save.", providerMessageIsError = true)
        assertEquals("Could not save.", failure.visibleProviderMessage(dirty = true))
    }

    @Test fun `explicit removal clears only active key and preserves every saved model`() = runTest(dispatcher) {
        val originals = AiProvider.entries.associateWith { provider ->
            AiConnectionConfig(provider, "unit-${provider.id}-key", "Org/${provider.name}")
        }
        originals.values.forEach { store.saveProviderConfig(it) }
        store.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        // Null is the explicit Remove saved key action, not a blank-key save.
        // Unsaved model edits must not replace the saved model during removal.
        vm.saveProviderSettings(null, "Org/UnsavedDraft")
        runCurrent()
        assertEquals(AiProvider.ORCAROUTER, vm.state.value.provider)
        assertEquals("", vm.state.value.apiKey)
        assertEquals(originals.getValue(AiProvider.ORCAROUTER).model, vm.state.value.selectedModel)
        vm.testProviderConnection()
        assertFalse(vm.state.value.isTestingProvider)
        assertTrue(vm.state.value.providerMessageIsError)
        assertTrue(vm.state.value.providerMessage.orEmpty().contains("API key"))
        originals.forEach { (provider, original) ->
            vm.setProvider(provider)
            runCurrent()
            val expected = if (provider == AiProvider.ORCAROUTER) original.copy(apiKey = null) else original
            assertEquals(expected, store.aiConnectionConfig.first())
        }
    }

    @Test fun `key removal queued after save preserves new model before state observation`() = runTest(dispatcher) {
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/ModelA")
        val other = AiConnectionConfig(AiProvider.ORCAROUTER, "unit-orca-key", "Org/OrcaModel")
        store.saveProviderConfig(original)
        store.saveProviderConfig(other)
        runCurrent()
        assertEquals(original.model, vm.state.value.selectedModel)

        // Queue both writes while the observer still holds model A.
        vm.saveProviderSettings("unit-new-key", "Org/ModelB")
        vm.saveProviderSettings(null, "Org/UnsavedDraft")
        assertEquals(original.model, vm.state.value.selectedModel)
        runCurrent()

        assertEquals(original.copy(apiKey = null, model = "Org/ModelB"), store.aiConnectionConfig.first())
        assertEquals("Org/ModelB", vm.state.value.selectedModel)
        assertEquals("", vm.state.value.apiKey)
        vm.setProvider(AiProvider.ORCAROUTER)
        runCurrent()
        assertEquals(other, store.aiConnectionConfig.first())
    }

    @Test fun `blank key save still rejects rather than removing saved credentials`() = runTest(dispatcher) {
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/Original")
        store.saveProviderConfig(original)
        runCurrent()
        vm.saveProviderSettings("", "Org/New")
        runCurrent()
        assertEquals(original, store.aiConnectionConfig.first())
        assertTrue(vm.state.value.providerMessageIsError)
        assertTrue(vm.state.value.visibleProviderMessage(dirty = true).orEmpty().contains("API key"))
    }

    @Test fun `failed key removal preserves saved configuration and reports error`() = runTest(dispatcher) {
        val original = AiConnectionConfig(AiProvider.OPENROUTER, "unit-original-key", "Org/Original")
        store.saveProviderConfig(original)
        runCurrent()
        preferences.failNextUpdate = true
        vm.saveProviderSettings(null, "Org/UnsavedDraft")
        runCurrent()
        assertEquals(original, store.aiConnectionConfig.first())
        assertEquals(original.apiKey, vm.state.value.apiKey)
        assertTrue(vm.state.value.providerMessageIsError)
        assertEquals("Could not remove saved key. Please retry.",
            vm.state.value.visibleProviderMessage(dirty = true))
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
                    throw java.io.IOException("Injected preferences write failure")
                }
                transform(values.value).also { values.value = it }
            }
    }
}
