package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.api.OrganismId

/** Receipt port for driver tests; physical storage and canonical authority are covered with the real journal. */
internal class MemoryNativeJournal : OrganicNativeJournal {
    var hasTracking = true
    override suspend fun isTracked(id: OrganismId, incarnation: String): Boolean = hasTracking

    private val records = mutableMapOf<Pair<OrganismId, String>, List<OrganicNativeAttempt>>()

    fun seed(id: OrganismId, incarnation: String, attempt: OrganicNativeAttempt) {
        records[id to incarnation] = records[id to incarnation].orEmpty() + attempt
    }

    override suspend fun attempts(id: OrganismId, incarnation: String) = records[id to incarnation].orEmpty()

    override suspend fun reserve(id: OrganismId, incarnation: String, claim: OrganicNativeClaim): OrganicNativeAttempt {
        check(hasTracking)
        val attempts = attempts(id, incarnation)
        attempts.singleOrNull { it.claim.token == claim.token }?.let {
            check(it.claim == claim)
            return it
        }
        check(attempts.none { it.claim.address == claim.address && !it.isQuiescent })
        return OrganicNativeAttempt(claim).also { records[id to incarnation] = attempts + it }
    }

    override suspend fun advance(
        id: OrganismId,
        incarnation: String,
        expected: OrganicNativeAttempt,
        next: OrganicNativeAttempt,
    ): OrganicNativeAttempt {
        val attempts = attempts(id, incarnation)
        val current = attempts.single { it.claim.token == expected.claim.token }
        if (current == next) {
            check(!next.stage.isAdmission)
            return current
        }
        check(current == expected && next.claim == expected.claim && next.revision == expected.revision + 1)
        expected.requireProgress(next)
        records[id to incarnation] = attempts.map { if (it == current) next else it }
        return next
    }

    override suspend fun released(id: OrganismId, incarnation: String, version: Long, references: Set<SessionRef>) {
        check(attempts(id, incarnation).all { it.isQuiescent })
    }
}
