package io.aequicor.heartbeat.feature.checklist.impl.presentation.remote

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.checklist.api.ChecklistViewState
import io.aequicor.heartbeat.feature.checklist.impl.di.scope.ChecklistScope
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistModel
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import pro.respawn.flowmvi.dsl.collect

/** Adapts the existing screen store without exporting FlowMVI or navigation across feature APIs. */
@SingleIn(ChecklistScope::class)
@Inject
class RemoteChecklistScreen internal constructor(
    private val model: ChecklistModel,
    @ForScope(ChecklistScope::class) private val scope: ScopeHandle,
) {
    private val log = Log.tag("RemoteChecklist")
    private val json = Json { classDiscriminator = "@type" }
    private val committed = MutableStateFlow<ChecklistViewState?>(null)

    /** Committed states for this screen owner. */
    val states: Flow<ChecklistViewState> = committed.filterNotNull()

    init {
        scope.coroutineScope.launch {
            model.store.collect { states.collect { publish(it) } }
        }
    }

    /** Applies a decoded input and waits for its committed projection. */
    suspend fun dispatch(intent: JsonObject): ChecklistViewState =
        snapshot(model.dispatch(json.decodeFromJsonElement(ChecklistScreenIntent.serializer(), intent)))

    @HighFrequency
    private fun publish(state: ChecklistScreenState) {
        log.v { "child screen committed revision=${state.revision}" }
        committed.value = snapshot(state)
    }

    private fun snapshot(state: ChecklistScreenState) = ChecklistViewState(
        state.revision,
        json.encodeToJsonElement(ChecklistScreenState.serializer(), state).jsonObject,
    )
}
