package io.aequicor.heartbeat.feature.aiengine.acpinterface.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * Creates a protocol service, not an engine registration. Construction performs no IO.
 * The engine adapter supplies a profile-owned scope and remains responsible for credential routing.
 */
public interface AcpClientFactory {
    /**
     * Takes ownership of [transport] and installs [handler] before reading any frames.
     * Cancelling [scope] closes the transport. Closing a UI handle must not cancel this scope.
     */
    public fun connect(transport: AcpTransport, scope: CoroutineScope, handler: AcpClientHandler): AcpConnection
}

/**
 * ACP v1 connection: Created -> Initializing -> Ready -> Closed.
 * Failed initialization or transport failure is terminal; reconnect explicitly with a new transport.
 * Calls before initialization, duplicate initialization and concurrent prompts in one session fail locally.
 * Cancelling a caller only abandons its wait: an already submitted operation continues in the owner scope.
 * The client never retries requests, switches credentials, or claims that a local cancellation stopped a turn.
 */
public interface AcpConnection {
    /** Negotiates v1; unsupported versions close the connection. */
    public suspend fun initialize(client: AcpImplementation): AcpInitialization

    /** Invokes an advertised authentication method without selecting one implicitly. */
    public suspend fun authenticate(methodId: String)

    /** Creates a session in an absolute working directory. MCP servers are not configured by this service. */
    public suspend fun newSession(cwd: String): AcpSession

    /** Replays updates through the handler before returning; requires the loadSession capability. */
    public suspend fun loadSession(sessionId: String, cwd: String): AcpSession

    /** Sends text and waits for the agent's final stop reason; updates and permissions are handled meanwhile. */
    public suspend fun prompt(sessionId: String, text: String): AcpPromptResult

    /** Sends a notification and cancels pending permission decisions; completion still comes from prompt. */
    public suspend fun cancel(sessionId: String)

    /** Idempotently closes the transport and fails all pending calls. */
    public suspend fun close()
}

/** Callbacks installed for the whole connection, including updates sent while loading a session. */
public interface AcpClientHandler {
    /**
     * Receives every update in wire order, including unknown variants, before the corresponding prompt result.
     * Must return promptly and must not await calls on this connection. Throwing closes the connection.
     */
    public suspend fun onSessionUpdate(update: AcpSessionUpdate)

    /** May suspend for a user decision. Returning an option not offered by the agent is rejected. */
    public suspend fun requestPermission(request: AcpPermissionRequest): AcpPermissionOutcome =
        AcpPermissionOutcome.Cancelled
}

/**
 * Single-reader message transport. Each receive yields one complete JSON-RPC frame without a newline.
 * Implementations must be main-safe; close must unblock receive and send and be idempotent.
 * Raw frames, environment, stderr and remote error messages must never be logged.
 */
public interface AcpTransport {
    /** Returns null on EOF; the protocol client treats EOF as disconnection. */
    public suspend fun receive(): String?

    /** Writes one frame. The protocol client serializes calls to this method. */
    public suspend fun send(frame: String)

    /** Releases resources and unblocks outstanding IO without waiting on the calling thread. */
    public fun close()
}

/** Starts desktop processes explicitly. Mobile platforms expose isSupported=false and reject open. */
public interface AcpStdioTransportFactory {
    /** Whether this platform can run desktop stdio agents. */
    public val isSupported: Boolean

    /**
     * Runs exactly the executable and arguments supplied, without a shell or implicit credential changes.
     * On Windows the JDK runs `.cmd`/`.bat` files through `cmd.exe`; adapters should resolve a real executable.
     * The caller owns the returned transport and must close it if it is never passed to [AcpClientFactory.connect].
     * Closing stops the process together with the descendants alive at that moment.
     *
     * @throws AcpException.LaunchFailed when the process cannot be started or its environment is invalid.
     * @throws UnsupportedOperationException when [isSupported] is false.
     */
    public suspend fun open(command: AcpCommand): AcpTransport
}

/**
 * Process invocation owned by an engine adapter. Environment inherits the parent process unless disabled;
 * adapters requiring credential isolation must set isEnvironmentInherited=false and supply the complete map.
 * No member of this class is safe for logging.
 */
public data class AcpCommand(
    public val executable: String,
    public val arguments: List<String> = emptyList(),
    public val workingDirectory: String? = null,
    public val environment: Map<String, String> = emptyMap(),
    public val isEnvironmentInherited: Boolean = true,
) {
    init {
        require(executable.isNotBlank()) { "Executable is required" }
    }

    /** Keeps process arguments and environment out of diagnostic interpolation. */
    override fun toString(): String = "AcpCommand(<redacted>)"
}

/** Unmodified extensible update payload; message IDs, tool calls and new variants are preserved. */
public data class AcpSessionUpdate(val sessionId: String, val update: JsonObject)
