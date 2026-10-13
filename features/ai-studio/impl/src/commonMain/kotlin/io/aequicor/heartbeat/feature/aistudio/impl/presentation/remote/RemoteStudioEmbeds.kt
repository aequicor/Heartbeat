package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEntries
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioChecklistView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioEmbedViews
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioQuestionView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRemoteScreens
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenState
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRemoteScreens
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/** Child snapshots are merged into the parent before acknowledging their input. */
interface StudioEmbeds {
    /** Full child set, ordered by its revision including removals. */
    val states: Flow<StudioEmbedViews>

    /** Applies input to its exact child instance and returns the current child set after the barrier. */
    suspend fun dispatch(input: AiStudioScreenIntent.Embed): StudioEmbedViews

    /** A native or test screen without remote children. */
    object None : StudioEmbeds {
        override val states: Flow<StudioEmbedViews> = flowOf(StudioEmbedViews())
        override suspend fun dispatch(input: AiStudioScreenIntent.Embed): StudioEmbedViews = StudioEmbedViews()
    }
}

/** Owns independent child handles for one remote view. All access runs on the scope's main dispatcher. */
@SingleIn(AiStudioScope::class)
@ContributesBinding(AiStudioScope::class)
@Inject
internal class RemoteStudioEmbeds(
    entries: StudioEntries,
    host: StudioScreenHost,
    private val questions: QuestionnaireRemoteScreens,
    private val checklists: ChecklistRemoteScreens,
    @ForScope(AiStudioScope::class) private val scope: ScopeHandle,
) : StudioEmbeds {
    private val log = Log.tag("RemoteStudioEmbeds")
    private val current = mutableMapOf<Key, Child>()
    private val retiring = mutableSetOf<Child>()
    private val committed = MutableStateFlow(StudioEmbedViews())
    override val states: Flow<StudioEmbedViews> = committed

    init {
        scope.onClose {
            (current.values + retiring).forEach { it.close() }
            current.clear()
            retiring.clear()
            log.i { "remote children closed" }
        }
        if (host == StudioScreenHost.Remote) {
            scope.coroutineScope.launch { entries.questionSources.collect { follow(Kind.Question, it) } }
            scope.coroutineScope.launch { entries.checklistIds.collect { follow(Kind.Checklist, it) } }
        }
    }

    override suspend fun dispatch(input: AiStudioScreenIntent.Embed): StudioEmbedViews {
        val key: Key
        val instance: String
        val intent: JsonObject
        when (input) {
            is AiStudioScreenIntent.QuestionInput -> {
                key = Key(Kind.Question, input.source)
                instance = input.instance
                intent = StudioViewJson.encodeToJsonElement(
                    QuestionnaireScreenIntent.serializer(),
                    input.input,
                ).jsonObject
            }

            is AiStudioScreenIntent.ChecklistInput -> {
                key = Key(Kind.Checklist, input.id)
                instance = input.instance
                intent = StudioViewJson.encodeToJsonElement(ChecklistScreenIntent.serializer(), input.input).jsonObject
            }
        }
        val child = current[key]?.takeIf { it.instance == instance } ?: return committed.value
        child.inFlight++
        try {
            accept(key, child, child.dispatch(intent))
        } finally {
            child.inFlight--
            if (child.inFlight == 0 && retiring.remove(child)) child.close()
        }
        return committed.value
    }

    private fun follow(kind: Kind, ids: Set<String>) {
        val removed = current.keys.filter { it.kind == kind && it.id !in ids }
        removed.forEach { key ->
            val child = checkNotNull(current.remove(key))
            child.reader?.cancel()
            // A question may disappear as a consequence of its own answer. Let that reducer finish its barrier.
            if (child.inFlight == 0) child.close() else retiring += child
        }
        if (removed.isNotEmpty()) {
            val state = committed.value
            publish(
                state.copy(
                    questions = state.questions.filterKeys { Key(Kind.Question, it) !in removed }.toImmutableMap(),
                    checklists = state.checklists.filterKeys { Key(Kind.Checklist, it) !in removed }.toImmutableMap(),
                ),
            )
        }
        ids.forEach { id ->
            val key = Key(kind, id)
            if (key !in current) {
                val child = open(key)
                current[key] = child
                child.reader = scope.coroutineScope.launch { child.states.collect { accept(key, child, it) } }
            }
        }
        log.d { "child set updated kind=$kind size=${ids.size}" }
    }

    private fun open(key: Key): Child = when (key.kind) {
        Kind.Question -> {
            val handle = questions.open(key.id)
            Child(
                handle.states.map { Projection(it.revision, it.screen) },
                { intent -> handle.dispatch(intent).let { Projection(it.revision, it.screen) } },
                handle::close,
            )
        }

        Kind.Checklist -> {
            val handle = checklists.open(key.id)
            Child(
                handle.states.map { Projection(it.revision, it.screen) },
                { intent -> handle.dispatch(intent).let { Projection(it.revision, it.screen) } },
                handle::close,
            )
        }
    }

    @HighFrequency
    private fun accept(key: Key, child: Child, snapshot: Projection) {
        if (current[key] !== child || snapshot.revision <= child.revision) return
        child.revision = snapshot.revision
        val state = committed.value
        val next = when (key.kind) {
            Kind.Question -> {
                val view = StudioQuestionView(
                    child.instance,
                    snapshot.revision,
                    StudioViewJson.decodeFromJsonElement(QuestionnaireScreenState.serializer(), snapshot.screen),
                )
                state.copy(questions = (state.questions + (key.id to view)).toImmutableMap())
            }

            Kind.Checklist -> {
                val view = StudioChecklistView(
                    child.instance,
                    snapshot.revision,
                    StudioViewJson.decodeFromJsonElement(ChecklistScreenState.serializer(), snapshot.screen),
                )
                state.copy(checklists = (state.checklists + (key.id to view)).toImmutableMap())
            }
        }
        publish(next)
    }

    @HighFrequency
    private fun publish(state: StudioEmbedViews) {
        val next = state.copy(revision = committed.value.revision + 1)
        log.v { "child projections committed revision=${next.revision}" }
        committed.value = next
    }

    private enum class Kind { Question, Checklist }
    private data class Key(val kind: Kind, val id: String)
    private data class Projection(val revision: Long, val screen: JsonObject)
    private class Child(
        val states: Flow<Projection>,
        val dispatch: suspend (JsonObject) -> Projection,
        private val dispose: () -> Unit,
    ) {
        val instance: String = Uuid.random().toString()
        var revision = -1L
        var inFlight = 0
        var reader: Job? = null
        fun close() = dispose()
    }
}
