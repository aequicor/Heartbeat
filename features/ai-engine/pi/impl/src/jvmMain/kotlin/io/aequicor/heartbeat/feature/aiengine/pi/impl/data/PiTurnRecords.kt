package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Latest logical request boundaries; native transcript status alone cannot settle an interrupted process. */
@Serializable
internal data class PiTurnRecord(
    val turn: Turn,
    val trust: TrustLevel,
    val processOwner: PiExecutionOwner? = null,
    val isProcessStopped: Boolean = false,
) {
    init {
        requireNotNull(turn.request)
        require(!isProcessStopped || (processOwner != null && turn.outcome != null))
    }
}

/** Permanent profile identity. Ownership is a credential fingerprint, never the credential itself. */
@Serializable
internal data class PiTurnSnapshot(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val ownership: String,
    val active: PiTurnRecord? = null,
    val last: PiTurnRecord? = null,
    /** Retained through terminal native events until all observed process exits are confirmed. */
    val stopping: PiTurnRecord? = null,
    /** Incomplete descendant discovery cannot be repaired by an empty snapshot after the parent exited. */
    val stopInspection: String? = null,
    /** Completed native assistant message fingerprints; null is a permanently ambiguous repeated message. */
    val answerTurns: Map<String, TurnId?> = emptyMap(),
) {
    init {
        require(ownership.isNotBlank())
        require(ref.engine == route.engine)
        require(active?.turn?.outcome == null)
        require(last == null || last.turn.outcome != null)
        require(active == null || active.turn.id != last?.turn?.id)
        require(stopInspection == null || (stopInspection.isNotBlank() && stopping != null))
        require(stopping == null || (stopping.processOwner != null && stopping.turn.outcome == null))
        require(
            stopping == null || listOfNotNull(active, last).any {
                it.turn.id == stopping.turn.id && it.turn.request == stopping.turn.request &&
                    it.processOwner?.launchId == stopping.processOwner?.launchId &&
                    it.processOwner?.root == stopping.processOwner?.root
            },
        )
    }

    override fun toString(): String = "PiTurnSnapshot(***)"
}

/** Atomic read–modify–write; transformers contain no IO and cannot wait for the native process. */
internal interface PiTurnRecords {
    suspend fun get(ref: SessionRef): PiTurnSnapshot?
    suspend fun update(ref: SessionRef, transform: (PiTurnSnapshot?) -> PiTurnSnapshot): PiTurnSnapshot
}

internal class MemoryPiTurnRecords : PiTurnRecords {
    private val values = mutableMapOf<SessionRef, PiTurnSnapshot>()
    override suspend fun get(ref: SessionRef): PiTurnSnapshot? = values[ref]
    override suspend fun update(ref: SessionRef, transform: (PiTurnSnapshot?) -> PiTurnSnapshot): PiTurnSnapshot =
        transform(values[ref]).also { values[ref] = it }
}

internal val PiTurnsSpec = KeyValueSpec("ai_pi_turns")

/** Permanent profile records; a required marker fails closed if the corresponding record is lost. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredPiTurnRecords(
    @ForScope(ProfileScope::class) stores: DataStores,
) : PiTurnRecords {
    private val store = stores.keyValue(PiTurnsSpec)
    private val mutex = Mutex()

    override suspend fun get(ref: SessionRef): PiTurnSnapshot? = mutex.withLock { read(ref) }

    override suspend fun update(ref: SessionRef, transform: (PiTurnSnapshot?) -> PiTurnSnapshot): PiTurnSnapshot =
        mutex.withLock {
            val next = transform(read(ref))
            check(next.ref == ref)
            val key = piTurnKey(ref)
            store.set(booleanKey("required_$key"), true)
            store.set(stringKey("turn_$key"), Json.encodeToString(next))
            next
        }

    private suspend fun read(ref: SessionRef): PiTurnSnapshot? {
        val key = piTurnKey(ref)
        val raw = store.get(stringKey("turn_$key"))
        if (raw == null) {
            check(store.get(booleanKey("required_$key")) != true) { "Required Pi turn record is missing" }
            return null
        }
        // Raw text decoding deliberately propagates corruption; jsonKey would collapse it to an absent record.
        return Json.decodeFromString<PiTurnSnapshot>(raw).also { check(it.ref == ref) }
    }
}

internal fun piTurnKey(ref: SessionRef): String = fingerprint(Json.encodeToString(ref))
