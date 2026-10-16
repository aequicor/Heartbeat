package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ConnectionCost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesOutput
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias SubagentsPipeline =
    PipelineContext<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>

/**
 * The section store. It mirrors the library machine and the enabled models; every change goes to the machine, which
 * persists it. The store keeps only the error banner.
 */
internal class SubagentsModel(
    private val machine: LibraryMachine,
    directory: Flow<Directory>,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private var known: Directory = Directory()

    /** The screen store, retained for the lifetime of this screen. */
    val store = factory.create<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>(
        "AdaptiveSubagents",
        SubagentsScreenState().reflectLibrary(machine.state.value, known),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            if (output == ModelProfilesOutput.StorageFailed && machine.state.value is ModelProfilesState.Ready) {
                updateState { copy(error = SubagentsErrorUi.SaveFailed) }
            }
        }) { reflectLibrary(it, known) }
        whileSubscribed {
            directory.collect { found ->
                known = found
                updateState { reflectLibrary(machine.state.value, found) }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SubagentsPipeline.handle(intent: SubagentsScreenIntent) {
        when (intent) {
            is SubagentsScreenIntent.SelectPriority -> change { copy(defaultPriority = intent.priority.toDomain()) }

            is SubagentsScreenIntent.SetCrossEngine -> change { copy(isCrossEngineAllowed = intent.isAllowed) }

            is SubagentsScreenIntent.SelectRouterModel -> change { copy(routerTarget = intent.id?.toTarget()) }

            is SubagentsScreenIntent.SelectResearcherModel -> change { copy(researcherTarget = intent.id?.toTarget()) }

            is SubagentsScreenIntent.SelectCost -> change {
                val binding = EngineBindingId(intent.id)
                copy(
                    connectionCosts = connectionCosts.filterNot { it.binding == binding } +
                        ConnectionCost(binding, intent.cost.toDomain()),
                )
            }

            SubagentsScreenIntent.Reload -> {
                updateState { copy(error = null) }
                sendTo(machine, ModelProfilesIntent.Public.Reload)
            }

            SubagentsScreenIntent.DismissError ->
                updateState { copy(error = error.takeIf { it == SubagentsErrorUi.LoadFailed }) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SubagentsPipeline.change(update: SubagentPreferences.() -> SubagentPreferences) {
        val ready = machine.state.value as? ModelProfilesState.Ready ?: return
        sendTo(machine, ModelProfilesIntent.Public.SetPreferences(ready.preferences.update()))
    }
}

/** Library and directory mirrored into the screen. */
internal fun SubagentsScreenState.reflectLibrary(
    state: ModelProfilesState,
    directory: Directory,
): SubagentsScreenState = when (state) {
    ModelProfilesState.Idle, ModelProfilesState.Loading -> copy(isLoaded = false)

    ModelProfilesState.Failed -> copy(isLoaded = false, error = SubagentsErrorUi.LoadFailed)

    is ModelProfilesState.Ready -> {
        val preferences = state.preferences
        copy(
            isLoaded = true,
            priority = preferences.defaultPriority.toUi(),
            isCrossEngineAllowed = preferences.isCrossEngineAllowed,
            routerModel = preferences.routerTarget?.uiId(),
            researcherModel = preferences.researcherTarget?.uiId(),
            models = directory.models.map { ModelOptionUi(it.target.uiId(), "${it.title} · ${it.connection}") }
                .toImmutableList(),
            connections = directory.connections.map {
                ConnectionUi(it.binding.value, it.title, preferences.costOf(it.binding).toUi())
            }.toImmutableList(),
            error = error.takeUnless { it == SubagentsErrorUi.LoadFailed },
        )
    }
}

/** An opaque screen id of a model route. */
internal fun EngineTarget.uiId(): String = Json.encodeToString(EngineTarget.serializer(), this)

private fun String.toTarget(): EngineTarget = Json.decodeFromString(EngineTarget.serializer(), this)

private fun SubagentPriority.toUi(): PriorityUi = PriorityUi.valueOf(name)

private fun PriorityUi.toDomain(): SubagentPriority = SubagentPriority.valueOf(name)

private fun CostMode.toUi(): CostModeUi = CostModeUi.valueOf(name)

private fun CostModeUi.toDomain(): CostMode = CostMode.valueOf(name)
