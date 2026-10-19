package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

/** Host-captured command context. Providers never authorize or execute the reviewed operation. */
public fun interface CommandSafetyEvidence {
    /** Bounded, secret-free, stable evidence, recaptured before execution; null when not applicable. */
    public suspend fun capture(
        directory: String,
        command: String,
        environment: Map<String, String>?,
        reviewFiles: List<String>,
    ): JsonObject?
}

/** At most one platform provider owns the evidence for a command. Ambiguity fails the snapshot. */
internal suspend fun Set<CommandSafetyEvidence>.captureCommandEvidence(
    directory: String,
    command: String,
    environment: Map<String, String>? = null,
    reviewFiles: List<String> = emptyList(),
): JsonObject? = mapNotNull {
    it.capture(
        directory,
        command,
        environment,
        reviewFiles,
    )
}.also { require(it.size <= 1) }.singleOrNull()

/** File names select evidence only; they never change the command or prove the role claimed by its caller. */
internal fun commandReviewFiles(arguments: JsonObject?): List<String> {
    val raw = arguments?.get("review_files") ?: return emptyList()
    require(raw is JsonArray && raw.size <= MAX_COMMAND_REVIEW_FILES) { "Invalid command review file list" }
    return raw.asSequence().map { value ->
        val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(path != null && path.isNotBlank() && path.length <= MAX_COMMAND_REVIEW_PATH) {
            "Invalid command review file path"
        }
        path
    }.distinct().sorted().toList()
}

/** Names the host-owned evidence without allowing caller-controlled JSON keys to replace snapshot identity. */
internal fun JsonObjectBuilder.putCommandSafetyEvidence(evidence: JsonObject?) {
    evidence ?: return
    val key = when ((evidence["kind"] as? JsonPrimitive)?.content) {
        "projectNetwork" -> "projectNetworkEvidence"
        "localGit" -> "localGitEvidence"
        else -> "gitPushEvidence"
    }
    put(key, evidence)
}

internal const val MAX_COMMAND_REVIEW_FILES = 16
private const val MAX_COMMAND_REVIEW_PATH = 2048
