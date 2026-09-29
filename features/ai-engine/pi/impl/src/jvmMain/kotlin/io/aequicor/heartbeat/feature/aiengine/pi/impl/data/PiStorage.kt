package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.Log
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Files of the bundled Pi runtime: transcripts, workspaces and per-process configuration.
 *
 * Everything lives under the application data directory ([StorageRoot]), so Pi data shares the application's
 * lifecycle: it is removed together with the rest of the app data. The pre-bundling location `~/.heartbeat/pi`
 * is deleted once on first access. Callers invoke these functions on the IO dispatcher.
 */
@SingleIn(AppScope::class)
internal class PiStorage(private val storageRoot: StorageRoot, private val legacyRoot: Path) {
    @Inject
    constructor(storageRoot: StorageRoot) : this(storageRoot, defaultLegacyPiRoot())

    private val log = Log.tag("PiStorage")
    private val isLegacyChecked = AtomicBoolean(false)

    /** Root of all Pi profiles: `<app data>/engines/pi`. */
    fun root(): Path {
        log.d { "Resolving Pi data root" }
        return Path.of(storageRoot.path(), "engines", "pi").toAbsolutePath().normalize()
    }

    /** Profile-private Pi directory; the profile id is hashed so no identifier reaches the file system. */
    fun profileRoot(profileId: String): Path {
        log.d { "Resolving Pi profile directory" }
        removeLegacy()
        return root().resolve(fingerprint(profileId)).normalize()
    }

    private fun removeLegacy() {
        if (!isLegacyChecked.compareAndSet(false, true)) return
        val legacy = legacyRoot
        if (!Files.exists(legacy)) return
        log.i { "Removing Pi data from the legacy home-directory location" }
        try {
            Files.walk(legacy).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            val parent = legacy.parent
            if (parent != null && Files.isDirectory(parent)) {
                Files.newDirectoryStream(parent).use { if (!it.iterator().hasNext()) Files.delete(parent) }
            }
        } catch (e: IOException) {
            log.w(e) { "Legacy Pi data was not fully removed" }
        } catch (e: UncheckedIOException) {
            log.w(e) { "Legacy Pi data was not fully removed" }
        }
    }
}

private fun defaultLegacyPiRoot(): Path =
    Path.of(System.getProperty("user.home"), ".heartbeat", "pi").toAbsolutePath().normalize()
