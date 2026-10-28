package com.vesper.flipper.ui.screen

import org.junit.Assert.*
import org.junit.Test

class ProviderDraftStateTest {
    @Test fun `key reverted to saved value survives stale observations and pending key save`() {
        val submitted = ProviderDraftState("unit-A", "Org/Model").editKey("unit-B")
        val reverted = submitted.editKey("unit-A").observe("unit-A", "Org/Model")
        val acknowledged = reverted.observe("unit-B", "Org/Model")
        assertEquals("unit-A", acknowledged.key)
        assertTrue(acknowledged.keyDirty)
        assertFalse(acknowledged.modelDirty)
        assertEquals(acknowledged, acknowledged.observe("unit-B", "Org/Model"))
        val saved = acknowledged.observe("unit-A", "Org/Model")
        assertFalse(saved.dirty)
        assertEquals("unit-C", saved.observe("unit-C", "Org/Loaded").key)
    }

    @Test fun `both reverted drafts survive stale observations and pending atomic save`() {
        val submitted = ProviderDraftState("unit-A", "Org/A")
            .editKey("unit-B").editModel("Org/B")
        val reverted = submitted.editKey("unit-A").editModel("Org/A")
            .observe("unit-A", "Org/A").observe("unit-A", "Org/A")
        val acknowledged = reverted.observe("unit-B", "Org/B")
        assertEquals("unit-A", acknowledged.key)
        assertEquals("Org/A", acknowledged.model)
        assertTrue(acknowledged.keyDirty)
        assertTrue(acknowledged.modelDirty)
        val saved = acknowledged.observe("unit-A", "Org/A")
        assertFalse(saved.dirty)
        val loaded = saved.observe("unit-C", "Org/C")
        assertEquals("unit-C", loaded.key)
        assertEquals("Org/C", loaded.model)
    }

    @Test fun `protecting explicitly reverted key still lets untouched model load`() {
        val draft = ProviderDraftState("unit-A", "Org/A")
            .editKey("unit-B").editKey("unit-A")
            .observe("unit-A", "Org/A")
        val loaded = draft.observe("unit-B", "Org/Loaded")
        assertEquals("unit-A", loaded.key)
        assertEquals("Org/Loaded", loaded.model)
        assertFalse(loaded.modelDirty)
    }

    @Test fun `storage observation of saved key retains a newer typed key and dirty model`() {
        val drafts = ProviderDraftState("unit-old-key", "Org/Saved")
            .copy(key = "unit-newer-edit", model = "Vendor/MixedCase:free")
        val observed = drafts.observe("unit-submitted-key", "Org/Saved")
        assertEquals("unit-newer-edit", observed.key)
        assertEquals("Vendor/MixedCase:free", observed.model)
        assertTrue(observed.keyDirty)
        assertTrue(observed.modelDirty)
    }

    @Test fun `acknowledged key save makes only key clean and does not discard model draft`() {
        val drafts = ProviderDraftState("", "").copy(key = "unit-key", model = "Org/Unsaved")
        val observed = drafts.observe("unit-key", "")
        assertFalse(observed.keyDirty)
        assertTrue(observed.modelDirty)
        assertEquals("Org/Unsaved", observed.model)
    }

    @Test fun `unchanged drafts follow loaded saved profile and explicit key removal`() {
        val loaded = ProviderDraftState("", "").observe("unit-key", "Org/Model")
        assertEquals("unit-key", loaded.key)
        assertEquals("Org/Model", loaded.model)
        assertFalse(loaded.dirty)
        val removed = loaded.observe("", loaded.savedModel)
        assertEquals("", removed.key)
        assertEquals(loaded.model, removed.model)
        assertFalse(removed.dirty)
    }

    @Test fun `new model edits survive delayed atomic save observation`() {
        val newer = ProviderDraftState("unit-key", "Org/Old").copy(model = "Org/Newer")
        assertEquals("Org/Newer", newer.observe("unit-key", "Org/Submitted").model)
    }

    @Test fun `failed write observation retains exact drafts and does not expose key in diagnostics`() {
        val drafts = ProviderDraftState("", "").copy(key = "unit-secret", model = "Mixed/Case-v1:free")
        assertEquals(drafts, drafts.observe("", ""))
        assertFalse(drafts.toString().contains("unit-secret"))
    }
}
