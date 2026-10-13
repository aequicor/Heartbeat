package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parameters of `hello`, the first request of every connection. */
@Serializable
internal data class HelloParams(
    val protocol: List<Int>,
    val extension: HelloExtension,
    val browser: HelloBrowser = HelloBrowser(),
    val capabilities: List<String> = emptyList(),
)

/** The extension's own version from its manifest. */
@Serializable
internal data class HelloExtension(val version: String)

/** The browser brand and version reported by the extension (Chrome, Edge, Brave…). */
@Serializable
internal data class HelloBrowser(val brand: String = "", val version: String = "")

/** Result of `hello`. */
@Serializable
internal data class HelloResult(
    val protocol: Int,
    val app: HelloApp,
    val features: BridgeFeatureSet,
    val limits: HelloLimits,
)

/** The Heartbeat build answering. */
@Serializable
internal data class HelloApp(val version: String)

/** Which bridge features the app currently offers; sent again as `app.features` when toggles change. */
@Serializable
internal data class BridgeFeatureSet(
    @SerialName("chat") val isChat: Boolean,
    @SerialName("agentControl") val isAgentControl: Boolean,
    /** The studio screen itself can be shown remotely (`studio.watch`). */
    @SerialName("studio") val isStudio: Boolean = false,
)

/** Limits the extension must respect. */
@Serializable
internal data class HelloLimits(val maxFrameBytes: Int, val maxWatches: Int)
