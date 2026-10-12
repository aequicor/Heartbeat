package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A long tool output kept out of the context; its text is user data and never logged. */
@Serializable
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
 * Offloaded outputs in the profile key-value store, one record each, kept for [LIFETIME]. The newest
 * [MAX_OUTPUTS] stay while together they hold at most [MAX_TOTAL_CHARS]; older ones are dropped first. One output
 * keeps at most [MAX_OUTPUT_CHARS].
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueContextOutputs(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val clock: Clock,
) : ContextOutputs {
    private val log = Log.tag("ContextOutputs")
    private val store by lazy { stores.keyValue(SPEC) }
    private val lock = Mutex()

    override suspend fun put(output: StoredOutput): Stored = lock.withLock {
        val id = Uuid.random().toHexString().take(ID_LENGTH)
        val kept = output.copy(text = output.text.take(MAX_OUTPUT_CHARS))
        val now = clock.now()
        store.set(entry(id), kept, Retention.expiring(Expiry.After(LIFETIME)))
        val index = store.get(INDEX).orEmpty().filter { it.at > now - LIFETIME } + IndexEntry(id, kept.text.length, now)
        val retained = index.retained()
        (index - retained.toSet()).forEach { store.remove(entry(it.id)) }
        store.set(INDEX, retained)
        log.d { "offloaded a tool output: chars=${kept.text.length} stored=${retained.size}" }
        Stored(id, kept.text.length)
    }

    override suspend fun get(id: String): StoredOutput? {
        log.v { "read an offloaded tool output" }
        return if (id.length == ID_LENGTH && id.all { it.isLetterOrDigit() }) store.get(entry(id)) else null
    }

    private fun List<IndexEntry>.retained(): List<IndexEntry> {
        var total = 0
        return asReversed().takeWhile { entry ->
            total += entry.chars
            total <= MAX_TOTAL_CHARS
        }.take(MAX_OUTPUTS).asReversed()
    }

    private fun entry(id: String) = jsonKey("output.$id", StoredOutput.serializer())

    @Serializable
    private data class IndexEntry(val id: String, val chars: Int, val at: Instant)

    private companion object {
        val SPEC = KeyValueSpec("context_offload")
        val INDEX = jsonKey("index", ListSerializer(IndexEntry.serializer()))
        val LIFETIME = 7.days
        const val ID_LENGTH = 12
        const val MAX_OUTPUTS = 64
        const val MAX_OUTPUT_CHARS = 500_000
        const val MAX_TOTAL_CHARS = 4_000_000
    }
}
