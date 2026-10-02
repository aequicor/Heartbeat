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
    suspend fun write(message: JsonObject)
    override fun close()
}

internal interface CodexTransport {
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
