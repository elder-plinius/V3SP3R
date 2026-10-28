package com.vesper.flipper.ai

import com.vesper.flipper.data.AiConnectionConfig
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A turn-local snapshot inherited by nested AI work, never by unrelated operations. */
internal class AiConnectionContext(val connection: AiConnectionConfig) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiConnectionContext>
}
