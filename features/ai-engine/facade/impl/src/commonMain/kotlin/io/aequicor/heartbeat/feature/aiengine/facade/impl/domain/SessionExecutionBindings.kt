package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/** Permanent authority metadata, separate from best-effort native history discovery and title indexing. */
interface SessionExecutionBindings {
    /** Null means genuinely unbound; corruption and read failures must throw. */
    suspend fun find(ref: SessionRef): SessionExecutionBinding?

    /** Records before publication; a native ref can never change execution authority. */
    suspend fun bind(ref: SessionRef, binding: SessionExecutionBinding)

    /** Durable reservation before native create; an unknown native ref cannot escape authority after a crash. */
    suspend fun reserve(opening: SessionExecutionOpening)

    /** Atomically binds the returned native ref and removes exactly its original reservation. */
    suspend fun complete(opening: SessionExecutionOpening, ref: SessionRef)

    /** Unresolved native creates, retained until the returned ref can be bound. */
    suspend fun pending(): List<SessionExecutionOpening>

    /** All known native segments of an incarnation, including archived segments, for privileged cleanup. */
    suspend fun references(instance: SessionExecutionInstance): Set<SessionRef>
}

/** Native ref is unknown until creation acknowledges; the reservation survives cancellation and storage failures. */
@Serializable
data class SessionExecutionOpening(val token: String, val engine: EngineId, val binding: SessionExecutionBinding)
