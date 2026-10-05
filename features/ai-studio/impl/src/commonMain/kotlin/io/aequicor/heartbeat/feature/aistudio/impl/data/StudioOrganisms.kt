package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
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
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The organic AI machine as studio chats use it. An organism chat has the id of its organism, so the chat and the
 * organism never need a separate link. The machine runs only while organic AI is on in the profile.
 */
@Inject
internal class StudioOrganisms(
    private val machines: MachineRegistry,
    private val selections: ModelSelections,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("StudioOrganisms")

    /**
     * Saves the new chat [chat] through [save], for an organism grown from [request] when there is one. The organism
     * is conceived first and the chat saved only then, both owned by the profile, so neither a refusal (no model, a
     * too long goal, organic AI off or asleep) nor a closed screen leaves a chat without its organism; a chat that is
     * not saved aborts its organism. An organism never runs in an isolated checkout, which its turns would bypass.
     */
    suspend fun create(
        chat: StudioChatRecord,
        request: OrganismRequest?,
        isWorktree: Boolean,
        save: suspend (StudioChatRecord) -> StudioChatRecord,
    ): StudioChatRecord {
        if (request == null) return save(chat)
        require(!isWorktree) { "An organism does not run in an isolated checkout" }
        return owned("organism chat creation") {
            val machine = living()
            conceive(machine, chat, request.goal, request.settings)
            try {
                save(chat)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val aborted = machine.send(OrganicAiIntent.Public.Abort(OrganismId(chat.id)))
                log.i { "organism ${chat.id} aborted, since its chat was not saved: $aborted" }
                throw e
            }
        }
    }

    /**
     * The first prompt of an organism chat is the organism's goal. The organism of a chat created here already lives,
     * so the prompt is only accepted; an organism chat without one (created before organisms were conceived with
     * their chats) conceives it on the chat's model, project and trust. The chat never gets a native session of its
     * own. Null for an ordinary chat. Fails when organic AI is off or asleep, or with [attachments].
     */
    suspend fun conceive(
        chat: StudioChatRecord,
        goal: String,
        settings: RunSettings,
        attachments: List<ResourceRef>,
        onAccepted: suspend () -> Unit,
    ): RunOutcome? {
        if (chat.organismId == null) return null
        require(attachments.isEmpty()) { "An organism grows from a written goal alone" }
        return owned("organism conception") {
            val machine = living()
            val organisms = (machine.state.value as? OrganicAiState.Living)?.organisms.orEmpty()
            if (OrganismId(chat.id) !in organisms) conceive(machine, chat, goal, settings)
            onAccepted()
            RunOutcome.Completed
        }
    }

    private suspend fun conceive(
        machine: OrganicMachineRef,
        chat: StudioChatRecord,
        goal: String,
        settings: RunSettings,
    ) {
        val chosen = chat.configuration?.modelId ?: chat.target?.studioModelId() ?: settings.modelId
        val conception = Conception(
            OrganismId(chat.id),
            goal,
            target = target(chosen, chat.target),
            workspace = chat.executionWorkspace ?: chat.projectId?.let(::WorkspaceRef),
            trust = settings.approval.toTrust(),
        )
        val result = machine.send(OrganicAiIntent.Public.Conceive(conception))
        log.i { "organism ${conception.id.value} conception for a studio chat: $result" }
        check(result == SendResult.Accepted) { "The organism was not conceived" }
    }

    /**
     * Runs [block] in the profile, so a closed screen does not cut it in half. A failure reaches the caller, or is
     * logged here once the caller has gone, never both.
     */
    private suspend fun <T> owned(what: String, block: suspend () -> T): T {
        val caller = currentCoroutineContext().job
        return profile.coroutineScope.async {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!caller.isActive) log.w(e) { "$what failed after its screen closed" }
                Result.failure(e)
            }
        }.await().getOrThrow()
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
