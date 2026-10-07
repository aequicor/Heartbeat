package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Retention state belongs to the native execution, even when every observing handle is closed. */
internal class PiContextRevision : SessionContextRevision {
    private val log = Log.tag("PiContextRevision")
    private val mutableState = MutableStateFlow<String?>(Uuid.random().toString())
    override val state = mutableState.asStateFlow()

    fun event(record: JsonObject) {
        when (record.string("type")) {
            "auto_compaction_start", "compaction_start" -> invalidate()
            "auto_compaction_end", "compaction_end" -> renewed()
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
