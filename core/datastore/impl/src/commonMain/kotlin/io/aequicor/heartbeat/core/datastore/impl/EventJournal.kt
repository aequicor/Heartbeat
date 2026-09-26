package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * When each event of one owner was last fired (`events.json`: name → epoch millis). Storages that were closed
 * when an event fired read it on open and delete the records written before that moment.
 * Blocking IO: call on an IO thread. Written atomically (temporary file + move), so a reader never sees a torn file.
 */
internal class EventJournal(private val file: Path, private val fileSystem: FileSystem) {

    private val log = Log.tag(DS_LOG_TAG)
    private val writeLock = Mutex()

    /** Fired events; empty when the journal is missing or unreadable (logged). */
    fun read(): Map<String, Long> = try {
        readOrThrow()
    } catch (e: IOException) {
        log.e(e) { "event journal $file is unreadable: fired events are not applied" }
        emptyMap()
    } catch (e: SerializationException) {
        log.e(e) { "event journal $file is corrupted: fired events are not applied" }
        emptyMap()
    }

    /**
     * Adds [event]. A corrupted journal is not overwritten silently: it is kept as `events.json.corrupt` (logged),
     * and the new journal starts from this event.
     */
    suspend fun record(event: DataEvent, firedAt: Long): Unit = writeLock.withLock {
        val previous = try {
            readOrThrow()
        } catch (e: SerializationException) {
            val backup = file.parent?.let { it / "${file.name}.corrupt" } ?: error("journal $file has no parent")
            log.e(e) { "event journal $file is corrupted: kept as $backup, earlier events are lost" }
            fileSystem.atomicMove(file, backup)
            emptyMap()
        }
        val updated = previous + (event.name to firedAt)
        val temporary = file.parent?.let { it / "${file.name}.tmp" } ?: error("journal $file has no parent")
        fileSystem.createDirectories(temporary.parent ?: error("unreachable"))
        fileSystem.write(temporary) { writeUtf8(json.encodeToString(serializer, updated)) }
        fileSystem.atomicMove(temporary, file)
    }

    private fun readOrThrow(): Map<String, Long> {
        if (!fileSystem.exists(file)) return emptyMap()
        return json.decodeFromString(serializer, fileSystem.read(file) { readUtf8() })
    }

    private companion object {
        val json = Json
        val serializer = MapSerializer(String.serializer(), Long.serializer())
    }
}
