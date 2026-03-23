package com.vesper.flipper.ai

/**
 * Supported LLM providers for AI-powered chat.
 * OpenRouter provides access to dozens of models via a single API key.
 * MiniMax provides direct access to MiniMax M2.7 / M2.5 models.
 */
enum class LlmProvider(val displayName: String) {
    OPEN_ROUTER("OpenRouter"),
    MINIMAX("MiniMax");

    companion object {
        fun fromName(name: String): LlmProvider {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: OPEN_ROUTER
        }
    }
}
