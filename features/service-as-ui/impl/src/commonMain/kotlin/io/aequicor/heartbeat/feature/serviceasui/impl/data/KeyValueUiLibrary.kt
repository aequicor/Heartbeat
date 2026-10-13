package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiLibraryStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val LibrarySpec = KeyValueSpec("service_ui_library")
private val LibraryKey = stringKey("library")
private val LibraryJson = Json { ignoreUnknownKeys = true }

/**
 * The surface library of the profile in one key, so every write replaces the whole snapshot atomically. The value is
 * a string decoded here: an unreadable library fails the load instead of reading as empty and being overwritten.
 * Saves are ordered by library revision, so a late save never replaces a newer snapshot.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueUiLibrary(
    @ForScope(ProfileScope::class) stores: DataStores,
) : UiLibraryStorage {
    private val log = Log.tag("ServiceUiLibrary")
    private val store = stores.keyValue(LibrarySpec)
    private val lock = Mutex()
    private var saved = -1L

    override suspend fun load(): UiLibrary = lock.withLock {
        val library = store.get(LibraryKey)?.let(::decode) ?: UiLibrary()
        saved = library.revision
        log.d { "Loaded surface library: surfaces=${library.surfaces.size}, revision=${library.revision}" }
        library
    }

    override suspend fun save(library: UiLibrary) = lock.withLock {
        if (library.revision > saved) {
            store.set(LibraryKey, LibraryJson.encodeToString(UiLibrary.serializer(), library))
            saved = library.revision
            log.v { "Saved surface library revision ${library.revision}" }
        } else {
            log.v { "Skip stale surface library revision ${library.revision}, saved $saved" }
        }
    }

    private fun decode(raw: String): UiLibrary = try {
        LibraryJson.decodeFromString(UiLibrary.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        throw e.withoutSpecText()
    }
}

/** Decoder messages quote stored specs. Keep only the kind of failure. */
internal fun Exception.withoutSpecText(): IllegalStateException =
    IllegalStateException("Unreadable surface library (${this::class.simpleName.orEmpty()})")
