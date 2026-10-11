package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessLibrarySpec
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Lazy profile database, never loaded as a collection and never cached in memory by the feature. A profile
 * key-value marker is written before the first restriction; while it is absent no row can exist, so scheduler
 * observers that consult ancestry for every wake, helper and graph task answer without opening the database.
 * Once the marker exists, every lookup reads the database and its failures propagate (fail closed). Rows written
 * by builds before the marker are adopted once per process: a profile that ever stored harnesses or workflow runs
 * gets the marker written, since its database may already hold restrictions.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredHarnessRequestAncestry(
    @ForScope(ProfileScope::class) stores: DataStores,
) : HarnessRequestAncestry {
    private val dao by lazy { stores.database(HarnessAncestryDatabaseSpec).ancestry() }
    private val marker by lazy { stores.keyValue(HarnessAncestryMarkerSpec) }
    private val library by lazy { stores.keyValue(HarnessLibrarySpec) }
    private val runs by lazy { stores.keyValue(KeyValueHarnessRunStorage.SPEC) }

    /** Only a positive marker is cached: a negative read could race a concurrent first restriction. */
    @Volatile
    private var isMarked = false

    /** Pre-marker rows can only predate this process, so a negative legacy probe is final for its lifetime. */
    @Volatile
    private var isLegacyProbed = false
    private val log = Log.tag("HarnessRequestOrigins")

    @HighFrequency
    override suspend fun restrict(session: SessionRef, request: RequestId, origin: HarnessCallOrigin) {
        log.v { "persist request restrictions" }
        if (!origin.isHookRestricted && origin.sendChain.isEmpty()) return
        if (!isMarked) {
            marker.set(HarnessAncestryMarkerKey, ANCESTRY_MARKER_VERSION)
            isMarked = true
        }
        dao.restrict(HarnessAncestryRecord(session.ancestryKey(), request.value, HarnessAncestryCodec.encode(origin)))
    }

    @HighFrequency
    override suspend fun lookup(session: SessionRef, request: RequestId): HarnessCallOrigin? {
        log.v { "read exact request restrictions" }
        val record = if (hasRecords()) dao.lookup(session.ancestryKey(), request.value) else null
        return record?.let { HarnessAncestryCodec.decode(it.origin) }
    }

    @HighFrequency
    private suspend fun hasRecords(): Boolean {
        if (!isMarked && marker.get(HarnessAncestryMarkerKey) != null) isMarked = true
        if (!isMarked && !isLegacyProbed) adoptLegacyRows()
        return isMarked
    }

    private suspend fun adoptLegacyRows() {
        val hasHarnessData = library.get(LegacyLibraryIndexKey) != null ||
            runs.get(KeyValueHarnessRunStorage.INDEX) != null
        if (hasHarnessData) {
            log.d { "Adopt request ancestry of a profile with earlier harness data" }
            marker.set(HarnessAncestryMarkerKey, ANCESTRY_MARKER_VERSION)
            isMarked = true
        }
        isLegacyProbed = true
    }
}

/** Stable, collision-free encoding of all three session identity fields; never use a display string or hash. */
internal fun SessionRef.ancestryKey(): String = Json.encodeToString(listOf(engine.value, source.value, nativeId))

/** Profile marker store of the ancestry database; wiped together with it by profile wipe. */
internal val HarnessAncestryMarkerSpec = KeyValueSpec("harness_ancestry_marker")

/** Present once any restriction may have been written to the ancestry database. */
internal val HarnessAncestryMarkerKey = stringKey("ancestry.written")

private const val ANCESTRY_MARKER_VERSION = "1"

/** The harness library's index key; present once any harness was ever saved in this profile. */
private val LegacyLibraryIndexKey = stringKey("index")
