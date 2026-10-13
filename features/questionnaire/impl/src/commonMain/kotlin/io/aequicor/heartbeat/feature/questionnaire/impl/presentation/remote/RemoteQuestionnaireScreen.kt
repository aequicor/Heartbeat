package io.aequicor.heartbeat.feature.questionnaire.impl.presentation.remote

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireViewState
import io.aequicor.heartbeat.feature.questionnaire.impl.di.scope.QuestionnaireScope
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireModel
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.respawn.flowmvi.dsl.collect

/** Adapts the existing screen store without exporting FlowMVI or navigation across feature APIs. */
@SingleIn(QuestionnaireScope::class)
@Inject
class RemoteQuestionnaireScreen internal constructor(
    private val model: QuestionnaireModel,
    @ForScope(QuestionnaireScope::class) private val scope: ScopeHandle,
) {
    private val log = Log.tag("RemoteQuestionnaire")
    private val json = Json { classDiscriminator = "@type" }
    private val committed = MutableStateFlow<QuestionnaireViewState?>(null)

    /** Committed states for this screen owner. */
    val states: Flow<QuestionnaireViewState> = committed.filterNotNull()

    init {
        scope.coroutineScope.launch {
            model.store.collect { states.collect { publish(it) } }
        }
    }

    /** Applies a decoded input and waits for its committed projection. */
    suspend fun dispatch(intent: JsonObject): QuestionnaireViewState =
        snapshot(model.dispatch(json.decodeFromJsonElement(QuestionnaireScreenIntent.serializer(), intent)))

    @HighFrequency
    private fun publish(state: QuestionnaireScreenState) {
        log.v { "child screen committed revision=${state.revision}" }
        committed.value = snapshot(state)
    }

    private fun snapshot(state: QuestionnaireScreenState) = QuestionnaireViewState(
        state.revision,
        json.encodeToJsonElement(QuestionnaireScreenState.serializer(), state).jsonObject,
    )
}
