package com.vesper.flipper.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses real Preferences and the same updateData/edit reducer as disk DataStore. */
internal class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

class ProviderSettingsTest {
    @Test fun `legacy settings survive switching and atomic provider saves`() = runBlocking {
        val original = mutablePreferencesOf(
            stringPreferencesKey("openrouter_api_key") to "legacy-fake-key",
            stringPreferencesKey("selected_model") to "Legacy/Exact:free",
            booleanPreferencesKey("auto_connect") to false
        )
        val data = MemoryPreferences(original)
        val store = SettingsStore(data)
        assertEquals(AiConnectionConfig(AiProvider.OPENROUTER, "legacy-fake-key", "Legacy/Exact:free"), store.aiConnectionConfig.first())
        for (provider in listOf(AiProvider.ORCAROUTER, AiProvider.ABLITERATED)) {
            store.setProvider(provider)
            assertEquals(AiConnectionConfig(provider, null, ""), store.aiConnectionConfig.first())
            store.saveProviderConfig(AiConnectionConfig(provider, "fake-${provider.name}", "${provider.name}/Exact:free"))
            assertEquals("fake-${provider.name}", store.apiKey.first())
            assertEquals("${provider.name}/Exact:free", store.selectedModel.first())
            assertEquals("legacy-fake-key", store.openRouterApiKey.first())
        }
        store.saveProviderConfig(AiConnectionConfig(AiProvider.ORCAROUTER, "updated-fake-key", "Mixed/Case:Suffix"))
        assertEquals(AiProvider.ABLITERATED, store.selectedProvider.first())
        store.setProvider(AiProvider.OPENROUTER)
        assertEquals("legacy-fake-key", store.apiKey.first())
        assertEquals("Legacy/Exact:free", store.selectedModel.first())
        assertFalse(store.autoConnect.first())
        assertEquals("legacy-fake-key", data.data.first()[stringPreferencesKey("openrouter_api_key")])
        store.setProvider(AiProvider.ORCAROUTER)
        assertEquals("updated-fake-key", store.apiKey.first())
        assertEquals("Mixed/Case:Suffix", store.selectedModel.first())
    }
}
