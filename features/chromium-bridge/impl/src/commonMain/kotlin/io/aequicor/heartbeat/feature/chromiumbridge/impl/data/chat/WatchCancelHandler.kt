package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WatchRef
import kotlinx.serialization.json.JsonObject

/** `watch.cancel` for every watch of a connection, whichever handler opened it. */
@ContributesIntoSet(ProfileScope::class, binding = binding<BridgeRequestHandler>())
@Inject
internal class WatchCancelHandler(private val watches: BridgeWatches) : BridgeRequestHandler {
    override val methods: Set<String> = setOf(WATCH_CANCEL)

    override suspend fun handle(call: BridgeCall): BridgeReply {
        val watch = BridgeProtocol.json.decodeFromJsonElement(WatchRef.serializer(), call.params).watch
        watches.cancel(call.session, watch)
        return BridgeReply.Ok(JsonObject(emptyMap()))
    }

    private companion object {
        const val WATCH_CANCEL = "watch.cancel"
    }
}
