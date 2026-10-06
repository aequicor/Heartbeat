package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Reconciles native snapshots with the same retained history; reattachment never seeds a second transcript. */
internal class CodexNativeHistory(
    private val ref: SessionRef,
    private val history: CodexHistory,
    private val host: CodexRuntimeEnvironment,
) {
    private val log = Log.tag("CodexNativeHistory")
    private val loadedTurns = mutableSetOf<String>()

    fun audit(thread: JsonObject, nativeTurns: Set<String>): List<JsonObject>? {
        if (thread.text("id") != ref.nativeId) protocolFailure()
        val turns = thread["turns"]?.let { value ->
            (value as? JsonArray ?: protocolFailure()).map { it as? JsonObject ?: protocolFailure() }
        }
        var isCovered = turns != null
        val missingTurns = (loadedTurns + nativeTurns).toMutableSet()
        for (turn in turns.orEmpty()) {
            val id = turn.text("id") ?: protocolFailure()
            missingTurns.remove(id)
            val items = nativeItems(turn)
            val view = turn.text("itemsView")
            val isKnownTurn = id in loadedTurns || id in nativeTurns
            val isFullView = view == null || view == "full"
            val isMatching = items?.all { history.matches(it) } == true
            if (!isKnownTurn || !isFullView || !isMatching) {
                isCovered = false
            }
        }
        if (!isCovered || missingTurns.isNotEmpty()) history.seeded(isComplete = false)
        log.d { "Codex native history audited coverage=${history.coverage}" }
        return turns
    }

    /**
     * Seeds the history with the native [turns]; null when the native response did not carry them.
     * [isNew] marks a thread just created by `thread/start`, which has no earlier native history. A resumed
     * thread is stored only after its first turn, so resumed empty [turns] mean the history was not loaded.
     * [isCanonical] requires the native `paginated` history mode without pending turn/item cursors. Its persisted
     * ItemCompleted records retain live IDs. Legacy replay synthesizes IDs and can omit tools even with
     * `itemsView=full`; seeding it as Partial would still duplicate messages or truncate a richer saved transcript.
     * Such replay, and any non-full canonical snapshot, is omitted entirely: empty Partial lets consumers keep
     * their saved history.
     */
    suspend fun load(
        turns: List<JsonElement>?,
        isNew: Boolean,
        isCanonical: Boolean,
        logicalTurns: Map<String, TurnId> = emptyMap(),
    ) {
        val snapshots = turns.orEmpty().map { value ->
            val turn = value as? JsonObject ?: protocolFailure()
            if (turn.text("id") == null) protocolFailure()
            turn to nativeItems(turn)
        }
        val isLoaded = isCanonical && snapshots.isNotEmpty() &&
            snapshots.all { (turn, items) -> turn.text("itemsView") == "full" && items != null }
        history.seeded(isComplete = isNew || isLoaded)
        log.d { "Loading native thread history turns=${turns?.size ?: "absent"} coverage=${history.coverage}" }
        if (!isNew && !isLoaded) return
        for ((turn, items) in snapshots) {
            loadTurn(turn, items.orEmpty(), logicalTurns)
        }
    }

    private suspend fun loadTurn(turn: JsonObject, items: List<JsonObject>, logicalTurns: Map<String, TurnId>) {
        val native = turn.text("id") ?: protocolFailure()
        val id = logicalTurns[native] ?: TurnId(native)
        loadedTurns += native
        host.resourceHistory.parts(ref, native)?.let { history.rememberOriginals(id, it) }
        items.forEach { history.nativeItem(it, id) }
    }

    private fun nativeItems(turn: JsonObject): List<JsonObject>? = turn["items"]?.let { value ->
        (value as? JsonArray ?: protocolFailure()).map {
            val item = it as? JsonObject ?: protocolFailure()
            if (item.text("id") == null) protocolFailure()
            item
        }
    }
}
