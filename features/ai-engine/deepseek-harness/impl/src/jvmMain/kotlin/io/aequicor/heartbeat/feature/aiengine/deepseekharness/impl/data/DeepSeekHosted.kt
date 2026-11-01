package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeAttachment
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.concurrent.Volatile

/** One native session's declarations and revocable MCP capability, never a source of action permission. */
internal class DeepSeekHosted(
    val names: Map<String, String> = emptyMap(),
    val attachment: AgentToolBridgeAttachment? = null,
) : AutoCloseable {
    @Volatile
    var session: DeepSeekSession? = null

    val servers: List<JsonObject> = attachment?.let { bridge ->
        listOf(
            buildJsonObject {
                put("type", "http")
                put("name", HOSTED_TOOLS_SERVER)
                put("url", "${bridge.endpoint.url.trimEnd('/')}/mcp")
                put(
                    "headers",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("name", "Authorization")
                                put("value", "Bearer ${bridge.endpoint.token}")
                            },
                        ),
                    ),
                )
            },
        )
    }.orEmpty()

    override fun close() {
        attachment?.close()
    }
}

/** DSH's publicToolName algorithm, including the lossy-name hash in dsh-mcp-client 0.2.1-rc.5. */
internal fun dshHostedPublicName(name: String): String {
    val joined = "mcp__${HOSTED_TOOLS_SERVER}__$name"
    val normalized = joined.replace(Regex("[^A-Za-z0-9_-]"), "_")
    if (joined == normalized && joined.length <= DSH_NAME_LIMIT) return joined
    val hash = MessageDigest.getInstance("SHA-256").digest("$HOSTED_TOOLS_SERVER\u0000$name".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(DSH_HASH_LENGTH)
    return "${normalized.take(DSH_NAME_LIMIT - DSH_HASH_LENGTH - 1)}_$hash"
}

private const val DSH_NAME_LIMIT = 64
private const val DSH_HASH_LENGTH = 12
