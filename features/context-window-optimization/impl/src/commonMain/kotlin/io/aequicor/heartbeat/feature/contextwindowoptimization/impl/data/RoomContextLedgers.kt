package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedger
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain.ContextLedgerStore
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Progress ledgers in the feature's profile database, one row per session. A row lives [LIFETIME] after its last
 * update, and only the [MAX_LEDGERS] most recently updated rows stay. A row that cannot be decoded is skipped on
 * load and stays as it is until its session writes a new ledger or it expires.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoomContextLedgers(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val retentions: RecordRetentions,
    private val clock: Clock,
) : ContextLedgerStore {
    private val log = Log.tag("ContextLedgers")
    private val dao by lazy { stores.database(ContextOptimizationDatabaseSpec).ledgers() }

    override suspend fun load(): Map<SessionRef, ContextLedger> {
        val rows = dao.all(clock.now().toEpochMilliseconds())
        val ledgers = rows.mapNotNull { it.decoded() }.toMap()
        log.v { "read ledgers: ${ledgers.size}/${rows.size}" }
        return ledgers
    }

    override suspend fun save(session: SessionRef, ledger: ContextLedger) {
        log.v { "write a ledger" }
        val row = LedgerEntity(
            session = session.key(),
            ledger = Json.encodeToString(ContextLedger.serializer(), ledger),
            updatedAt = ledger.updatedAt.toEpochMilliseconds(),
            retention = retentions.stamp(Retention.expiring(Expiry.After(LIFETIME))),
        )
        dao.save(row, MAX_LEDGERS)
    }

    override suspend fun delete(session: SessionRef) {
        log.v { "delete a ledger" }
        dao.delete(session.key())
    }

    private fun LedgerEntity.decoded(): Pair<SessionRef, ContextLedger>? = try {
        Json.decodeFromString(SessionRef.serializer(), session) to
            Json.decodeFromString(ContextLedger.serializer(), ledger)
    } catch (e: IllegalArgumentException) {
        // Also a SerializationException; the row is kept, so other rows and a later fix are not lost.
        log.w(e) { "a stored ledger is unreadable and skipped" }
        null
    }

    private fun SessionRef.key(): String = Json.encodeToString(SessionRef.serializer(), this)

    private companion object {
        val LIFETIME = 30.days
        const val MAX_LEDGERS = 200
    }
}
