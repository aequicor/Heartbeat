package io.aequicor.heartbeat.core.di.impl

import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Values are stored as JSON strings. Restored values nobody consumed yet stay in [snapshot],
 * so a consumer created later still gets them — even after a second process death.
 */
internal class ScopeSavedStateImpl(private val scopeName: String, restored: SavedBundle?, private val json: Json) :
    ScopeSavedState {

    private val log = Log.tag(ScopeHandleImpl.LOG_TAG)
    private val restoredEntries: MutableMap<String, String> = restored?.entries.orEmpty().toMutableMap()
    private val suppliers = mutableMapOf<String, () -> String?>()

    override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? {
        val raw = restoredEntries.remove(key) ?: return null
        return try {
            json.decodeFromString(serializer, raw).also { log.d { "scope $scopeName: restored '$key'" } }
        } catch (e: IllegalArgumentException) {
            // SerializationException or a failed `require` of the restored class: the format changed
            // between app versions — start from scratch instead of crashing
            log.w(e) { "scope $scopeName: dropped unreadable saved state '$key'" }
            null
        }
    }

    override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) {
        check(key !in suppliers) { "scope $scopeName: saved state '$key' is already registered" }
        suppliers[key] = { supplier()?.let { json.encodeToString(serializer, it) } }
    }

    override fun unregister(key: String) {
        suppliers -= key
    }

    override fun snapshot(): SavedBundle {
        val saved = suppliers.mapNotNull { (key, supplier) -> save(key, supplier)?.let { key to it } }.toMap()
        return SavedBundle(restoredEntries + saved)
    }

    /** A failing supplier loses only its own key, not the whole `StateKeeper.save()`. */
    private fun save(key: String, supplier: () -> String?): String? = try {
        supplier()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "scope $scopeName: failed to save '$key'" }
        null
    }
}
