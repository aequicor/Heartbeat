package io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelKey
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfile
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesOutput
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileSource
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ProfileVariant
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Ratings
import io.aequicor.heartbeat.feature.adaptivesubagents.api.SaveRejection
import io.aequicor.heartbeat.feature.adaptivesubagents.api.Traits
import io.aequicor.heartbeat.feature.adaptivesubagents.api.normalized
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.Directory
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain.LibraryMachine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock

private typealias EditorPipeline = PipelineContext<ProfileEditorState, ProfileEditorIntent, ProfileEditorAction>

/**
 * The description editor of [modelKey]. The draft is filled once from the stored description (or the catalog title when
 * there is none) and then belongs to the screen; saving sends it to the library with the revision it started from,
 * and a refusal keeps the editor open with the reason.
 */
internal class ProfileEditorModel(
    private val machine: LibraryMachine,
    private val modelKey: ModelKey,
    directory: Flow<Directory>,
    private val onClose: () -> Unit,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private var startRevision: Long? = null
    private var stored: ModelProfile? = null

    /** The editor store, retained for the lifetime of this screen. */
    val store = factory.create<ProfileEditorState, ProfileEditorIntent, ProfileEditorAction>(
        "ModelProfileEditor",
        ProfileEditorState(),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output ->
            val error = when {
                output is ModelProfilesOutput.Rejected && output.key == modelKey -> output.reason.toUi()
                output == ModelProfilesOutput.StorageFailed -> EditorErrorUi.SaveFailed
                else -> null
            }
            if (error != null) updateState { copy(error = error) }
        }) { state -> if (!isLoaded) filled(state) else this }
        whileSubscribed {
            directory.collect { found ->
                val model = found.models.firstOrNull { ModelKey.of(it.target) == modelKey } ?: return@collect
                updateState {
                    copy(
                        title = title.ifEmpty { model.title },
                        engine = model.engineTitle,
                        efforts = model.efforts.toImmutableList(),
                    )
                }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    /** The draft filled from the stored description once the library is ready. */
    private fun ProfileEditorState.filled(state: ModelProfilesState): ProfileEditorState {
        val ready = state as? ModelProfilesState.Ready ?: return this
        val profile = ready.profileOf(modelKey)
        stored = profile
        startRevision = profile?.revision
        return copy(
            isLoaded = true,
            title = profile?.title ?: title,
            summary = profile?.summary.orEmpty(),
            tags = profile?.tags.orEmpty().joinToString(", "),
            base = profile?.base?.toDraft() ?: TraitsDraftUi(),
            variants = profile?.variants.orEmpty().associate { it.effort to it.traits.toDraft() }.toImmutableMap(),
            sources = profile?.sources.orEmpty().toImmutableList(),
            isDescribed = profile != null,
            isResearched = profile?.source == ProfileSource.Research,
        )
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun EditorPipeline.handle(intent: ProfileEditorIntent) {
        when (intent) {
            is ProfileEditorIntent.SelectConfiguration -> updateState { copy(selected = intent.effort) }

            is ProfileEditorIntent.SetSeparate -> updateState {
                val effort = selected ?: return@updateState this
                val next = if (intent.isSeparate) variants + (effort to base) else variants - effort
                copy(variants = next.toPersistentMap())
            }

            is ProfileEditorIntent.ChangeSummary -> updateState { copy(summary = intent.text) }

            is ProfileEditorIntent.ChangeTags -> updateState { copy(tags = intent.text) }

            is ProfileEditorIntent.ChangeRating -> updateState { edited { it.rated(intent.axis, intent.value) } }

            is ProfileEditorIntent.ChangeList -> updateState { edited { it.listed(intent.list, intent.text) } }

            ProfileEditorIntent.Save -> save()

            ProfileEditorIntent.Delete -> {
                sendTo(machine, ModelProfilesIntent.Public.Delete(modelKey))
                onClose()
            }

            ProfileEditorIntent.DismissError -> updateState { copy(error = null) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun EditorPipeline.save() = withState {
        val profile = ModelProfile(
            modelKey,
            title.trim().ifEmpty { modelKey.model.value },
            summary,
            tags.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet(),
            base.toTraits(),
            variants.filterKeys { it in efforts }.map { (effort, draft) -> ProfileVariant(effort, draft.toTraits()) },
            stored?.facts,
            ProfileSource.Manual,
            sources,
            clock.now(),
        )
        updateState { copy(error = null) }
        sendTo(machine, ModelProfilesIntent.Public.Save(profile, startRevision))
        // The library stores exactly the normalized draft with the next revision; anything else was refused.
        val expected = profile.normalized().copy(revision = (startRevision ?: 0) + 1)
        if ((machine.state.value as? ModelProfilesState.Ready)?.profileOf(modelKey) == expected) onClose()
    }
}

private fun ProfileEditorState.edited(change: (TraitsDraftUi) -> TraitsDraftUi): ProfileEditorState {
    val effort = selected
    return if (effort != null && effort in variants) {
        copy(variants = (variants + (effort to change(variants.getValue(effort)))).toPersistentMap())
    } else {
        copy(base = change(base))
    }
}

private fun TraitsDraftUi.rated(axis: AxisUi, value: Int): TraitsDraftUi {
    val rating = value.coerceIn(Ratings.RANGE)
    return when (axis) {
        AxisUi.Quality -> copy(quality = rating)
        AxisUi.Speed -> copy(speed = rating)
        AxisUi.Cost -> copy(cost = rating)
    }
}

private fun TraitsDraftUi.listed(list: TraitListUi, text: String): TraitsDraftUi = when (list) {
    TraitListUi.Strengths -> copy(strengths = text)
    TraitListUi.Weaknesses -> copy(weaknesses = text)
    TraitListUi.UseCases -> copy(useCases = text)
}

private fun Traits.toDraft() = TraitsDraftUi(
    ratings.quality,
    ratings.speed,
    ratings.cost,
    strengths.joinToString("\n"),
    weaknesses.joinToString("\n"),
    useCases.joinToString("\n"),
)

private fun TraitsDraftUi.toTraits() = Traits(
    Ratings(quality, speed, cost),
    strengths.lines(),
    weaknesses.lines(),
    useCases.lines(),
)

private fun SaveRejection.toUi(): EditorErrorUi = when (this) {
    SaveRejection.Invalid -> EditorErrorUi.Invalid
    SaveRejection.Conflict -> EditorErrorUi.Conflict
    SaveRejection.Limit -> EditorErrorUi.Limit
}
