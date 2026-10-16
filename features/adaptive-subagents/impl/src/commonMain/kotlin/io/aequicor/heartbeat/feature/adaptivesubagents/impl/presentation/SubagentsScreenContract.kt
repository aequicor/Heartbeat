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

    /** Reads the library again after a failure. */
    data object Reload : SubagentsScreenIntent

    /** Hides a save error. */
    data object DismissError : SubagentsScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface SubagentsScreenAction : MVIAction
