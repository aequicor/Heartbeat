package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val log = Log.tag("CodexHostedManifests")

internal const val MANIFEST_VERSION = 1

/** Hosted declarations of a thread being opened: all current tools, or the ones a resumed thread was created with. */
internal sealed interface HostedThread {
    /** A new thread declares the current tools. */
    data object New : HostedThread

    /** A resumed thread keeps the [tools] it declared at creation. */
    data class Resumed(val tools: Set<String>) : HostedThread
}

/**
 * Hosted tools of a thread being opened: the [manifest] saved for a new thread, the declarations and instructions
 * sent with its start or resume ([parameters]), and whether its hosted tool calls are served ([isServed]).
 */
// A generated data-class toString would expose hosted instructions and workspace paths.
@Suppress("UseDataClass")
internal class HostedOpening(
    val manifest: String?,
    val parameters: Pair<List<JsonObject>, String>?,
    val isServed: Boolean,
)

/**
 * Whether a thread created with [stored] declarations may resume while the host offers [expected]. Version and
 * workspace must match; a tool present in both must be declared identically. Added tools stay invisible to the
 * thread and removed ones are refused when called, so neither blocks the resume.
 */
internal fun isManifestCompatible(stored: String, workspace: WorkspaceRef?, expected: String?): Boolean = try {
    val saved = Json.parseToJsonElement(stored).jsonObject
    val current = expected?.let(::manifestTools).orEmpty()
    saved["version"] == JsonPrimitive(MANIFEST_VERSION) &&
        (saved["workspace"] ?: JsonNull) == (workspace?.value?.let(::JsonPrimitive) ?: JsonNull) &&
        manifestTools(stored).all { (name, declaration) -> current[name]?.let { it == declaration } ?: true }
} catch (e: SerializationException) {
    log.w(e) { "Stored hosted tool manifest is unreadable" }
    false
} catch (e: IllegalArgumentException) {
    log.w(e) { "Stored hosted tool manifest is malformed" }
    false
}

/** Declarations of a manifest by tool name; an entry without a name is not a declaration. */
internal fun manifestTools(manifest: String): Map<String, JsonObject> =
    ((Json.parseToJsonElement(manifest).jsonObject["tools"] as? JsonArray) ?: JsonArray(emptyList()))
        .mapNotNull { element ->
            val declaration = element as? JsonObject ?: return@mapNotNull null
            val name = (declaration["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            name?.let { it to declaration }
        }
        .toMap()
