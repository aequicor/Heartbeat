package io.aequicor.heartbeat.feature.checklist.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

@Immutable
internal data class ChecklistChoiceUi(val id: String, val title: String)

@Immutable
internal data class ChecklistFieldUi(
    val id: String,
    val title: String,
    val isText: Boolean,
    val isMultiple: Boolean,
    val isRequired: Boolean,
    val min: Int,
    val max: Int,
    val choices: ImmutableList<ChecklistChoiceUi>,
    val selected: ImmutableSet<String>,
    val text: String,
)

internal enum class ChecklistPhaseUi { Loading, Open, Completed, Superseded }

@Immutable
internal data class ChecklistScreenState(
    val title: String = "",
    val fields: ImmutableList<ChecklistFieldUi> = persistentListOf(),
    val phase: ChecklistPhaseUi = ChecklistPhaseUi.Loading,
    val isAutomatic: Boolean = false,
    val isCompletionAllowed: Boolean = false,
    val isSaving: Boolean = false,
    val hasFailed: Boolean = false,
    val isDeliveryFailed: Boolean = false,
    val isDeliveryPending: Boolean = false,
) : MVIState

internal sealed interface ChecklistScreenIntent : MVIIntent {
    data class Choice(val field: String, val choice: String) : ChecklistScreenIntent
    data class Text(val field: String, val text: String) : ChecklistScreenIntent
    data object Complete : ChecklistScreenIntent
    data object Retry : ChecklistScreenIntent
    data object RetryDelivery : ChecklistScreenIntent
}

internal sealed interface ChecklistScreenAction : MVIAction
