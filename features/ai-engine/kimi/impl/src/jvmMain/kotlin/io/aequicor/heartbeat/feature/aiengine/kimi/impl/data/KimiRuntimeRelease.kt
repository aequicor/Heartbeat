package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Adapter compatibility pin; published versions are immutable and old runtime releases remain available. */
internal const val KIMI_RUNTIME_VERSION = "2.4.0-hb7"
internal const val KIMI_SOURCE_COMMIT = "f26e4d91ef8d5d233f6c20b42a150f96924d4f5e"
internal const val KIMI_PERMISSION_GATE = 1
internal const val KIMI_RELEASE_ROOT =
    "https://github.com/aequicor/Heartbeat/releases/download/runtime-kimi-$KIMI_RUNTIME_VERSION"

/** The five native platforms Heartbeat can prepare; an unsupported architecture never picks another build. */
internal fun kimiRuntimeTarget(
    os: String = System.getProperty("os.name"),
    arch: String = System.getProperty("os.arch"),
): String? {
    val architecture = when (arch.lowercase()) {
        "amd64", "x86_64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> return null
    }
    return when {
        os.startsWith("Windows", ignoreCase = true) -> "win32-$architecture"
        os.startsWith("Mac", ignoreCase = true) -> "darwin-$architecture"
        os.startsWith("Linux", ignoreCase = true) && architecture == "x64" -> "linux-x64"
        else -> null
    }
}

/** Resolves an exact Heartbeat-owned release, never upstream latest or a system CLI. */
internal suspend fun kimiInstallPlan(feeds: ReleaseFeeds, target: String): InstallPlan {
    if (target !in KimiRuntimeTargets) kimiInstallFailure(InstallFailureReason.NoAssetForPlatform)
    val document = feeds.document("$KIMI_RELEASE_ROOT/runtime-manifest.json", GitHubDownloadHosts)
    val manifest = try {
        Json.parseToJsonElement(document) as? JsonObject
    } catch (error: SerializationException) {
        Log.tag("KimiRuntimeRelease").w(error) { "Kimi runtime manifest is invalid" }
        kimiInstallFailure(InstallFailureReason.UntrustedSource)
    } ?: kimiInstallFailure(InstallFailureReason.UntrustedSource)
    val isIdentity = manifest.number("schemaVersion") == 1 && manifest.text("engine") == "kimi"
    val isPinned = manifest.text("version") == KIMI_RUNTIME_VERSION &&
        manifest.text("sourceCommit") == KIMI_SOURCE_COMMIT && manifest.number("permissionGate") == KIMI_PERMISSION_GATE
    if (!isIdentity || !isPinned) kimiInstallFailure(InstallFailureReason.UntrustedSource)
    val entry = (manifest["platforms"] as? JsonObject)?.get(target) as? JsonObject
        ?: kimiInstallFailure(InstallFailureReason.NoAssetForPlatform)
    val file = "kimi-$KIMI_RUNTIME_VERSION-$target.zip"
    val checksum = entry.text("sha256")?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        ?: kimiInstallFailure(InstallFailureReason.UntrustedSource)
    val size = (entry["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        ?: kimiInstallFailure(InstallFailureReason.UntrustedSource)
    if (entry.text("file") != file) kimiInstallFailure(InstallFailureReason.UntrustedSource)
    return InstallPlan(
        KIMI_RUNTIME_VERSION,
        "$KIMI_RELEASE_ROOT/$file",
        checksum,
        size,
        ArchiveKind.Zip(),
        if (target.startsWith("win32-")) "kimi.exe" else "kimi",
        GitHubDownloadHosts,
        isRetainedForRollback = true,
    )
}

internal fun kimiInstallFailure(reason: InstallFailureReason): Nothing =
    throw ManagementException(ManagementFailure.Install(reason))

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private val KimiRuntimeTargets = setOf("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64")
