package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.TransferStamps
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Random opaque identifiers carrying no account, path or credential data; time from the injected clock. */
@OptIn(ExperimentalUuidApi::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class UuidTransferStamps(private val clock: Clock) : TransferStamps {
    override fun conversation(): ConversationId = ConversationId(Uuid.random().toHexString())

    override fun request(): RequestId = RequestId("handoff-" + Uuid.random().toHexString())

    override fun now(): Instant = clock.now()
}
