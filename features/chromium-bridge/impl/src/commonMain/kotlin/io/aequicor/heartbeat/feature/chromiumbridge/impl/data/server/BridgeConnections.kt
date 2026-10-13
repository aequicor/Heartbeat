package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** Live connections of the app; consumers pick the extension they talk to from [sessions]. */
@OptIn(ExperimentalAtomicApi::class)
internal class BridgeConnections(
    private val dispatcher: BridgeDispatcher,
    private val isOriginAllowed: (String) -> Boolean,
) {
    private val log = Log.tag("BridgeConnections")
    private val ids = AtomicLong(0)
    private val live = MutableStateFlow<List<BridgeSession>>(emptyList())

    /** Introduced connections, oldest first. */
    val sessions: StateFlow<List<BridgeSession>> = live.asStateFlow()

    /** Serves one transport until it closes; the session is listed once its host introduced an allowed origin. */
    suspend fun serve(transport: FrameTransport) {
        val connection = BridgeConnection(ids.incrementAndFetch(), transport, dispatcher, isOriginAllowed)
        try {
            connection.run { introduced ->
                live.update { it + introduced }
                log.i { "Bridge connection introduced id=${introduced.id}" }
            }
        } finally {
            live.update { sessions -> sessions.filterNot { it === connection } }
        }
    }
}
