package com.vesper.flipper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelPresetsTest {
    @Test
    fun everyProviderHasTheIntendedBoundedShortcutCount() {
        assertEquals(8, ProviderModelPresets.forProvider(AiProvider.OPENROUTER).size)
        assertEquals(8, ProviderModelPresets.forProvider(AiProvider.ORCAROUTER).size)
        assertEquals(3, ProviderModelPresets.forProvider(AiProvider.ABLITERATED).size)
    }

    @Test
    fun idsAreUniqueWithinEachProvider() {
        AiProvider.entries.forEach { provider ->
            val ids = ProviderModelPresets.forProvider(provider).map { it.id }
            assertEquals(provider.name, ids.size, ids.toSet().size)
        }
    }

    @Test
    fun exactIdsPassValidationWithoutNormalization() {
        AiProvider.entries.forEach { provider ->
            ProviderModelPresets.forProvider(provider).forEach { model ->
                assertEquals(model.id, model.id.trim())
                assertNull("${provider.name}: ${model.id}", modelIdValidationError(model.id))
            }
        }
    }

    @Test
    fun everyShortcutHasNonBlankDisplayMetadata() {
        AiProvider.entries.forEach { provider ->
            ProviderModelPresets.forProvider(provider).forEach { model ->
                assertTrue(model.id, model.displayName.isNotBlank())
                assertTrue(model.id, model.description.isNotBlank())
            }
        }
    }

    @Test
    fun kimiIdsStayInTheirProviderNamespace() {
        val openRouter = ProviderModelPresets.forProvider(AiProvider.OPENROUTER).map { it.id }
        val orcaRouter = ProviderModelPresets.forProvider(AiProvider.ORCAROUTER).map { it.id }
        assertTrue("moonshotai/kimi-k2.7-code" in openRouter)
        assertFalse("kimi/kimi-k2.7-code" in openRouter)
        assertTrue("kimi/kimi-k2.7-code" in orcaRouter)
        assertFalse("moonshotai/kimi-k2.7-code" in orcaRouter)
    }

    @Test
    fun deprecatedAbliterationIdIsNeverOffered() {
        AiProvider.entries.forEach { provider ->
            assertFalse(ProviderModelPresets.forProvider(provider).any { it.id == "abliterated-model-large" })
        }
    }

    @Test
    fun callerMutationCannotAlterFuturePresetsOrAnotherProvider() {
        val expected = AiProvider.entries.associateWith { ProviderModelPresets.forProvider(it).toList() }
        AiProvider.entries.forEach { provider ->
            val returned = ProviderModelPresets.forProvider(provider)
            // Kotlin List is read-only, not necessarily immutable on the JVM.
            // Both rejecting mutation and returning a defensive fresh list are valid.
            try {
                (returned as MutableList<ModelInfo>)[0] = ModelInfo("caller-only", "Caller", "Not a preset")
            } catch (_: UnsupportedOperationException) {
                // An immutable implementation is also acceptable.
            }
            expected.forEach { (otherProvider, models) ->
                assertEquals(otherProvider.name, models, ProviderModelPresets.forProvider(otherProvider))
            }
        }
    }

    @Test
    fun abliterationIncludesExactlyTheThreeCurrentModels() {
        assertEquals(
            listOf("abliterated-model-large-flash", "abliterated-model", "abliterated-model-large-v2"),
            ProviderModelPresets.forProvider(AiProvider.ABLITERATED).map { it.id }
        )
    }

    @Test
    fun orcaRouterPreservesItsOwnVerifiedIdsInCuratedOrder() {
        assertEquals(
            listOf(
                "openai/gpt-5.3-codex",
                "anthropic/claude-sonnet-5.5",
                "anthropic/claude-opus-5.5",
                "deepseek/deepseek-v4.1-flash",
                "z-ai/glm-5.3",
                "kimi/kimi-k2.7-code",
                "qwen/qwen3.8-max",
                "deepseek/deepseek-v4-pro"
            ),
            ProviderModelPresets.forProvider(AiProvider.ORCAROUTER).map { it.id }
        )
    }

    @Test
    fun openRouterPreservesVerifiedIdsInCuratedOrder() {
        assertEquals(
            listOf(
                "qwen/qwen3-coder-next",
                "openai/gpt-5.3-codex",
                "anthropic/claude-sonnet-5.5",
                "anthropic/claude-opus-5.5",
                "deepseek/deepseek-v4.1-flash",
                "z-ai/glm-5.3",
                "moonshotai/kimi-k2.7-code",
                "nousresearch/hermes-4-405b"
            ),
            ProviderModelPresets.forProvider(AiProvider.OPENROUTER).map { it.id }
        )
    }
}
