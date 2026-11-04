package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Internal wire boundary. Implementations serialize writes and sanitize failures before crossing it. */
internal interface CodexWire : AutoCloseable {
    val messages: Flow<JsonObject>

    /** Read-only ownership snapshot; unsupported transports cannot supply evidence for a stored stop. */
    suspend fun processOwner(): CodexExecutionOwner? = null

    /**
     * Protected execution needs a fresh offline inventory of this executable and the working thread's surface.
     * Only the desktop transport can run the probe; every other transport fails closed. An implementation must
     * also refuse [CodexInventoryTarget.effective] with [requireCodexInventoryConfig] against the same executable's
     * clean-home `config/read`, before any thread starts: the caller no longer checks the working config itself.
     */
    suspend fun verifyInventory(target: CodexInventoryTarget, off: CodexNativeOff): Unit = unsupported()

    suspend fun write(message: JsonObject)
    override fun close()
}

/** Immutable launch selection shared by a runtime's metadata and execution processes. Never logs settings. */
internal interface PreparedCodexLaunch {
    /** Profile opt-in captured together with this executable and configuration directory. */
    val isMcpEnabled: Boolean get() = false

    /** Absolute executable this captured launch starts, or null when no external artifact is selected. */
    val executable: String? get() = null

    /** SHA-256 of the captured executable, or null when this transport cannot read it. */
    suspend fun executableSha256(): String? = null

    /** Starts the captured executable with the captured home, arguments and environment. */
    suspend fun open(): CodexWire

    /** Applies only the typed restrictions for subsequent effective-config and inventory checks. */
    suspend fun open(off: CodexNativeOff): CodexWire {
        if (off.isRestricted) unsupported()
        return open()
    }

    /**
     * Stops only identity-checked stored processes, without opening a thread. Newly observed descendants are
     * recorded before signalling; the returned owner includes concurrent observations. Null aborts signalling.
     * [beginInspection] durably marks discovery pending before enumerating new children; [record] clears it only
     * after a complete snapshot, and no signal precedes it. A newer inspection replaces an interrupted one, which
     * can then neither record nor signal; a descendant that exits during enumeration is gone, not unknown.
     * True proves exit of those observed processes only, not containment of unobserved descendants.
     */
    suspend fun stop(
        owner: CodexExecutionOwner,
        beginInspection: suspend () -> Boolean,
        record: suspend (CodexExecutionOwner) -> CodexExecutionOwner?,
    ): Boolean = false

    /**
     * Version that the already held metadata process of this captured launch reported in its own handshake;
     * unknown by default and never established by executing the selected file with a version probe.
     */
    suspend fun version(): String? = null

    /**
     * Records the version [version] reported by the metadata process this captured launch already started, so a
     * subsequent restricted launch can check its floor against that observation instead of probing the file.
     * The observation belongs to exactly this executable, configuration and environment: never record a version
     * obtained from another file, another captured launch or another account. Null and blank leave it unknown,
     * which keeps a restricted launch refused.
     */
    suspend fun observeVersion(version: String?) {}
}

/**
 * Version of the engine that produced [userAgent], for example `heartbeat/0.166.0-alpha.5.1 (Mac OS; arm64)…`.
 * The process reports its own build: the leading `<originator>/<version>` token is used and anything else, or an
 * absent payload, stays unknown. SemVer validity is checked by the caller's policy, not here.
 */
internal fun codexEngineVersion(userAgent: String?): String? {
    val token = userAgent?.substringBefore(' ')?.takeIf(String::isNotBlank) ?: return null
    val version = token.substringAfter('/', "").takeIf(String::isNotBlank) ?: return null
    return version.takeIf { it.none(Char::isWhitespace) }
}

internal interface CodexTransport {
    /** Captures process settings once. Immutable/mobile transports can use the default implementation. */
    suspend fun prepare(): PreparedCodexLaunch = object : PreparedCodexLaunch {
        override suspend fun open(): CodexWire = this@CodexTransport.open()
    }

    /** Opens an app-server started with the profile's current launch context. */
    suspend fun open(): CodexWire

    /** Opens an app-server started as [launch] describes (a sign-in, a staged copy). */
    suspend fun open(launch: LaunchContext): CodexWire = open()

    suspend fun available(): EngineAvailability

    /** The executable [launch] would start and its version; never starts the app-server. */
    suspend fun locate(launch: LaunchContext): Installation = Installation(InstallSource.Missing)

    /** File checks of [settings]: a missing executable or home directory. */
    suspend fun check(settings: LaunchSettings): List<LaunchProblem> = emptyList()

    /** The release build of this host, or null where Codex publishes none. */
    fun releaseTarget(): CodexTarget? = null
}

internal fun json(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*values))
internal fun String.json(): JsonPrimitive = JsonPrimitive(this)
internal fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(name: String): JsonObject = get(name) as? JsonObject ?: protocolFailure()
internal fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: protocolFailure()
internal fun protocolFailure(): Nothing = throw EngineException(
    EngineFailure.Transport(TransportFailureReason.ProtocolViolation),
)
internal fun fail(reason: EngineFailure): Nothing = throw EngineException(reason)
internal fun unsupported(): Nothing = fail(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
