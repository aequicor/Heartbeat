package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Chrome's native-messaging host manifest. */
@Serializable
internal data class HostManifest(
    val name: String,
    val description: String,
    val path: String,
    val type: String = "stdio",
    @SerialName("allowed_origins") val allowedOrigins: List<String>,
) {
    /** Whether this manifest starts [wrapper] for every Heartbeat extension. */
    fun startsWrapper(wrapper: String): Boolean = name == BridgeProtocol.HOST_NAME && type == "stdio" &&
        path == wrapper && allowedOrigins.containsAll(ExtensionIdentity.origins)

    companion object {
        private val format = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** The manifest of this build's host started through [wrapper]. */
        fun of(wrapper: String): HostManifest = HostManifest(
            name = BridgeProtocol.HOST_NAME,
            description = "Heartbeat: bridge between the browser extension and the desktop app",
            path = wrapper,
            allowedOrigins = ExtensionIdentity.origins,
        )

        fun encode(manifest: HostManifest): String = format.encodeToString(serializer(), manifest) + "\n"

        /** @throws IllegalArgumentException for a file that is not a host manifest. */
        fun decode(text: String): HostManifest = format.decodeFromString(serializer(), text)
    }
}
