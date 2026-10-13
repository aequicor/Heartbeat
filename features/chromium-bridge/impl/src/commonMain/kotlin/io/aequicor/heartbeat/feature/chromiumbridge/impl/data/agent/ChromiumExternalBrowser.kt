package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.agent

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserException
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserFailure
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowserState
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTab
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalTabEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * New tabs use the newest extension allowing agent control. Opaque tab handles and events remain bound to
 * their original connection; losing that connection revokes its handles even if another browser stays connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChromiumExternalBrowser(sessions: StateFlow<List<BridgeSession>>, scope: CoroutineScope) :
    ExternalBrowser {
    private val log = Log.tag("ChromiumExternalBrowser")

    private val tabsLock = Mutex()
    private val knownTabs = mutableSetOf<String>()
    private val pendingTabs = mutableSetOf<PendingTab>()
    private val observed = MutableStateFlow<Set<Long>>(emptySet())
    private val connections = sessions
    private val routes = MutableStateFlow<Map<String, SessionRoute>>(emptyMap())

    override fun bindSession(owner: String, connectionId: String): AutoCloseable {
        val route = SessionRoute(connectionId)
        routes.update { it + (owner to route) }
        log.i { "External browser turn bound" }
        return AutoCloseable {
            routes.update { if (it[owner] === route) it - owner else it }
            log.i { "External browser turn binding released" }
        }
    }

    override fun isExternalSession(owner: String): Boolean = owner in routes.value

    override fun isTabInSession(owner: String, tabId: String): Boolean =
        routes.value[owner]?.connection?.let { it == tabId.substringBefore(':') } ?: true

    // Referential equality publishes a fresh lease even when the same owner binds the same browser again.
    @Suppress("UseDataClass")
    private class SessionRoute(val connection: String)

    private val agentSessions: StateFlow<List<BridgeSession>> = sessions.flatMapLatest { list ->
        if (list.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(list.map { session -> session.client.map { session.takeIf { _ -> it.isAgentCapable() } } }) {
                it.filterNotNull()
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val state: StateFlow<ExternalBrowserState> = agentSessions.map { list ->
        val client = list.lastOrNull()?.client?.value
        if (client == null) {
            ExternalBrowserState.Unavailable
        } else {
            ExternalBrowserState.Connected(client.browserBrand, client.browserVersion)
        }
    }.stateIn(scope, SharingStarted.Eagerly, ExternalBrowserState.Unavailable)

    override val events: Flow<ExternalTabEvent> = channelFlow {
        val collectors = mutableMapOf<Long, Job>()
        agentSessions.collect { list ->
            val live = list.map { it.id }.toSet()
            collectors.keys.filter { it !in live }.forEach { id -> collectors.remove(id)?.cancel() }
            val revoked = tabsLock.withLock {
                pendingTabs.filter { it.connectionId !in live }.forEach { it.invalidateConnection() }
                knownTabs.filter { it.substringBefore(':').toLongOrNull() !in live }
                    .also { knownTabs.removeAll(it.toSet()) }
            }
            revoked.forEach { send(ExternalTabEvent.Revoked(it)) }
            list.filter { it.id !in collectors }.forEach { session ->
                collectors[session.id] = launch(start = CoroutineStart.UNDISPATCHED) {
                    session.events.collect { incoming ->
                        incoming.toTabEvent(session.id)?.let { event ->
                            tabsLock.withLock {
                                if (event is ExternalTabEvent.Removed || event is ExternalTabEvent.Revoked) {
                                    knownTabs.remove(event.tabId)
                                    pendingTabs.forEach { it.invalidate(event.tabId) }
                                } else {
                                    knownTabs.add(event.tabId)
                                }
                            }
                            send(event)
                        }
                    }
                }
            }
            observed.value = live
            log.v { "External browser event subscriptions updated count=${live.size}" }
        }
    }.shareIn(scope, SharingStarted.Eagerly)

    override suspend fun activeTab(owner: String?): ExternalTab? {
        val session = selected(owner)
        return (call(session, "tabs.active", JsonObject(emptyMap()))["tab"] as? JsonObject)?.toTab(session.id)
    }

    override suspend fun readActiveTab(owner: String): JsonObject {
        val session = selected(owner)
        val result = call(session, "tabs.readActive", JsonObject(emptyMap()))
        val id = result["tabId"]?.jsonPrimitive?.contentOrNull
        return if (id == null) result else JsonObject(result + ("tabId" to JsonPrimitive("${session.id}:$id")))
    }

    override suspend fun openTab(owner: String): ExternalTab {
        val session = selected(owner)
        return tabCall(session, "tabs.open", buildJsonObject { put("owner", owner) })
    }

    override suspend fun ownedTabs(owner: String): List<ExternalTab> {
        val selected = selected(owner) // An unavailable browser must not look like an empty group.
        return connections.value
            .filter { it.client.value.isAgentCapable() && (!isExternalSession(owner) || it === selected) }
            .flatMap { session ->
                val result = call(session, "tabs.owned", buildJsonObject { put("owner", owner) })
                (result["tabs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toTab(session.id) }
            }
    }

    override suspend fun recoverTab(owner: String, tabId: String, expectedUrl: String): ExternalTab = tabCall(
        route(tabId),
        "tabs.recover",
        tabParams(tabId) {
            put("owner", owner)
            put("expectedUrl", expectedUrl)
        },
    )

    override suspend fun control(tabId: String, expectedUrl: String): ExternalTab {
        val session = route(tabId)
        return tabCall(session, "tabs.control", tabParams(tabId) { put("expectedUrl", expectedUrl) })
    }

    override suspend fun navigate(tabId: String, url: String): ExternalTab {
        val session = route(tabId)
        return tabCall(session, "tabs.navigate", tabParams(tabId) { put("url", url) }, NAVIGATION_TIMEOUT_MILLIS)
    }

    override suspend fun activate(tabId: String) {
        call(route(tabId), "tabs.activate", tabParams(tabId))
    }

    override suspend fun release(tabId: String) {
        call(route(tabId), "tabs.release", tabParams(tabId))
        tabsLock.withLock {
            knownTabs.remove(tabId)
            pendingTabs.forEach { it.invalidate(tabId) }
        }
    }

    override suspend fun close(tabId: String) {
        call(route(tabId), "tabs.close", tabParams(tabId))
        tabsLock.withLock {
            knownTabs.remove(tabId)
            pendingTabs.forEach { it.invalidate(tabId) }
        }
    }

    @HighFrequency
    override suspend fun devTools(tabId: String, method: String, params: JsonObject): String {
        log.v { "DevTools call method=$method" }
        val result = call(
            route(tabId),
            "cdp.send",
            buildJsonObject {
                put("tabId", tabId.substringAfter(':'))
                put("method", method)
                put("params", params)
            },
        )
        return (result["result"] ?: JsonObject(emptyMap())).toString()
    }

    override suspend fun resolveDocument(tabId: String, requestId: String, isAllowed: Boolean) {
        call(
            route(tabId),
            "guard.resolve",
            buildJsonObject {
                put("tabId", tabId.substringAfter(':'))
                put("requestId", requestId)
                put("allow", isAllowed)
            },
        )
    }

    private fun selected(owner: String?): BridgeSession {
        val selected = routes.value[owner]?.connection ?: return newest()
        return connections.value.firstOrNull { it.id.toString() == selected && it.client.value.isAgentCapable() }
            ?: throw ExternalBrowserException(ExternalBrowserFailure.Unavailable, "The chat's browser is unavailable")
    }

    private fun newest(): BridgeSession = connections.value.lastOrNull { it.client.value.isAgentCapable() }
        ?: throw ExternalBrowserException(ExternalBrowserFailure.Unavailable, "No browser allows agent control")

    private fun route(tabId: String): BridgeSession = connections.value.firstOrNull {
        it.id.toString() == tabId.substringBefore(':') && it.client.value.isAgentCapable()
    } ?: throw ExternalBrowserException(ExternalBrowserFailure.Unavailable, "The tab's browser connection is gone")

    private suspend fun call(
        session: BridgeSession,
        method: String,
        params: JsonObject,
        timeout: Long = CALL_TIMEOUT_MILLIS,
    ): JsonObject = try {
        session.call(method, params, timeout) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: BridgeException) {
        throw ExternalBrowserException(e.code.toFailure(), "External browser refused $method (${e.code})", e)
    }

    private suspend fun tabCall(
        session: BridgeSession,
        method: String,
        params: JsonObject,
        timeout: Long = CALL_TIMEOUT_MILLIS,
    ): ExternalTab {
        awaitObservation(session)
        val pending = PendingTab(session.id)
        tabsLock.withLock { pendingTabs.add(pending) }
        try {
            val result = call(session, method, params, timeout)
            val tab = (result["tab"] as? JsonObject)?.toTab(session.id)
                ?: throw ExternalBrowserException(ExternalBrowserFailure.Failed, "External browser returned no tab")
            tabsLock.withLock {
                route(tab.id)
                if (!pending.isValid || tab.id in pending.invalidated) {
                    throw ExternalBrowserException(
                        ExternalBrowserFailure.NoSuchTab,
                        "Tab control ended during the request",
                    )
                }
                knownTabs.add(tab.id)
            }
            return tab
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                tabsLock.withLock { pendingTabs.remove(pending) }
            }
        }
    }

    /** Install event listeners before an RPC can create a tab or emit its revocation. */
    private suspend fun awaitObservation(session: BridgeSession) {
        combine(observed, session.client) { ids, client -> session.id in ids || !client.isAgentCapable() }
            .first { it }
        if (!session.client.value.isAgentCapable()) {
            throw ExternalBrowserException(ExternalBrowserFailure.Unavailable, "The browser connection is gone")
        }
    }

    /** Revocations retained only until this bounded RPC finishes. */
    private class PendingTab(val connectionId: Long) {
        var isValid = true
        val invalidated = mutableSetOf<String>()

        fun invalidate(tabId: String) {
            invalidated.add(tabId)
        }

        fun invalidateConnection() {
            isValid = false
        }
    }

    private fun tabParams(tabId: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("tabId", tabId.substringAfter(':'))
            extra()
        }

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

        fun JsonObject.toTab(connectionId: Long): ExternalTab? {
            val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return ExternalTab("$connectionId:$id", string("url"), string("title"))
        }

        fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

        fun BridgeIncomingEvent.toTabEvent(connectionId: Long): ExternalTabEvent? {
            val nativeId = (params["tabId"] as? JsonPrimitive)?.contentOrNull ?: return null
            val tabId = "$connectionId:$nativeId"
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
