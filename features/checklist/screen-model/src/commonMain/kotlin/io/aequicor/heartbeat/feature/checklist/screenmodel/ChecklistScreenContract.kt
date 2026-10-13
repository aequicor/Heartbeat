@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class)

package io.aequicor.heartbeat.feature.checklist.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** One offered answer. */
@Serializable
@Immutable
data class ChecklistChoiceUi(val id: String, val title: String)

/** A field with its current draft and selection bounds. */
@Serializable
@Immutable
data class ChecklistFieldUi(
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

/** Lifecycle of the displayed card. */
@Serializable
enum class ChecklistPhaseUi { Loading, Open, Completed, Superseded }

/** Display state, including durable save and delivery progress from the owning app. */
@Serializable
@Immutable
data class ChecklistScreenState(
    val title: String = "",
    val fields: ImmutableList<ChecklistFieldUi> = persistentListOf(),
    val phase: ChecklistPhaseUi = ChecklistPhaseUi.Loading,
    val isAutomatic: Boolean = false,
    val isCompletionAllowed: Boolean = false,
    val isSaving: Boolean = false,
    val hasFailed: Boolean = false,
    val isDeliveryFailed: Boolean = false,
    val isDeliveryPending: Boolean = false,
    /** Host-local commit revision; the remote handle sends it in its envelope. */
    @Transient val revision: Long = 0,
) : MVIState

/** User input; completion and delivery are confirmed by the owning app. */
@Serializable
sealed interface ChecklistScreenIntent : MVIIntent {
    /** Selects or toggles a choice. */
    @Serializable
    data class Choice(val field: String, val choice: String) : ChecklistScreenIntent

    /** Replaces the text draft. */
    @Serializable
    data class Text(val field: String, val text: String) : ChecklistScreenIntent

    /** Completes a manually submitted card. */
    @Serializable
    data object Complete : ChecklistScreenIntent

    /** Retries a failed save. */
    @Serializable
    data object Retry : ChecklistScreenIntent

    /** Retries delivery of the completed card. */
    @Serializable
    data object RetryDelivery : ChecklistScreenIntent
}

/** The screen has no one-off actions. */
sealed interface ChecklistScreenAction : MVIAction
