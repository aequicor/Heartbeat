package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The organic AI machine as studio chats use it. An organism chat has the id of its organism, so the chat and the
 * organism never need a separate link. The machine runs only while organic AI is on in the profile.
 */
@Inject
internal class StudioOrganisms(private val machines: MachineRegistry, private val selections: ModelSelections) {
    private val log = Log.tag("StudioOrganisms")

    /**
     * The first prompt of an organism chat is the organism's goal: conceives the organism of [chat] on the chat's
     * model, project and trust and reports it accepted; the chat never gets a native session of its own. Null for an
     * ordinary chat. Fails when organic AI is off or asleep, or when the chat already has its organism.
     */
    suspend fun conceive(
        chat: StudioChatRecord,
        goal: String,
        settings: RunSettings,
        onAccepted: suspend () -> Unit,
    ): RunOutcome? {
        if (chat.organismId == null) return null
        val selected = selections.observe().first()
        val chosen = chat.configuration?.modelId ?: chat.target?.studioModelId() ?: settings.modelId
        val target = requireNotNull(turnTarget(chosen, chat.target, selected.defaultTarget)) {
            "Select a connected model in settings"
        }
        check(selected.isEnabled(target)) { "The selected route is no longer enabled" }
        val conception = Conception(
            OrganismId(chat.id),
            goal,
            target = target,
            workspace = chat.executionWorkspace ?: chat.projectId?.let(::WorkspaceRef),
            trust = settings.approval.toTrust(),
        )
        val machine = withTimeoutOrNull(WAIT_MILLIS) {
            machines.observe(OrganicAiMachineKey).filterNotNull().first()
        } ?: error("Organic AI is turned off")
        val awake = withTimeoutOrNull(WAIT_MILLIS) {
            machine.state.first { it is OrganicAiState.Living || it == OrganicAiState.Broken }
        }
        check(awake is OrganicAiState.Living) { "Organic AI is not awake" }
        val result = machine.send(OrganicAiIntent.Public.Conceive(conception))
        log.i { "organism ${conception.id.value} conception for a studio chat: $result" }
        check(result == SendResult.Accepted) { "The organism was not conceived" }
        onAccepted()
        return RunOutcome.Completed
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
