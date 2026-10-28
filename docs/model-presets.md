# Provider-scoped offline model shortcuts

## Contract

`ProviderModelPresets.forProvider(provider: AiProvider): List<ModelInfo>` returns a small, deterministic, provider-scoped list. These are **offline shortcuts**, not automatic fallbacks, defaults, a model migration, or an exhaustive catalog. The function performs no networking or settings access. Nothing in the preset function reads, overwrites, or normalizes a saved model choice; list order must not be treated as an automatic selection. The broader provider-settings UI, ViewModels, and build-time update policy are separate parts of this feature PR and need their own integration tests.

## Vetted data: independently verified 2026-10-25

Verification used credential-free HTTP GETs of the providers' own public resources on **October 25, 2026 (UTC+05:00)**, not inferred IDs, cross-provider aliases, completion requests, or a user account. OpenRouter and OrcaRouter both returned HTTP 200 JSON model catalogs; every ID below was matched exactly against that provider's `data[].id`.[1][4]

Abliteration's public documentation returned HTTP 200 and explicitly describes three current models, plus the deprecated `abliterated-model-large`; only the three current models are included.[2]

### OpenRouter — 8 shortcuts

Every ID in this table occurs in OpenRouter's retrieved public catalog.[1]

| Exact model ID | Display label |
| --- | --- |
| `qwen/qwen3-coder-next` | Qwen3 Coder Next |
| `openai/gpt-5.3-codex` | GPT-5.3 Codex |
| `anthropic/claude-sonnet-5.5` | Claude Sonnet 5.5 |
| `anthropic/claude-opus-5.5` | Claude Opus 5.5 |
| `deepseek/deepseek-v4.1-flash` | DeepSeek V4.1 Flash |
| `z-ai/glm-5.3` | GLM 5.3 |
| `moonshotai/kimi-k2.7-code` | Kimi K2.7 Code |
| `nousresearch/hermes-4-405b` | Hermes 4 405B |

### OrcaRouter — 8 shortcuts

Every ID in this table occurs in OrcaRouter's retrieved public API catalog.[4]

| Exact model ID | Display label |
| --- | --- |
| `openai/gpt-5.3-codex` | GPT-5.3 Codex |
| `anthropic/claude-sonnet-5.5` | Claude Sonnet 5.5 |
| `anthropic/claude-opus-5.5` | Claude Opus 5.5 |
| `deepseek/deepseek-v4.1-flash` | DeepSeek V4.1 Flash |
| `z-ai/glm-5.3` | GLM 5.3 |
| `kimi/kimi-k2.7-code` | Kimi K2.7 Code |
| `qwen/qwen3.8-max` | Qwen3.8 Max |
| `deepseek/deepseek-v4-pro` | DeepSeek V4 Pro |

The Kimi IDs intentionally differ: OpenRouter publishes `moonshotai/kimi-k2.7-code`, while OrcaRouter publishes `kimi/kimi-k2.7-code`. They were verified separately, not translated between providers.[1][4]

### Abliteration.ai — all 3 current models

The IDs and input types below come from the provider's current-model comparison table.[2]

| Exact model ID | Display label | Documented input |
| --- | --- | --- |
| `abliterated-model-large-flash` | Abliterated Large Flash | Text and images |
| `abliterated-model` | Abliterated Model | Text and images |
| `abliterated-model-large-v2` | Abliterated Large v2 | Text only |

**Excluded:** `abliterated-model-large` is explicitly deprecated, even though the documentation says existing integrations can still use it. Excluding it from shortcuts does not invalidate or replace an existing saved choice.[2]

## Recommendations, not verified performance claims

The lists favor coding and reasoning candidates, rather than a universal quality ranking. OpenRouter's descriptions identify Qwen Coder, Codex, Claude, GLM, and Kimi entries with programming, software-engineering, or reasoning tasks.[1] OrcaRouter supplies its own descriptions for its selected coding and reasoning entries.[4]

Our suggested evaluation uses are:

- **Qwen Coder / Codex / Kimi Code:** code explanation, test generation, debugging, and reviewing code in an authorized lab.
- **Claude Sonnet / Opus / GLM:** secure-code review, threat modeling, and analysis of multi-file changes.
- **DeepSeek / Qwen Max / Hermes:** alternative reasoning perspectives for security-analysis exercises and red-team learning in systems you own or are authorized to test.
- **Abliteration models:** compare all current variants against your own benign analysis fixtures; select an image-capable variant only when needed.

These are editorial suggestions, not benchmark findings, security certifications, or claims of safety bypass. Model descriptions in the Kotlin data deliberately say "candidate" rather than promise outcomes. No inference or adversarial capability tests were performed.

## Access and compatibility are not guaranteed

Public listing proves catalog presence on the verification date, **not** account entitlement, credits, regional availability, endpoint health, continued availability, pricing, or successful use in V3SP3R. No account, credentials, phone, or private backups were accessed. No authenticated inference or tool calls were made.

OpenRouter's retrieved `supported_parameters` includes `tools` for the selected entries **except Hermes 4 405B**, which did not advertise that parameter in this snapshot. This is metadata, not a runtime result.[1] OrcaRouter's selected entries advertise `tools` and `chat_completions` in their catalog metadata, also not runtime proof.[4] Abliteration's documentation advertises tool calling and provides an endpoint-specific compatibility matrix; its authenticated `/v1/models` route was not queried.[2]

Neither native tool calling nor V3SP3R's end-to-end tool compatibility is guaranteed for any shortcut. Verify the selected provider, account, endpoint, request parameters, and model with an appropriate authorized test before relying on it. Preserve a manually entered exact ID even if it is not a preset.

## Tests and evidence boundary

`app/src/test/java/com/vesper/flipper/data/ProviderModelPresetsTest.kt` contains 10 JUnit 4 tests: three exact ordered provider lists, bounded counts, within-provider uniqueness, unchanged ID validation, nonblank metadata, Kimi namespace separation, deprecated-ID exclusion, and mutation isolation across calls/providers.

Executed with the cached Kotlin 2.2.10 compiler and JUnit 4.13.2 in a standalone JVM harness: **10 tests passed**. Each provider-list test first failed its intended assertion against an empty list, then passed after that provider's data was added. Additional invariant tests passed on their first run and are regression coverage, not claimed red-phase evidence.

The harness compiles the real `AiProvider.kt`, real preset implementation, real JUnit test, and the `ModelInfo` declaration extracted verbatim from `SettingsStore.kt`; it does not compile the Android-dependent settings class. This verifies the data component, not an Android build, APK, UI integration, saved-preference integration, or live model compatibility. No Windows Gradle/full app compilation was run and protected templates were untouched.

The dated catalog retrieval bodies and standalone-harness logs were not committed; they are not a substitute for fresh public catalog checks. The checked-in regression suite runs under the repository's `:app:testDebugUnitTest` task. Later full-app CI on the original feature branch also ran that task; a transplanted upstream PR tree needs its own CI result. Keep workstation-specific evidence paths and credentials out of the public source.

The checked-in test uses the repository's existing JUnit dependency and standard `app/src/test/java` source set. Refresh the verified lists, documentation date, and exact-ID tests together after repeating provider-specific public catalog checks. Never infer one provider's ID from another provider's spelling.

## Sources

[1] https://openrouter.ai/api/v1/models
[2] https://docs.abliteration.ai/models
[4] https://api.orcarouter.ai/v1/models
