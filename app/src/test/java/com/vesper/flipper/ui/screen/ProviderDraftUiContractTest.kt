package com.vesper.flipper.ui.screen

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring checks only; runtime draft and persistence behavior have separate tests. */
class ProviderDraftUiContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/com/vesper/flipper/ui/screen/$name.kt"
        return listOf(File(relative), File("app/$relative")).first { it.exists() }.readText()
    }

    @Test fun `explicit field edits use protection and provider switching discards drafts`() {
        val ui = source("ProviderConfiguration")
        assertTrue(ui.contains("drafts = drafts.editKey(it)"))
        assertTrue(ui.contains("drafts = drafts.editModel(id)"))
        assertFalse(ui.contains("drafts.copy("))
        assertTrue(ui.contains("var drafts by remember(state.provider)"))
    }

    @Test fun `key-only save sits before model field and dismisses input explicitly`() {
        val ui = source("ProviderConfiguration")
        assertTrue("Missing adjacent Save key control", ui.contains("Text(\"Save key\")"))
        assertTrue(ui.indexOf("Text(\"Save key\")") < ui.indexOf("Text(\"Exact model ID\")"))
        assertTrue(ui.contains("onSaveKey(state.provider, keyDraft)"))
        assertTrue(ui.contains("focusManager.clearFocus(force = true)"))
        assertTrue(ui.contains("keyboard?.hide()"))
        assertTrue(source("SettingsScreen").contains("onSaveKey = viewModel::saveApiKey"))
    }

    @Test fun `offline presets are unconditional separate from live models and exact selection is draft only`() {
        val ui = source("ProviderConfiguration")
        assertTrue(ui.contains("ProviderModelPresets.forProvider(state.provider)"))
        assertTrue(ui.contains("Suggested models (offline presets)"))
        assertTrue(ui.indexOf("Suggested models (offline presets)") < ui.indexOf("if (models.isNotEmpty())"))
        assertTrue(ui.contains("Live provider model list"))
        assertFalse(ui.contains("!refreshing && !dirty"))
        assertFalse(ui.contains("presets.first()"))
        assertTrue(ui.contains("editModel(model.id)"))
    }
}
