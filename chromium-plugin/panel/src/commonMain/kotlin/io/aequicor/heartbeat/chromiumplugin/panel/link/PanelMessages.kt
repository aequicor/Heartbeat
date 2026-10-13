package io.aequicor.heartbeat.chromiumplugin.panel.link

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Messages between the panel and the service worker over the `heartbeat-panel` port
 * (`chromium-plugin/src/background/panel-hub.js`). The hub opens app watches for the panel, relays their events
 * and the app's replies, and adds the link status and the agent-control consent.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed interface PanelInbound {
    /** Link status of the extension (`connecting`, `ready`, `host_missing`…) and the app's bridge features. */
    @Serializable
    @SerialName("status")
    data class Status(val status: String, val features: PanelFeatures? = null) : PanelInbound

    /** The user's consent to agent control in this browser and the number of tabs agents drive. */
    @Serializable
    @SerialName("agent")
    data class Agent(val isAllowed: Boolean = false, val controlled: Int = 0) : PanelInbound

    /** A message for the watch the panel opened under [key]. */
    sealed interface ForWatch : PanelInbound {
        val key: String?
    }

    /** Local link transition, delivered in port order before any frames of the restored watch. */
    @Serializable
    @SerialName("connection")
    data class Connection(override val key: String, val status: String) : ForWatch

    /** An app event of a watch the panel opened under [key]. */
    @Serializable
    @SerialName("event")
    data class Event(
        override val key: String? = null,
        val event: String,
        val params: JsonObject,
        @kotlinx.serialization.Transient val connectionEpoch: Long = 0,
    ) : ForWatch

    /** The answer to a [PanelOutbound.Call] with the same [id]. */
    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val result: JsonElement? = null, val error: PanelError? = null) : PanelInbound

    /** The watch under [key] could not be opened. */
    @Serializable
    @SerialName("watchFailed")
    data class WatchFailed(override val key: String, val error: PanelError? = null) : ForWatch
}

/** Bridge features the app offers (`app.features`). */
@Serializable
internal data class PanelFeatures(
    @SerialName("chat") val isChat: Boolean = false,
    @SerialName("agentControl") val isAgentControl: Boolean = false,
    @SerialName("studio") val isStudio: Boolean = false,
)

/** A failure with one of `BridgeErrorCode`; the message is diagnostic only. */
@Serializable
internal data class PanelError(val code: String, val message: String = "")

/** Messages the panel sends to the service worker. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed interface PanelOutbound {
    /** Opens (or replaces) the watch [key] with the app method [method]. */
    @Serializable
    @SerialName("watch")
    data class Watch(val key: String, val method: String, val params: JsonObject = JsonObject(emptyMap())) :
        PanelOutbound

    /** Closes the watch [key]. */
    @Serializable
    @SerialName("unwatch")
    data class Unwatch(val key: String) : PanelOutbound

    /** Calls an app method; the hub answers with a [PanelInbound.Reply] of the same [id]. */
    @Serializable
    @SerialName("call")
    data class Call(val id: Long, val method: String, val params: JsonElement) : PanelOutbound

    /** Allows or forbids agent control in this browser. */
    @Serializable
    @SerialName("consent")
    data class Consent(val isAllowed: Boolean) : PanelOutbound

    /** Takes every tab back from agents. */
    @Serializable
    @SerialName("revokeTabs")
    data object RevokeTabs : PanelOutbound
}

/** JSON of the panel port; unknown fields of newer app or extension versions are ignored. */
internal val panelJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
