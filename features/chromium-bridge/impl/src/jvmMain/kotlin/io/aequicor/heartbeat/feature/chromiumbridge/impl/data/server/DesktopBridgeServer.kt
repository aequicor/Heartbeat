package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ToggleBridgeFeatures
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.BridgeServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path

/** [BridgeServer] over the app's Unix domain socket. */
internal class DesktopBridgeServer(
    private val socket: () -> Path,
    private val connections: BridgeConnections,
    private val features: ToggleBridgeFeatures,
    private val io: CoroutineDispatcher,
) : BridgeServer {
    private val log = Log.tag("DesktopBridgeServer")

    override suspend fun serve(onStatus: suspend (BridgeServerStatus) -> Unit) = coroutineScope {
        onStatus(BridgeServerStatus.Starting)
        val announcer = launch { announceFeatures() }
        val result = UnixSocketBridgeServer(socket(), io).serve(
            onListening = { onStatus(BridgeServerStatus.Listening) },
        ) { link -> connections.serve(LinkTransport(link, io)) }
        announcer.cancel()
        onStatus(
            when (result) {
                ServeResult.Occupied -> BridgeServerStatus.Occupied
                is ServeResult.Failed -> BridgeServerStatus.Failed
            },
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun clients(): Flow<List<ConnectedBrowser>> = connections.sessions.flatMapLatest { sessions ->
        if (sessions.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(sessions.map { session -> session.client.map { client -> client?.let { session.describe(it) } } }) {
                it.filterNotNull()
            }
        }
    }

    override suspend fun revokeTabs(): Int {
        var answered = 0
        connections.sessions.value
            .filter { AGENT_CONTROL in it.client.value?.capabilities.orEmpty() }
            .forEach { session ->
                try {
                    session.call(REVOKE_ALL, JsonObject(emptyMap()))
                    answered++
                } catch (e: BridgeException) {
                    log.w(e) { "Extension did not release its agent tabs id=${session.id} code=${e.code}" }
                }
            }
        return answered
    }

    /** Pushes feature changes to every extension past the handshake. */
    private suspend fun announceFeatures() {
        features.observe().drop(1).collect { set ->
            val params = BridgeProtocol.json.encodeToJsonElement(BridgeFeatureSet.serializer(), set).jsonObject
            connections.sessions.value.filter { it.client.value != null }.forEach { session ->
                try {
                    session.emit(APP_FEATURES, params)
                } catch (e: BridgeException) {
                    log.w(e) { "Feature update not delivered id=${session.id}" }
                }
            }
            log.i { "Bridge features announced chat=${set.isChat} agentControl=${set.isAgentControl}" }
        }
    }

    private fun BridgeSession.describe(client: BridgeClient) = ConnectedBrowser(
        id = id,
        brand = client.browserBrand,
        version = client.browserVersion,
        extensionVersion = client.extensionVersion,
        isAgentControlAllowed = AGENT_CONTROL in client.capabilities,
    )

    private companion object {
        const val AGENT_CONTROL = "agentControl"
        const val REVOKE_ALL = "tabs.revokeAll"
        const val APP_FEATURES = "app.features"
    }
}
