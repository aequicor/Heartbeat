package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

internal fun testExecutionAccess() = SessionExecutionAccess(
    ExecutionTestBindings(),
    SessionExecutionAdmissions(lazyOf(emptySet())),
)

internal class ExecutionTestBindings : SessionExecutionBindings {
    var completeFailure: Exception? = null
    var onComplete: () -> Unit = {}
    private val openings = mutableListOf<SessionExecutionOpening>()
    private val records = mutableMapOf<SessionRef, SessionExecutionBinding>()
    override suspend fun find(ref: SessionRef) = records[ref]
    override suspend fun bind(ref: SessionRef, binding: SessionExecutionBinding) {
        check(records[ref] == null || records[ref] == binding)
        records[ref] = binding
    }
    override suspend fun reserve(opening: SessionExecutionOpening) {
        openings += opening
    }
    override suspend fun complete(opening: SessionExecutionOpening, ref: SessionRef) {
        onComplete()
        completeFailure?.let { throw it }
        check(opening in openings)
        bind(ref, opening.binding)
        openings.remove(opening)
    }
    override suspend fun pending() = openings.toList()
    override suspend fun references(instance: SessionExecutionInstance) =
        records.filterValues { it.instance == instance }.keys
}
