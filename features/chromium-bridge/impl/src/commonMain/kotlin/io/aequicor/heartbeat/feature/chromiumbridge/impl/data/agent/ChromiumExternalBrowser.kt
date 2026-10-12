package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.agent

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserException
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserFailure
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [ExternalBrowser] over the newest connected extension whose user allowed agent control. Calls go to that
 * connection only; when it drops, calls fail as unavailable and its tabs are gone with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChromiumExternalBrowser(sessions: StateFlow<List<BridgeSession>>, scope: CoroutineScope) :
    ExternalBrowser {
    private val log = Log.tag("ChromiumExternalBrowser")

    private val agentSession: StateFlow<BridgeSession?> = sessions.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(null)
        } else {
            combine(list.map { session -> session.client.map { session.takeIf { _ -> it.isAgentCapable() } } }) {
                it.lastOrNull { session -> session != null }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val state: StateFlow<ExternalBrowserState> = agentSession.map { session ->
        val client = session?.client?.value
        if (client == null) {
            ExternalBrowserState.Unavailable
        } else {
            ExternalBrowserState.Connected(client.browserBrand, client.browserVersion)
        }
    }.stateIn(scope, SharingStarted.Eagerly, ExternalBrowserState.Unavailable)

    override val events: Flow<ExternalTabEvent> = agentSession.flatMapLatest { session ->
        session?.events?.mapNotNull { it.toTabEvent() } ?: emptyFlow()
    }

    override suspend fun activeTab(): ExternalTab? =
        call("tabs.active", JsonObject(emptyMap()))["tab"]?.let { it as? JsonObject }?.toTab()

    override suspend fun openTab(): ExternalTab = tab(call("tabs.open", JsonObject(emptyMap())))

    override suspend fun control(tabId: String): ExternalTab = tab(call("tabs.control", tabParams(tabId)))

    override suspend fun navigate(tabId: String, url: String): ExternalTab = tab(
        call(
            "tabs.navigate",
            buildJsonObject {
                put("tabId", tabId)
                put("url", url)
            },
            NAVIGATION_TIMEOUT_MILLIS,
        ),
    )

    override suspend fun activate(tabId: String) {
        call("tabs.activate", tabParams(tabId))
    }

    override suspend fun release(tabId: String) {
        call("tabs.release", tabParams(tabId))
    }

    override suspend fun close(tabId: String) {
        call("tabs.close", tabParams(tabId))
    }

    @HighFrequency
    override suspend fun devTools(tabId: String, method: String, params: JsonObject): String {
        log.v { "DevTools call method=$method" }
        val result = call(
            "cdp.send",
            buildJsonObject {
                put("tabId", tabId)
                put("method", method)
                put("params", params)
            },
        )
        return (result["result"] ?: JsonObject(emptyMap())).toString()
    }

    override suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean) {
        call(
            "guard.resolve",
            buildJsonObject {
                put("tabId", tabId)
                put("requestId", requestId)
                put("allow", isAllowed)
            },
        )
    }

    private suspend fun call(method: String, params: JsonObject, timeout: Long = CALL_TIMEOUT_MILLIS): JsonObject {
        val session = agentSession.value
            ?: throw ExternalBrowserException(ExternalBrowserFailure.Unavailable, "No browser allows agent control")
        return try {
            session.call(method, params, timeout) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: BridgeException) {
            throw ExternalBrowserException(e.code.toFailure(), "External browser refused $method (${e.code})", e)
        }
    }

    private fun tab(result: JsonObject): ExternalTab = (result["tab"] as? JsonObject)?.toTab()
        ?: throw ExternalBrowserException(ExternalBrowserFailure.Failed, "External browser returned no tab")

    private fun tabParams(tabId: String) = buildJsonObject { put("tabId", tabId) }

    private companion object {
        const val CALL_TIMEOUT_MILLIS = 30_000L
        const val NAVIGATION_TIMEOUT_MILLIS = 35_000L
        const val AGENT_CONTROL = "agentControl"

        fun io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeClient?.isAgentCapable(): Boolean =
            this != null && AGENT_CONTROL in capabilities

        fun String.toFailure(): ExternalBrowserFailure = when (this) {
            BridgeErrorCode.UNAVAILABLE -> ExternalBrowserFailure.Unavailable
            BridgeErrorCode.FORBIDDEN -> ExternalBrowserFailure.Forbidden
            BridgeErrorCode.NO_SUCH_TAB -> ExternalBrowserFailure.NoSuchTab
            BridgeErrorCode.TIMEOUT -> ExternalBrowserFailure.Timeout
            else -> ExternalBrowserFailure.Failed
        }

        fun JsonObject.toTab(): ExternalTab? {
            val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return ExternalTab(id, string("url"), string("title"))
        }

        fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun BridgeIncomingEvent.toTabEvent(): ExternalTabEvent? {
            val tabId = (params["tabId"] as? JsonPrimitive)?.contentOrNull ?: return null
            return when (event) {
                "tab.changed" -> ExternalTabEvent.Changed(
                    tabId,
                    params.string("url"),
                    params.string("title"),
                    (params["loading"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )

                "tab.removed" -> ExternalTabEvent.Removed(tabId)

                "tab.revoked" -> ExternalTabEvent.Revoked(tabId)

                "guard.paused" -> ExternalTabEvent.DocumentRequested(
                    tabId,
                    params.string("requestId"),
                    params.string("url"),
                    (params["mainFrame"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )

                else -> null
            }
        }
    }
}
