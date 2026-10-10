package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A harness the chat menu offers; [isProfile] and [projects] describe its permanent scope. */
data class StudioHarness(val id: String, val title: String, val isProfile: Boolean, val projects: Set<String>)

/** Library choices: enabled harnesses and committed chat connections. */
data class StudioHarnessChoices(
    val harnesses: List<StudioHarness> = emptyList(),
    val attached: Map<SessionRef, Set<String>> = emptyMap(),
)

/**
 * Chat side of the harness library; implemented over the harness machine, absent without the feature.
 * A new chat's choice stays on the screen until its submission: [claim] hands it over under the submission id,
 * [bindSubmission] names the created chat, and [StudioHarnessAttach] connects it before the first prompt.
 */
interface StudioHarnesses {
    val choices: Flow<StudioHarnessChoices>

    /** Connects or disconnects an existing chat; true once the library confirmed the change. */
    suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean): Boolean

    /** A new chat's submission carries [harnesses]; called before the submission is sent. */
    fun claim(submissionId: String, harnesses: Set<String>)

    /** The claimed submission created [chatId]. */
    fun bindSubmission(submissionId: String, chatId: String)

    /** The submission ended without a chat or after its first prompt; an unbound claim is dropped. */
    fun release(submissionId: String)

    /** Without the harness feature nothing is offered. */
    object None : StudioHarnesses {
        override val choices: Flow<StudioHarnessChoices> = flowOf(StudioHarnessChoices())
        override suspend fun connect(session: SessionRef, harness: String, isSelected: Boolean) = false
        override fun claim(submissionId: String, harnesses: Set<String>) = Unit
        override fun bindSubmission(submissionId: String, chatId: String) = Unit
        override fun release(submissionId: String) = Unit
    }
}

/** Connects a new chat's chosen harnesses between opening its session and submitting the first prompt. */
internal fun interface StudioHarnessAttach {
    suspend fun beforeSubmit(chatId: String, session: SessionRef)
}

/** Default without the harness feature. */
internal object NoStudioHarnessAttach : StudioHarnessAttach {
    override suspend fun beforeSubmit(chatId: String, session: SessionRef) = Unit
}
