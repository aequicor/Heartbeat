package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
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
    /** Null inherits legacy authority completeness; old revoked records must never become complete context. */
    val isQuestionnaireContextComplete: Boolean? = null,
) {
    /** Captures only explicit human input, without ever repairing either lost completeness or tool authority. */
    fun append(request: RequestId, text: String?): StudioHumanAuthority {
        if (!hasCompleteQuestionnaireContext()) return invalidate()
        if (text == null) return this
        val previous = messages.firstOrNull { it.request == request }
        if (previous != null) return if (previous.text == text) this else invalidate()
        val next = copy(messages = messages + StudioHumanInstruction(request, text))
        // Preserve known originals but never pretend the omitted input or an earlier restriction was captured.
        return if (next.isBounded()) next else invalidate()
    }

    fun snapshot(): TrustedUserInstructions = if (isComplete && hasCompleteQuestionnaireContext()) {
        TrustedUserInstructions.Complete(messages.map { TrustedUserInstruction(it.request, it.text) })
    } else {
        TrustedUserInstructions.Incomplete
    }

    fun questionnaireContextSnapshot(): StudioQuestionnaireHumanContext = if (hasCompleteQuestionnaireContext()) {
        StudioQuestionnaireHumanContext.Complete(
            messages.map { StudioQuestionnaireHumanContext.Message(it.request, it.text) },
        )
    } else {
        StudioQuestionnaireHumanContext.Incomplete
    }

    /** Automatic answers preserve existing originals, never missing context or tool authority. Defaults to manual. */
    fun invalidate(preserveQuestionnaireContext: Boolean = false): StudioHumanAuthority = copy(
        isComplete = false,
        // Resolve the nullable legacy default BEFORE revoking authority, including on the first automatic answer.
        isQuestionnaireContextComplete = preserveQuestionnaireContext && hasCompleteQuestionnaireContext(),
    )

    private fun hasCompleteQuestionnaireContext(): Boolean =
        (isQuestionnaireContextComplete ?: isComplete) && isBounded()

    private fun isBounded(): Boolean = messages.size <= TrustedUserInstructionBounds.MAX_MESSAGES &&
        messages.sumOf { it.text.length.toLong() } <= TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS &&
        messages.map { it.request }.distinct().size == messages.size

    override fun toString(): String = "StudioHumanAuthority(messages=${messages.size}, isComplete=$isComplete, " +
        "isQuestionnaireContextComplete=${isQuestionnaireContextComplete ?: isComplete})"
}

/** Read without migration or native session access. The caller holds the repository's record mutex. */
internal suspend fun readStudioUserInstructions(store: KeyValueStore, id: String): TrustedUserInstructions =
    store.get(ChatsKey).orEmpty().singleOrNull { it.id == id }?.humanAuthority?.snapshot()
        ?: TrustedUserInstructions.Incomplete

/** Read bounded human originals for questionnaires only. The caller holds the repository's record mutex. */
internal suspend fun readStudioQuestionnaireHumanContext(
    store: KeyValueStore,
    id: String,
): StudioQuestionnaireHumanContext =
    store.get(ChatsKey).orEmpty().singleOrNull { it.id == id }?.humanAuthority?.questionnaireContextSnapshot()
        ?: StudioQuestionnaireHumanContext.Incomplete

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

/**
 * Caller holds the record mutex. Persist revocation before any answer reaches a native executor.
 * Only automatic answers derived from existing context may preserve it; ordinary/manual calls revoke both states.
 */
internal suspend fun invalidateStudioUserInstructions(
    store: KeyValueStore,
    id: String,
    preserveQuestionnaireContext: Boolean = false,
): SessionRef? {
    val records = store.get(ChatsKey).orEmpty()
    val current = records.singleOrNull { it.id == id } ?: error("Unknown studio conversation")
    val previous = current.humanAuthority ?: StudioHumanAuthority(isComplete = false)
    val next = previous.invalidate(preserveQuestionnaireContext)
    if (next != current.humanAuthority) {
        store.set(ChatsKey, records.map { if (it.id == id) it.copy(humanAuthority = next) else it })
    }
    return current.ref
}
