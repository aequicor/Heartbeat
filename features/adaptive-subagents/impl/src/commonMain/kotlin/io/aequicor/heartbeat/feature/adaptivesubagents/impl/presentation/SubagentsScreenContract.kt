package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** What a subagent is optimised for when the agent names nothing. */
internal enum class PriorityUi { Quality, Balanced, Speed, Cost }

/** How a connection is billed. */
internal enum class CostModeUi { Metered, Subscription, Local, Free }

/** An enabled model offered for service tasks; [id] is opaque to the screen. */
internal data class ModelOptionUi(val id: String, val title: String)

/** An enabled connection and its billing. */
internal data class ConnectionUi(val id: String, val title: String, val cost: CostModeUi)

/** Where the ratings of a model come from. */
internal enum class DescriptionUi { Manual, Researched, Borrowed, Estimated }

/** Where a running research is. */
internal enum class StageUi { Queued, Facts, Searching, Reading, Writing }

/** Why a research produced no description. */
internal enum class ResearchFailureUi { Busy, NoSources, ResearcherUnavailable, InvalidAnswer, Failed }

/**
 * An enabled model in the library list: its [description] source, ratings "quality · speed · cost" when described,
 * and the running research [stage] with pages read.
 */
internal data class ModelEntryUi(
    val id: String,
    val title: String,
    val engine: String,
    val description: DescriptionUi,
    val ratings: String? = null,
    val stage: StageUi? = null,
    val sources: Int = 0,
)

/** A configuration of the trial selection. */
internal data class TrialOptionUi(
    val title: String,
    val effort: String?,
    val ratings: String,
    val score: String,
    val source: DescriptionUi,
)

/** The trial selection: a task and priority, and the scored shortlist it gets. */
internal data class TrialUi(
    val task: String = "",
    val priority: PriorityUi = PriorityUi.Balanced,
    val options: ImmutableList<TrialOptionUi> = persistentListOf(),
    val isRunning: Boolean = false,
    val isTried: Boolean = false,
)

/** Why the library cannot be shown or changed. */
internal enum class SubagentsErrorUi {
    /** The stored library could not be read; nothing can change until it loads. */
    LoadFailed,

    /** The last change could not be written and lives only until the profile closes. */
    SaveFailed,
}

/**
 * The adaptive subagents section: selection preferences, the models of the router and the researcher (null — the
 * profile's default model), and connection billing.
 */
@Immutable
internal data class SubagentsScreenState(
    val isLoaded: Boolean = false,
    val priority: PriorityUi = PriorityUi.Balanced,
    val isCrossEngineAllowed: Boolean = true,
    val routerModel: String? = null,
    val researcherModel: String? = null,
    val models: ImmutableList<ModelOptionUi> = persistentListOf(),
    val connections: ImmutableList<ConnectionUi> = persistentListOf(),
    val entries: ImmutableList<ModelEntryUi> = persistentListOf(),
    val isResearchEnabled: Boolean = false,
    val hasResearcher: Boolean = false,
    val confirming: String? = null,
    val researchFailure: ResearchFailureUi? = null,
    val trial: TrialUi = TrialUi(),
    val error: SubagentsErrorUi? = null,
) : MVIState

/** User actions of the section. */
internal sealed interface SubagentsScreenIntent : MVIIntent {
    /** Chooses the default priority. */
    data class SelectPriority(val priority: PriorityUi) : SubagentsScreenIntent

    /** Allows or forbids subagents on another engine than their parent. */
    data class SetCrossEngine(val isAllowed: Boolean) : SubagentsScreenIntent

    /** Chooses the router model; null — the profile's default. */
    data class SelectRouterModel(val id: String?) : SubagentsScreenIntent

    /** Chooses the researcher model; null — the profile's default. */
    data class SelectResearcherModel(val id: String?) : SubagentsScreenIntent

    /** Sets the billing of connection [id]. */
    data class SelectCost(val id: String, val cost: CostModeUi) : SubagentsScreenIntent

    /** Actions of the model list's research. */
    sealed interface ResearchIntent : SubagentsScreenIntent

    /** Researches a description of model [id]; an edited description asks for confirmation first. */
    data class Research(val id: String) : ResearchIntent

    /** Confirms replacing an edited description by research. */
    data object ConfirmResearch : ResearchIntent

    /** Keeps the edited description. */
    data object CancelConfirm : ResearchIntent

    /** Stops the research of model [id]. */
    data class CancelResearch(val id: String) : ResearchIntent

    /** Hides a research failure. */
    data object DismissResearchFailure : ResearchIntent

    /** Actions of the trial selection. */
    sealed interface TrialIntent : SubagentsScreenIntent

    /** Edits the task of the trial selection. */
    data class ChangeTrialTask(val task: String) : TrialIntent

    /** Chooses the priority of the trial selection. */
    data class SelectTrialPriority(val priority: PriorityUi) : TrialIntent

    /** Runs the trial selection. */
    data object RunTrial : TrialIntent

    /** Reads the library again after a failure. */
    data object Reload : SubagentsScreenIntent

    /** Hides a save error. */
    data object DismissError : SubagentsScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface SubagentsScreenAction : MVIAction
