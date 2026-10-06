package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.OrganismRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The organic AI machine as studio chats use it. An organism chat has the id of its organism, so the chat and the
 * organism never need a separate link. The chat is saved first and its organism conceived by its first prompt, so an
 * organism never lives without a chat to show and stop it; a chat whose organism is not in view takes its goal again.
 * The machine runs only while organic AI is on in the profile.
 */
@Inject
internal class StudioOrganisms(private val machines: MachineRegistry, private val selections: ModelSelections) {
    private val log = Log.tag("StudioOrganisms")

    /**
     * The organism id of the new chat [chat] created for [request]: the chat's own id. Fails when its organism could
     * not be conceived now: the goal is too long, no model is chosen, organic AI is off or asleep, or the chat runs
     * in an isolated checkout, which organism turns bypass. Asked before the chat is saved.
     */
    suspend fun admit(chat: String, request: OrganismRequest, isWorktree: Boolean): String {
        require(!isWorktree) { "An organism does not run in an isolated checkout" }
        require(request.goal.length <= OrganismBounds.MAX_GOAL) { "The goal is too long for an organism" }
        target(request.settings.modelId, stored = null)
        living()
        return chat
    }

    /**
     * Conceives the organism of [chat] from its first message, or submits a follow-up to its completed zygote.
     * The chat never gets a native session of its own. Null for an ordinary chat. The machine owns acceptance;
     * rejected messages keep the caller's draft. Each turn retains its own [attachments] through recovery.
     * Fails when organic AI is off/asleep, the organism is busy/aborted, or input is invalid.
     */
    suspend fun submit(
        chat: StudioChatRecord,
        goal: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        onAccepted: suspend () -> Unit,
    ): RunOutcome? {
        if (chat.organismId == null) return null
        val machine = living()
        val organisms = (machine.state.value as? OrganicAiState.Living)?.organisms.orEmpty()
        if (OrganismId(chat.id) in organisms) {
            val result = machine.send(OrganicAiIntent.Public.FollowUp(OrganismId(chat.id), goal, attachments))
            log.i { "organism ${chat.id} follow-up for a studio chat: $result" }
            check(result == SendResult.Accepted) { "The organism cannot accept a follow-up now" }
            onAccepted()
            return RunOutcome.Completed
        }
        val chosen = chat.configuration?.modelId ?: chat.target?.studioModelId() ?: settings.modelId
        val conception = Conception(
            OrganismId(chat.id),
            goal,
            target = target(chosen, chat.target),
            workspace = chat.executionWorkspace ?: chat.projectId?.let(::WorkspaceRef),
            trust = settings.approval.toTrust(),
            attachments = attachments,
        )
        val result = machine.send(OrganicAiIntent.Public.Conceive(conception))
        log.i { "organism ${conception.id.value} conception for a studio chat: $result" }
        check(result == SendResult.Accepted) { "The organism was not conceived" }
        onAccepted()
        return RunOutcome.Completed
    }

    private suspend fun target(chosen: String, stored: EngineTarget?): EngineTarget {
        val selected = selections.observe().first()
        val target = requireNotNull(turnTarget(chosen, stored, selected.defaultTarget)) {
            "Select a connected model in settings"
        }
        check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
        return target
    }

    /** The organic AI machine once it lives, waiting a while for it to start and wake. */
    private suspend fun living(): OrganicMachineRef {
        val machine = withTimeoutOrNull(WAIT_MILLIS) {
            machines.observe(OrganicAiMachineKey).filterNotNull().first()
        } ?: error("Organic AI is turned off")
        val awake = withTimeoutOrNull(WAIT_MILLIS) {
            machine.state.first { it is OrganicAiState.Living || it == OrganicAiState.Broken }
        }
        check(awake is OrganicAiState.Living) { "Organic AI is not awake" }
        return machine
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}

private typealias OrganicMachineRef = MachineRef<OrganicAiState, OrganicAiIntent.Public, OrganicAiOutput>
