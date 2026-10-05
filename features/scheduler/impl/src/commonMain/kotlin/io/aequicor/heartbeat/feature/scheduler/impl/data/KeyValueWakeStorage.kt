package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Pending wakes in the profile key-value store; wake notes and payloads are never logged. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueWakeStorage(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : WakeStorage {
    private val log = Log.tag("KeyValueWakeStorage")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): List<ScheduledWake> {
        val wakes = store.get(WAKES)?.let(::decode).orEmpty()
        log.d { "read pending wakes: ${wakes.size}" }
        return wakes
    }

    override suspend fun save(wakes: List<ScheduledWake>) {
        log.d { "write pending wakes: ${wakes.size}" }
        if (wakes.isEmpty()) store.remove(WAKES) else store.set(WAKES, json.encodeToString(WAKE_LIST, wakes))
    }

    /**
     * A decoding failure ([kotlinx.serialization.SerializationException] is an [IllegalArgumentException], as are the
     * checks of the model) is replaced by one without message or cause, which may quote wake notes.
     */
    private fun decode(raw: String): List<ScheduledWake> = try {
        json.decodeFromString(WAKE_LIST, raw)
    } catch (e: IllegalArgumentException) {
        // The effect runtime logs the failed load; only the cleaned failure leaves this place.
        throw e.withoutStoredText()
    }

    private companion object {
        val SPEC = KeyValueSpec("scheduler")
        val WAKES = stringKey("wakes")
        val WAKE_LIST = ListSerializer(ScheduledWake.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Decoder messages and causes can quote stored wake notes: only the failure kind is kept. */
private fun Exception.withoutStoredText(): IllegalStateException =
    IllegalStateException("Unreadable scheduler wakes (${this::class.simpleName.orEmpty()})")
