package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import kotlinx.serialization.json.Json

/** Lazy profile database, never loaded as a collection and never cached in memory by the feature. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredHarnessRequestAncestry(
    @ForScope(ProfileScope::class) stores: DataStores,
) : HarnessRequestAncestry {
    private val dao by lazy { stores.database(HarnessAncestryDatabaseSpec).ancestry() }
    private val log = Log.tag("HarnessRequestOrigins")

    @HighFrequency
    override suspend fun restrict(session: SessionRef, request: RequestId, origin: HarnessCallOrigin) {
        log.v { "persist request restrictions" }
        if (!origin.isHookRestricted && origin.sendChain.isEmpty()) return
        dao.restrict(HarnessAncestryRecord(session.ancestryKey(), request.value, HarnessAncestryCodec.encode(origin)))
    }

    @HighFrequency
    override suspend fun lookup(session: SessionRef, request: RequestId): HarnessCallOrigin? {
        log.v { "read exact request restrictions" }
        val record = dao.lookup(session.ancestryKey(), request.value) ?: return null
        return HarnessAncestryCodec.decode(record.origin)
    }
}

/** Stable, collision-free encoding of all three session identity fields; never use a display string or hash. */
internal fun SessionRef.ancestryKey(): String = Json.encodeToString(listOf(engine.value, source.value, nativeId))
