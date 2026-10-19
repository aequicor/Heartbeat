package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import kotlinx.serialization.json.JsonObject

/** Host-captured command context. Providers never authorize or execute the reviewed operation. */
public fun interface CommandSafetyEvidence {
    /** Bounded, secret-free, stable evidence, recaptured before execution; null when not applicable. */
    public suspend fun capture(directory: String, command: String, environment: Map<String, String>?): JsonObject?
}

/** At most one platform provider owns the evidence for a command. Ambiguity fails the snapshot. */
internal suspend fun Set<CommandSafetyEvidence>.captureCommandEvidence(
    directory: String,
    command: String,
    environment: Map<String, String>? = null,
): JsonObject? = mapNotNull {
    it.capture(
        directory,
        command,
        environment,
    )
}.also { require(it.size <= 1) }.singleOrNull()
