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
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** A long tool output kept out of the context; its text is user data and never logged. */
internal data class StoredOutput(val session: SessionRef, val tool: String, val text: String) {
    override fun toString(): String = "StoredOutput(tool=$tool, chars=${text.length})"
}

/** An offloaded output [id] keeping its first [chars] characters. */
internal data class Stored(val id: String, val chars: Int)

/** Profile storage of offloaded tool outputs. */
internal interface ContextOutputs {
    /** Stores [output], at most a bounded beginning of it, and tells what was kept. */
    suspend fun put(output: StoredOutput): Stored

    /** The stored output [id], or null when it expired or never existed. */
    suspend fun get(id: String): StoredOutput?
}

/**
 * Offloaded outputs in the feature's profile database, one row each, kept for [LIFETIME]. The newest
 * [MAX_OUTPUTS] stay while together they hold at most [MAX_TOTAL_CHARS]; older ones are dropped first, in the same
 * transaction as the insert. One output keeps at most [MAX_OUTPUT_CHARS].
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoomContextOutputs(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val retentions: RecordRetentions,
    private val clock: Clock,
) : ContextOutputs {
    private val log = Log.tag("ContextOutputs")
    private val dao by lazy { stores.database(ContextOptimizationDatabaseSpec).outputs() }

    override suspend fun put(output: StoredOutput): Stored {
        val id = Uuid.random().toHexString().take(ID_LENGTH)
        val text = output.text.take(MAX_OUTPUT_CHARS)
        val row = OutputEntity(
            id = id,
            session = Json.encodeToString(SessionRef.serializer(), output.session),
            tool = output.tool,
            text = text,
            chars = text.length,
            retention = retentions.stamp(Retention.expiring(Expiry.After(LIFETIME))),
        )
        val stored = dao.insertCapped(row, MAX_OUTPUTS, MAX_TOTAL_CHARS)
        log.d { "offloaded a tool output: chars=${text.length} stored=$stored" }
        return Stored(id, text.length)
    }

    override suspend fun get(id: String): StoredOutput? {
        log.v { "read an offloaded tool output" }
        val isValid = id.length == ID_LENGTH && id.all { it.isLetterOrDigit() }
        return if (isValid) dao.get(id, clock.now().toEpochMilliseconds())?.decoded() else null
    }

    private fun OutputEntity.decoded(): StoredOutput? = try {
        StoredOutput(Json.decodeFromString(SessionRef.serializer(), session), tool, text)
    } catch (e: IllegalArgumentException) {
        // Also a SerializationException: the stored session is not readable.
        log.w(e) { "an offloaded tool output has an unreadable session" }
        null
    }

    private companion object {
        val LIFETIME = 7.days
        const val ID_LENGTH = 12
        const val MAX_OUTPUTS = 64

        // Keep a complete hosted tool result, including compiled mini-app source, available through paged recall.
        const val MAX_OUTPUT_CHARS = 4 * 1024 * 1024
        const val MAX_TOTAL_CHARS = 32L * 1024 * 1024
    }
}
