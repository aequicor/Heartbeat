package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.RecordRetentions
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

/** Lazily opens the profile database; every read and write uses a single exact helper identity. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredHarnessHelperBindings(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val retentions: RecordRetentions,
) : HarnessHelperBindings {
    private val dao by lazy { stores.database(HarnessAncestryDatabaseSpec).helpers() }
    private val log = Log.tag("HarnessHelperBindings")

    override suspend fun bind(binding: HarnessHelperBinding) {
        log.v { "Persist immutable helper ownership" }
        dao.bind(
            HarnessHelperBindingRecord(
                binding.helper.value,
                binding.owner.value,
                binding.harness.value,
                binding.attachRequest.value,
                binding.session?.let { Json.encodeToString(it) },
                retention(),
            ),
        )
    }

    override suspend fun lookup(helper: HelperId): HarnessHelperBinding? = dao.lookup(helper.value)?.binding()

    override suspend fun bindSession(helper: HelperId, owner: ActionId, session: SessionRef): HarnessHelperBinding {
        log.v { "Bind exact helper session before attachment" }
        return dao.bindSession(helper.value, owner.value, Json.encodeToString(session), retention()).binding()
    }

    private fun retention() = retentions.stamp(Retention.expiring(Expiry.After(HELPER_BINDING_RETENTION)))

    private fun HarnessHelperBindingRecord.binding(): HarnessHelperBinding = try {
        HarnessHelperBinding(
            HelperId(helper),
            ActionId(owner),
            HarnessId(harness),
            RequestId(attachRequest),
            session?.let { Json.decodeFromString<SessionRef>(it) },
        )
    } catch (error: IllegalArgumentException) {
        val safe = HarnessStorageCorrupt("HelperBinding")
        log.w(safe) { "Helper binding decode failed (${error::class.simpleName.orEmpty()})" }
        throw safe
    }
}

/**
 * Sliding lifetime of a helper ownership proof, renewed by every owned admission of the helper. It outlives the
 * 30-day workflow run retention, so a recovery prompt to a still-known helper chat keeps its proof.
 */
internal val HELPER_BINDING_RETENTION = 90.days
