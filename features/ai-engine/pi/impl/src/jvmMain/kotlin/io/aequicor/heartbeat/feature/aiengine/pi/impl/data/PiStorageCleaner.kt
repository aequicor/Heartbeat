package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Comparator

@Inject
@ContributesIntoSet(AppScope::class)
internal class PiStorageCleaner(private val dispatchers: DispatcherProvider, private val storage: PiStorage) :
    ProfileStorageCleaner {
    private val log = Log.tag("PiStorageCleaner")

    override suspend fun wipeProfile(id: ProfileId) = withContext(dispatchers.io) {
        val root = storage.root()
        val target = storage.profileRoot(id.value)
        if (id.value.isBlank() || target.parent != root) {
            piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        log.i { "Removing Pi profile transcripts and runtime configuration" }
        if (Files.exists(target)) {
            Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
