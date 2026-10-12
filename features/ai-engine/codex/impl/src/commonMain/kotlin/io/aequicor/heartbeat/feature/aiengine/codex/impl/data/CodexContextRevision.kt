package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Retention state belongs to the native execution, even when every observing handle is closed. */
internal class CodexContextRevision : SessionContextRevision {
    private val log = Log.tag("CodexContextRevision")
    private val mutableState = MutableStateFlow<String?>(Uuid.random().toString())
    override val state = mutableState.asStateFlow()

    fun event(method: String?, params: JsonObject) {
        if (method == "thread/compacted") renewed()
        val item = params["item"] as? JsonObject
        if (item?.text("type") == "contextCompaction") {
            when (method) {
                "item/started" -> invalidate()
                "item/completed" -> renewed()
            }
        }
    }

    fun invalidate() {
        log.v { "Native context retention is unknown" }
        mutableState.value = null
    }

    fun renewed() {
        log.v { "Native context retention identity renewed" }
        mutableState.value = Uuid.random().toString()
    }
}
