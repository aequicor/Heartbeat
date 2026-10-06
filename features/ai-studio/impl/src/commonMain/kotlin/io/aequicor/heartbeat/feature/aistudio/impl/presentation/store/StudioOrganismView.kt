package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismSession
import io.aequicor.heartbeat.feature.organicai.api.sessionOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext

private typealias OrganismPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Organism chats of the studio. Their organisms come from the organic AI machine (a chat has its organism's id);
 * each open organism chat shows one of its sub-sessions live, the zygote unless the user picked another. Commands
 * go to the organic AI machine; the mode of a new chat is a studio machine choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StudioOrganismView(
    private val studio: MachineRef<AiStudioState, AiStudioIntent, AiStudioOutput>,
    private val machines: MachineRegistry,
    private val backend: StudioBackend,
    private val withAttachments: (List<MessageUi>) -> Flow<ImmutableList<MessageUi>>,
) {
    private val log = Log.tag("StudioOrganismView")
    private val selection = MutableStateFlow<Map<String, String>>(emptyMap())

    suspend fun observe(pipeline: OrganismPipeline): Unit = coroutineScope {
        launch {
            organisms().collect { organisms ->
                pipeline.updateState { copy(organisms = organisms.mapValues { it.value.toUi() }.toImmutableMap()) }
            }
        }
        launch { observeTranscripts(pipeline) }
        launch {
            StudioNativeSessions(
                studio,
                backend,
                selection,
                organisms(),
                withAttachments,
            ).observe(pipeline)
        }
    }

    suspend fun handle(pipeline: OrganismPipeline, intent: AiStudioScreenIntent.Organism): Unit = with(pipeline) {
        when (intent) {
            is AiStudioScreenIntent.SelectOrganism -> {
                log.i { "organism mode of pane ${intent.paneId}: ${intent.isEnabled}" }
                sendTo(studio, AiStudioIntent.Public.SelectOrganism(intent.paneId, intent.isEnabled))
            }

            is AiStudioScreenIntent.SelectSubSession -> {
                log.i { "show organism sub-session ${intent.key}" }
                selection.update { it + (intent.sessionId to intent.key) }
                updateState { copy(subSessions = (subSessions + (intent.sessionId to intent.key)).toImmutableMap()) }
            }

            is AiStudioScreenIntent.ControlOrganism -> {
                val organism = OrganismId(intent.sessionId)
                val command = when (intent.action) {
                    OrganismActionUi.Abort -> OrganicAiIntent.Public.Abort(organism)
                    OrganismActionUi.Resume -> OrganicAiIntent.Public.Resume(organism)
                }
                val result = machines.send(OrganicAiMachineKey, command)
                log.i { "organism ${intent.action}: $result" }
            }

            is AiStudioScreenIntent.DecideOrganism -> {
                val decision = PermissionDecision(
                    TurnId(intent.turn),
                    PermissionRequestId(intent.requestId),
                    PermissionOptionId(intent.optionId),
                )
                val command = OrganicAiIntent.Public.Decide(OrganismId(intent.sessionId), CellId(intent.cell), decision)
                val result = machines.send(OrganicAiMachineKey, command)
                log.i { "organism cell ${intent.cell} decision: $result" }
            }
        }
    }

    /** Organisms keyed by the id of their chat; empty while organic AI is off or asleep. */
    private fun organisms(): Flow<Map<String, Organism>> = machines.observe(OrganicAiMachineKey).flatMapLatest { ref ->
        ref?.state?.map { state ->
            (state as? OrganicAiState.Living)?.organisms.orEmpty().mapKeys { it.key.value }
        } ?: flowOf(emptyMap())
    }

    /** The shown sub-session of every open organism chat, re-followed only when its native session changes. */
    private suspend fun observeTranscripts(pipeline: OrganismPipeline) {
        val open = studio.state.map { state ->
            (state as? AiStudioState.Ready)?.panes?.asSequence()?.mapNotNull {
                it.sessionId
            }?.distinct()?.toList().orEmpty()
        }.distinctUntilChanged()
        combine(organisms(), open, selection) { organisms, ids, selected ->
            ids.mapNotNull { id ->
                organisms[id]?.let { organism ->
                    val key = selected[id] ?: PrimarySubSession
                    id to (key to organism.sessionOf(key))
                }
            }.toMap()
        }.distinctUntilChanged()
            .flatMapLatest(::transcriptsOf)
            .collect { views ->
                pipeline.updateState {
                    copy(
                        subTranscripts = views.mapValues { it.value.first }.toImmutableMap(),
                        subObservations = views.mapValues { it.value.second }.toImmutableMap(),
                    )
                }
            }
    }

    private fun transcriptsOf(
        shown: Map<String, Pair<String, OrganismSession?>>,
    ): Flow<Map<String, Pair<ImmutableList<MessageUi>, SubSessionObservationUi>>> = if (shown.isEmpty()) {
        flowOf(emptyMap())
    } else {
        flow {
            val views = backend.sessionViews()
            emitAll(
                combine(
                    shown.map { (chat, selected) ->
                        val (key, session) = selected
                        val messages = (session?.let { views.observe(it.ref, it.reopening) } ?: flowOf(emptyList()))
                            .flatMapLatest { items -> withAttachments(items.map { it.toUi() }) }
                        val observation = session?.let { views.observation(it.ref) } ?: flowOf(null)
                        combine(messages, observation) { items, snapshot ->
                            chat to (items to snapshot.toObservationUi(key))
                        }
                    },
                ) { it.toMap() },
            )
        }
    }
}
