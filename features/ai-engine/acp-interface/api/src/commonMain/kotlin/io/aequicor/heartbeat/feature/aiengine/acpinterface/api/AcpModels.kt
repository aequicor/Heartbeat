package io.aequicor.heartbeat.feature.aiengine.acpinterface.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Implementation metadata used during protocol negotiation. */
@Serializable
public data class AcpImplementation(val name: String, val version: String, val title: String? = null)

/** Advertised authentication method. Authentication itself remains an explicit adapter operation. */
@Serializable
public data class AcpAuthMethod(val id: String, val name: String, val description: String? = null)

/** Negotiated agent capabilities; absent capabilities are unsupported. Unknown capability fields are preserved. */
@Serializable
public data class AcpInitialization(
    val protocolVersion: Int,
    val agentCapabilities: JsonObject = JsonObject(emptyMap()),
    val authMethods: List<AcpAuthMethod> = emptyList(),
    val agentInfo: AcpImplementation? = null,
)

/** Native session identity and the complete response (including optional modes/configuration). */
public data class AcpSession(val sessionId: String, val metadata: JsonObject)

/** Native completion reason. Kept open for future protocol extensions. */
@Serializable
public data class AcpPromptResult(val stopReason: String)

/** Agent permission request. The tool call is descriptive data, never executable client instructions. */
@Serializable
public data class AcpPermissionRequest(
    val sessionId: String,
    val toolCall: JsonObject,
    val options: List<AcpPermissionOption>,
)

/** One agent-provided permission choice; kind may be an unfamiliar extension. */
@Serializable
public data class AcpPermissionOption(val optionId: String, val name: String, val kind: String)

/** Explicit response to a permission request. The default handler never grants a permission. */
public sealed interface AcpPermissionOutcome {
    /** No choice was made, or the corresponding turn was cancelled. */
    public data object Cancelled : AcpPermissionOutcome

    /** The exact opaque ID of an option offered in this request. */
    public data class Selected(val optionId: String) : AcpPermissionOutcome
}

/** Safe exception message. Remote text/data are available separately and must not be logged. */
public sealed class AcpException(message: String) : Exception(message) {
    /** Transport closed or its framing/IO failed; delivery of outstanding requests may be ambiguous. */
    public class Disconnected : AcpException("ACP connection closed; pending operation outcome may be unknown")

    /** The agent process could not be started; the platform error is logged sanitized, not exposed. */
    public class LaunchFailed : AcpException("ACP agent process could not be started")

    /** Invalid JSON-RPC or ACP response, including unsupported negotiated protocol versions. */
    public class Protocol : AcpException("Invalid or unsupported ACP protocol message")

    /** Remote JSON-RPC error; the exception's message contains only the numeric code. */
    public class Remote(
        public val code: Int,
        public val remoteMessage: String,
        public val data: kotlinx.serialization.json.JsonElement? = null,
    ) : AcpException("ACP remote error code=$code")
}
