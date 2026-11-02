package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ArchivesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException

private val archiveLog = Log.tag("OrganicNativeArchive")

/** Best-effort catalogue cleanup after exact native stop; callers exclude human conversation receipts. */
internal suspend fun EngineFacade.archiveOrganic(ref: SessionRef) {
    try {
        when (val access = sessions.get(ref).features.resolve(ArchivesSessions)) {
            is FeatureAccess.Available -> access.feature.setArchived(true)
            is FeatureAccess.Unavailable -> throw EngineException(access.reason)
            FeatureAccess.Unsupported -> archiveLog.d { "organic executor archiving is unsupported" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        archiveLog.w(e) { "stopped organic executor was not archived" }
    }
}
