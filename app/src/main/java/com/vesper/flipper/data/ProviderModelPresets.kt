package com.vesper.flipper.data

/**
 * Provider-scoped offline shortcuts verified on 2026-10-25; see docs/model-presets.md.
 * Suggestions only: not a live catalog, default, account-access or tool-compatibility guarantee.
 * This pure lookup neither reads nor changes saved choices. Ordering is not a default selection.
 */
object ProviderModelPresets {
    fun forProvider(provider: AiProvider): List<ModelInfo> = when (provider) {
        AiProvider.OPENROUTER -> listOf(
            ModelInfo("qwen/qwen3-coder-next", "Qwen3 Coder Next", "Coding and test-writing candidate"),
            ModelInfo("openai/gpt-5.3-codex", "GPT-5.3 Codex", "Code review and debugging candidate"),
            ModelInfo("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", "Everyday coding and secure-code review candidate"),
            ModelInfo("anthropic/claude-opus-5.5", "Claude Opus 5.5", "Complex code and threat-model review candidate"),
            ModelInfo("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "Reasoning and security-learning candidate"),
            ModelInfo("z-ai/glm-5.3", "GLM 5.3", "Software-engineering analysis candidate"),
            ModelInfo("moonshotai/kimi-k2.7-code", "Kimi K2.7 Code", "Programming and authorized lab-code review candidate"),
            ModelInfo("nousresearch/hermes-4-405b", "Hermes 4 405B", "Reasoning candidate; tool compatibility unverified")
        )
        AiProvider.ORCAROUTER -> listOf(
            ModelInfo("openai/gpt-5.3-codex", "GPT-5.3 Codex", "Code review and debugging candidate"),
            ModelInfo("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", "Everyday coding and secure-code review candidate"),
            ModelInfo("anthropic/claude-opus-5.5", "Claude Opus 5.5", "Complex code and threat-model review candidate"),
            ModelInfo("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "Reasoning and security-learning candidate"),
            ModelInfo("z-ai/glm-5.3", "GLM 5.3", "Software-engineering analysis candidate"),
            ModelInfo("kimi/kimi-k2.7-code", "Kimi K2.7 Code", "Programming and authorized lab-code review candidate"),
            ModelInfo("qwen/qwen3.8-max", "Qwen3.8 Max", "Multi-step analysis and security-learning candidate"),
            ModelInfo("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro", "Alternative reasoning and code-analysis candidate")
        )
        AiProvider.ABLITERATED -> listOf(
            ModelInfo("abliterated-model-large-flash", "Abliterated Large Flash", "Text/image analysis candidate; compatibility unverified"),
            ModelInfo("abliterated-model", "Abliterated Model", "Text/image analysis candidate; compatibility unverified"),
            ModelInfo("abliterated-model-large-v2", "Abliterated Large v2", "Text-only analysis candidate; compatibility unverified")
        )
    }
}
