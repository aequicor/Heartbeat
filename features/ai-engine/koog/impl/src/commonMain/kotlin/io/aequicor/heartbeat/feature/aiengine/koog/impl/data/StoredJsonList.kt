package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

private val StoredJson = Json { ignoreUnknownKeys = true }

/**
 * JSON array kept as raw text, so one element this version cannot read never erases its neighbours.
 * Unreadable elements are logged, skipped by [items] and written back unchanged after the readable ones by [update];
 * readable elements that [update] leaves unchanged keep their stored JSON, including fields this version ignores.
 * A value that is not a JSON array at all is reported as absent by [items] and never overwritten by [update].
 * Callers serialize [update] themselves.
 */
internal class StoredJsonList<T : Any>(
    private val store: KeyValueStore,
    name: String,
    private val serializer: KSerializer<T>,
) {
    private val log = Log.tag("KoogStore")
    private val key = stringKey(name)

    /** Readable elements in stored order. */
    suspend fun items(): List<T> = read()?.items?.keys?.toList().orEmpty()

    /** Replaces the readable elements; throws [IllegalStateException] instead of overwriting an unreadable value. */
    suspend fun update(transform: (List<T>) -> List<T>) {
        val current = checkNotNull(read()) { "Stored ${store.spec} value is unreadable; refusing to overwrite it" }
        val next = transform(current.items.keys.toList()).map {
            current.items[it] ?: StoredJson.encodeToJsonElement(serializer, it)
        }
        store.set(key, StoredJson.encodeToString(JsonArray.serializer(), JsonArray(next + current.unreadable)))
    }

    private suspend fun read(): Contents<T>? {
        val raw = store.get(key) ?: return Contents(emptyMap(), emptyList())
        val elements = try {
            StoredJson.parseToJsonElement(raw) as? JsonArray
        } catch (e: SerializationException) {
            log.w(e.withoutStoredValue()) { "Stored ${store.spec} value is not JSON" }
            return null
        } ?: run {
            log.w { "Stored ${store.spec} value is not a JSON array" }
            return null
        }
        // Keyed by value to write unchanged elements back as stored; equal duplicates collapse into one.
        val items = linkedMapOf<T, JsonElement>()
        val unreadable = mutableListOf<JsonElement>()
        elements.forEach { element ->
            try {
                items[StoredJson.decodeFromJsonElement(serializer, element)] = element
            } catch (e: SerializationException) {
                log.w(e.withoutStoredValue()) { "Skipping unreadable ${store.spec} element" }
                unreadable += element
            } catch (e: IllegalArgumentException) {
                log.w(e.withoutStoredValue()) { "Skipping invalid ${store.spec} element" }
                unreadable += element
            }
        }
        return Contents(items, unreadable)
    }

    private data class Contents<T>(val items: Map<T, JsonElement>, val unreadable: List<JsonElement>)
}

/** Decoder messages can quote the stored value, which may hold transcripts. */
private fun Throwable.withoutStoredValue(): Throwable =
    SerializationException("Stored element decoding failed (${this::class.simpleName ?: "unknown error"})")
