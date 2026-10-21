package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import kotlinx.serialization.Serializable

/** Original text captured by an explicit human send; question titles and generated prompts are never persisted here. */
@Serializable
internal data class StudioHumanInstruction(val request: RequestId, val text: String) {
    override fun toString(): String = "StudioHumanInstruction(request=$request)"
}

/** Logical chat authority survives profile restarts, engine replacement and native context rollovers. */
@Serializable
internal data class StudioHumanAuthority(
    val messages: List<StudioHumanInstruction> = emptyList(),
    val isComplete: Boolean = true,
) {
    fun append(request: RequestId, text: String?): StudioHumanAuthority {
        if (!isComplete || !isBounded()) return copy(isComplete = false)
        if (text == null) return this
        val previous = messages.firstOrNull { it.request == request }
        if (previous != null) return if (previous.text == text) this else copy(isComplete = false)
        val next = copy(messages = messages + StudioHumanInstruction(request, text))
        // Preserve known originals but never pretend the omitted input or an earlier restriction was captured.
        return if (next.isBounded()) next else copy(isComplete = false)
    }

    fun snapshot(): TrustedUserInstructions = if (isComplete && isBounded()) {
        TrustedUserInstructions.Complete(messages.map { TrustedUserInstruction(it.request, it.text) })
    } else {
        TrustedUserInstructions.Incomplete
    }

    private fun isBounded(): Boolean = messages.size <= TrustedUserInstructionBounds.MAX_MESSAGES &&
        messages.sumOf { it.text.length.toLong() } <= TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS &&
        messages.map { it.request }.distinct().size == messages.size

    override fun toString(): String = "StudioHumanAuthority(messages=${messages.size}, isComplete=$isComplete)"
}

/**
 * The caller holds the repository's record mutex. A failed write propagates before native submission.
 * Missing legacy provenance stays incomplete; neither transcript roles nor a new "continue" repairs it.
 */
internal suspend fun captureStudioUserInstructions(
    store: KeyValueStore,
    id: String,
    request: RequestId,
    instruction: String?,
): TrustedUserInstructions {
    val records = store.get(ChatsKey).orEmpty()
    val current = records.singleOrNull { it.id == id } ?: error("Unknown studio conversation")
    val previous = current.humanAuthority ?: StudioHumanAuthority(isComplete = false)
    val next = previous.append(request, instruction)
    if (next != current.humanAuthority) {
        store.set(ChatsKey, records.map { if (it.id == id) it.copy(humanAuthority = next) else it })
    }
    return next.snapshot()
}

/** Caller holds the record mutex. Persist the loss of provenance before any answer reaches a native executor. */
internal suspend fun invalidateStudioUserInstructions(store: KeyValueStore, id: String): SessionRef? {
    val records = store.get(ChatsKey).orEmpty()
    val current = records.singleOrNull { it.id == id } ?: error("Unknown studio conversation")
    val previous = current.humanAuthority ?: StudioHumanAuthority(isComplete = false)
    val next = previous.copy(isComplete = false)
    if (next != current.humanAuthority) {
        store.set(ChatsKey, records.map { if (it.id == id) it.copy(humanAuthority = next) else it })
    }
    return current.ref
}
