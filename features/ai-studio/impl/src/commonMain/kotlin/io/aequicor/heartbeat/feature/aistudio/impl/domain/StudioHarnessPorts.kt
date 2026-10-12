package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A harness available for explicit connection from a chat's composer. */
data class StudioHarness(val id: String, val title: String)

/** Library choices: enabled harnesses and committed chat connections. */
data class StudioHarnessChoices(
    val harnesses: List<StudioHarness> = emptyList(),
    val attached: Map<SessionRef, Set<String>> = emptyMap(),
)

/**
 * Chat side of the harness library; implemented over the harness machine, absent without the feature.
 * A new chat's choice stays on the screen until its submission: [claim] hands it over under the submission id,
 * which travels with the submission to its first turn, and [StudioHarnessAttach] connects it before that prompt.
 * The screen only claims; the turn uses and releases the claim, so leaving the screen never strands it.
 */
interface StudioHarnesses {
    val choices: Flow<StudioHarnessChoices>

    /** Connects or disconnects an existing chat; true once the library confirmed the change. */
    suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean

    /** A new chat's submission carries [harnesses]; called before the submission is sent. */
    fun claim(submissionId: String, harnesses: Set<String>)

    /** The submission was not sent; its claim is dropped. */
    fun release(submissionId: String)

    /** Without the harness feature nothing is offered. */
    object None : StudioHarnesses {
        override val choices: Flow<StudioHarnessChoices> = flowOf(StudioHarnessChoices())
        override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean) = false
        override fun claim(submissionId: String, harnesses: Set<String>) = Unit
        override fun release(submissionId: String) = Unit
    }
}

/** Connects a new chat's chosen harnesses between opening its session and submitting the first prompt. */
internal interface StudioHarnessAttach {
    /** Connects the harnesses claimed by [submissionId] to [session]; a claim is used once, other turns pass. */
    suspend fun beforeSubmit(submissionId: String, session: SessionRef)

    /** The turn of [submissionId] ended; a claim it did not use is dropped. */
    fun release(submissionId: String)
}

/** Default without the harness feature. */
internal object NoStudioHarnessAttach : StudioHarnessAttach {
    override suspend fun beforeSubmit(submissionId: String, session: SessionRef) = Unit
    override fun release(submissionId: String) = Unit
}
