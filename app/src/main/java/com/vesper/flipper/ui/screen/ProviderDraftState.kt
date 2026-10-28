package com.vesper.flipper.ui.screen

/** Memory-only drafts. Storage observations must never replace newer local edits. */
internal data class ProviderDraftState(
    val savedKey: String,
    val savedModel: String,
    val key: String = savedKey,
    val model: String = savedModel,
    private val keyEdited: Boolean = false,
    private val modelEdited: Boolean = false
) {
    val keyDirty: Boolean get() = key != savedKey
    val modelDirty: Boolean get() = model != savedModel
    val dirty: Boolean get() = keyDirty || modelDirty

    fun editKey(value: String): ProviderDraftState = copy(key = value, keyEdited = true)
    fun editModel(value: String): ProviderDraftState = copy(model = value, modelEdited = true)

    // Equality is sufficient for the dirty indicator, but not for edit protection:
    // reverting to saved A must survive the delayed observation of submitted B.
    // Recomposition with unchanged storage is not an acknowledgement. Only a
    // changed observation matching the current draft can release its protection.
    fun observe(key: String, model: String): ProviderDraftState = copy(
        savedKey = key,
        savedModel = model,
        key = if (keyEdited || keyDirty) this.key else key,
        model = if (modelEdited || modelDirty) this.model else model,
        keyEdited = keyEdited && !(key != savedKey && key == this.key),
        modelEdited = modelEdited && !(model != savedModel && model == this.model)
    )

    override fun toString(): String = "ProviderDraftState(key=<redacted>, model=$model, dirty=$dirty)"
}
