package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

/** Ratings the editor offers, 1..5. */
internal val RatingChoicesUi: List<Int> = Ratings.RANGE.toList()

/** A rating axis. */
internal enum class AxisUi { Quality, Speed, Cost }

/** A list of a configuration's traits. */
internal enum class TraitListUi { Strengths, Weaknesses, UseCases }

/** Traits being edited: ratings 1..5 and lists with one item per line. */
internal data class TraitsDraftUi(
    val quality: Int = 3,
    val speed: Int = 3,
    val cost: Int = 3,
    val strengths: String = "",
    val weaknesses: String = "",
    val useCases: String = "",
) {
    /** The rating of [axis]. */
    fun rating(axis: AxisUi): Int = when (axis) {
        AxisUi.Quality -> quality
        AxisUi.Speed -> speed
        AxisUi.Cost -> cost
    }

    /** The text of [list]. */
    fun text(list: TraitListUi): String = when (list) {
        TraitListUi.Strengths -> strengths
        TraitListUi.Weaknesses -> weaknesses
        TraitListUi.UseCases -> useCases
    }
}

/** Why a description was not saved. */
internal enum class EditorErrorUi { Invalid, Conflict, Limit, SaveFailed }

/**
 * The description editor of one model. [selected] is the edited configuration: null for the base (default effort),
 * else an advertised effort; [variants] holds efforts described separately.
 */
@Immutable
internal data class ProfileEditorState(
    val isLoaded: Boolean = false,
    val title: String = "",
    val engine: String = "",
    val efforts: ImmutableList<String> = persistentListOf(),
    val selected: String? = null,
    val summary: String = "",
    val tags: String = "",
    val base: TraitsDraftUi = TraitsDraftUi(),
    val variants: ImmutableMap<String, TraitsDraftUi> = persistentMapOf(),
    val sources: ImmutableList<String> = persistentListOf(),
    val isDescribed: Boolean = false,
    val isResearched: Boolean = false,
    val error: EditorErrorUi? = null,
) : MVIState {
    /** The traits of the selected configuration: its variant, else the base. */
    val current: TraitsDraftUi get() = selected?.let { variants[it] } ?: base
}

/** User actions of the editor. */
internal sealed interface ProfileEditorIntent : MVIIntent {
    /** Edits the base (null) or the configuration at [effort]. */
    data class SelectConfiguration(val effort: String?) : ProfileEditorIntent

    /** Describes the selected effort separately or lets it follow the base. */
    data class SetSeparate(val isSeparate: Boolean) : ProfileEditorIntent

    /** Edits the summary. */
    data class ChangeSummary(val text: String) : ProfileEditorIntent

    /** Edits the tags, separated by commas. */
    data class ChangeTags(val text: String) : ProfileEditorIntent

    /** Rates the selected configuration on [axis]. */
    data class ChangeRating(val axis: AxisUi, val value: Int) : ProfileEditorIntent

    /** Edits a list of the selected configuration. */
    data class ChangeList(val list: TraitListUi, val text: String) : ProfileEditorIntent

    /** Saves the description and closes the editor. */
    data object Save : ProfileEditorIntent

    /** Deletes the description and closes the editor. */
    data object Delete : ProfileEditorIntent

    /** Hides the error. */
    data object DismissError : ProfileEditorIntent
}

/** Reserved contract for one-off editor actions. */
internal sealed interface ProfileEditorAction : MVIAction
