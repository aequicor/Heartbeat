package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveModels
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ConnectionCost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.CostMode
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesOutput
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.RatingSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchFailure
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ResearchSubject
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionContext
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SelectionOption
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentHost
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPreferences
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentPriority
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SubagentRequest
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.selection.normalizedModel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private typealias SubagentsPipeline =
    PipelineContext<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>

/**
 * The section store. It mirrors the library machine and the enabled models; every change goes to the machine, which
 * persists it. The store keeps only banners, a pending confirmation and the trial selection.
 */
internal class SubagentsModel(
    private val machine: LibraryMachine,
    directory: Flow<Directory>,
    private val adaptive: AdaptiveModels,
    isResearchEnabled: Flow<Boolean>,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val openEditor: (ModelKey) -> Unit = {},
) {
    private var known: Directory = Directory()

    /** The screen store, retained for the lifetime of this screen. */
    val store = factory.create<SubagentsScreenState, SubagentsScreenIntent, SubagentsScreenAction>(
        "AdaptiveSubagents",
        SubagentsScreenState().reflectLibrary(machine.state.value, known),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            when {
                output == ModelProfilesOutput.StorageFailed && machine.state.value is ModelProfilesState.Ready ->
                    updateState { copy(error = SubagentsErrorUi.SaveFailed) }

                output is ModelProfilesOutput.ResearchFinished -> output.failure?.toUi()?.let { failure ->
                    updateState { copy(researchFailure = failure) }
                }
            }
        }) { reflectLibrary(it, known) }
        whileSubscribed {
            isResearchEnabled.collect { updateState { copy(isResearchEnabled = it) } }
        }
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

            is SubagentsScreenIntent.ResearchIntent -> handleResearch(intent)

            is SubagentsScreenIntent.TrialIntent -> handleTrial(intent)

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
    private suspend fun SubagentsPipeline.handleResearch(intent: SubagentsScreenIntent.ResearchIntent) {
        when (intent) {
            is SubagentsScreenIntent.Research -> withState {
                if (entries.firstOrNull { it.id == intent.id }?.description == DescriptionUi.Manual) {
                    updateState { copy(confirming = intent.id) }
                } else {
                    research(intent.id)
                }
            }

            SubagentsScreenIntent.ConfirmResearch -> withState {
                val id = confirming
                updateState { copy(confirming = null) }
                if (id != null) research(id)
            }

            SubagentsScreenIntent.CancelConfirm -> updateState { copy(confirming = null) }

            is SubagentsScreenIntent.CancelResearch ->
                sendTo(machine, ModelProfilesIntent.Public.CancelResearch(intent.id.toKey()))

            SubagentsScreenIntent.DismissResearchFailure -> updateState { copy(researchFailure = null) }

            is SubagentsScreenIntent.Edit -> openEditor(intent.id.toKey())
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SubagentsPipeline.handleTrial(intent: SubagentsScreenIntent.TrialIntent) {
        when (intent) {
            is SubagentsScreenIntent.ChangeTrialTask -> updateState { copy(trial = trial.copy(task = intent.task)) }

            is SubagentsScreenIntent.SelectTrialPriority ->
                updateState { copy(trial = trial.copy(priority = intent.priority)) }

            SubagentsScreenIntent.RunTrial -> runTrial()
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SubagentsPipeline.research(id: String) {
        val ready = machine.state.value as? ModelProfilesState.Ready ?: return
        val key = id.toKey()
        val model = known.models.firstOrNull { ModelKey.of(it.target) == key } ?: return
        val researcher = ready.preferences.researcherTarget ?: known.defaultTarget ?: return
        val subject = ResearchSubject(key, model.title, model.efforts, model.defaultEffort)
        sendTo(machine, ModelProfilesIntent.Public.Research(Uuid.random().toString(), subject, researcher))
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun SubagentsPipeline.runTrial() = withState {
        val task = trial.task.trim()
        if (task.isEmpty() || trial.isRunning) return@withState
        updateState { copy(trial = trial.copy(isRunning = true)) }
        val request = SubagentRequest(task, trial.priority.toDomain())
        val options = adaptive.preview(request, SelectionContext(SubagentHost.StudioHelper, null, null))
        val rows = options.map { it.toUi() }.toImmutableList()
        updateState { copy(trial = trial.copy(options = rows, isRunning = false, isTried = true)) }
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
            entries = entriesOf(state, directory),
            hasResearcher = (preferences.researcherTarget ?: directory.defaultTarget) != null,
            confirming = confirming?.takeIf { id -> directory.models.any { ModelKey.of(it.target).uiId() == id } },
            error = error.takeUnless { it == SubagentsErrorUi.LoadFailed },
        )
    }
}

/** One row per enabled model of an engine, with its description source and running research. */
private fun entriesOf(state: ModelProfilesState.Ready, directory: Directory) =
    directory.models.distinctBy { ModelKey.of(it.target) }.map { model ->
        val key = ModelKey.of(model.target)
        val own = state.profileOf(key)
        val isBorrowed = own == null &&
            state.profiles.any { normalizedModel(it.key.model) == normalizedModel(key.model) }
        val job = state.researchOf(key)
        ModelEntryUi(
            key.uiId(),
            model.title,
            model.engineTitle,
            when {
                own?.source == ProfileSource.Manual -> DescriptionUi.Manual
                own != null -> DescriptionUi.Researched
                isBorrowed -> DescriptionUi.Borrowed
                else -> DescriptionUi.Estimated
            },
            own?.base?.ratings?.text(),
            job?.stage?.let { StageUi.valueOf(it.name) },
            job?.sources ?: 0,
        )
    }.toImmutableList()

private fun SelectionOption.toUi() = TrialOptionUi(
    title,
    reasoningEffort,
    ratings.text(),
    ((score * SCORE_DIGITS).roundToInt() / SCORE_DIGITS).toString(),
    when (ratingSource) {
        RatingSource.Described -> DescriptionUi.Researched
        RatingSource.Borrowed -> DescriptionUi.Borrowed
        RatingSource.Heuristic -> DescriptionUi.Estimated
    },
)

private fun Ratings.text(): String = "$quality · $speed · $cost"

private fun ResearchFailure.toUi(): ResearchFailureUi? = when (this) {
    ResearchFailure.Cancelled -> null
    ResearchFailure.Busy -> ResearchFailureUi.Busy
    ResearchFailure.NoSources -> ResearchFailureUi.NoSources
    ResearchFailure.ResearcherUnavailable -> ResearchFailureUi.ResearcherUnavailable
    ResearchFailure.InvalidAnswer -> ResearchFailureUi.InvalidAnswer
    ResearchFailure.Failed -> ResearchFailureUi.Failed
}

/** An opaque screen id of a model of an engine. */
internal fun ModelKey.uiId(): String = Json.encodeToString(ModelKey.serializer(), this)

private fun String.toKey(): ModelKey = Json.decodeFromString(ModelKey.serializer(), this)

private const val SCORE_DIGITS = 100.0

/** An opaque screen id of a model route. */
internal fun EngineTarget.uiId(): String = Json.encodeToString(EngineTarget.serializer(), this)

private fun String.toTarget(): EngineTarget = Json.decodeFromString(EngineTarget.serializer(), this)

private fun SubagentPriority.toUi(): PriorityUi = PriorityUi.valueOf(name)

private fun PriorityUi.toDomain(): SubagentPriority = SubagentPriority.valueOf(name)

private fun CostMode.toUi(): CostModeUi = CostModeUi.valueOf(name)

private fun CostModeUi.toDomain(): CostMode = CostMode.valueOf(name)
