package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Inject
@ContributesIntoSet(AppScope::class)
internal class PiStorageCleaner(private val dispatchers: DispatcherProvider) : ProfileStorageCleaner {
    private val log = Log.tag("PiStorageCleaner")

    override suspend fun wipeProfile(id: ProfileId) = withContext(dispatchers.io) {
        require(id.value.isNotBlank())
        val root = Path.of(System.getProperty("user.home"), ".heartbeat", "pi").toAbsolutePath().normalize()
        val target = root.resolve(fingerprint(id.value)).normalize()
        check(target.parent == root)
        log.i { "Removing Pi profile transcripts and runtime configuration" }
        if (Files.exists(target)) {
            Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
