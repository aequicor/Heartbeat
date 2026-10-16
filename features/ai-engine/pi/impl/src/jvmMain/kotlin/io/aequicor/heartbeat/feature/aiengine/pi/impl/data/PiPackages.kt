package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.PackageOperation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pi packages of the profile, installed by Pi itself into the persistent profile [home] (npm packages under
 * `npm/node_modules`, git ones under `git/<host>/<path>`). A process never installs: its agent directory lives only
 * as long as the process, so the launch maps each configured remote source to its installed folder and leaves out
 * sources that are not installed yet. [run] executes `pi <arguments>` with [home] as the agent directory and returns
 * its exit code.
 */
internal class PiPackages(private val home: Path, private val run: suspend (List<String>) -> Int) {
    private val log = Log.tag("PiPackages")

    /** Makes the installed packages match [config]: installs missing sources and removes unlisted ones. */
    suspend fun apply(operation: PackageOperation, config: EngineConfigValues, progress: (Int, Int) -> Unit) {
        writeSettings(config)
        val desired = remoteSources(config)
        val installed = installedSources()
        val steps = when (operation) {
            // Pi removes by package identity, not version/ref; remove old sources before installing replacements.
            PackageOperation.Sync -> (installed - desired).map { listOf("remove", it) } +
                (desired - installed).map { listOf("install", it) }

            PackageOperation.Update -> listOf(listOf("update", "--extensions"))
        }
        log.i { "Pi packages $operation steps=${steps.size}" }
        progress(0, steps.size)
        steps.forEachIndexed { index, arguments ->
            val code = run(arguments)
            if (code != 0) {
                log.w { "Pi package command failed command=${arguments.first()} exit=$code" }
                throw ManagementException(ManagementFailure.Install(InstallFailureReason.PackageFailed))
            }
            progress(index + 1, steps.size)
        }
    }

    /** Keeps Pi's own settings in [home] limited to what package commands read: the npm command and the list. */
    private fun writeSettings(config: EngineConfigValues) {
        val current = readSettings()
        val npm = settingsOf(config)?.get("npmCommand")
        val updated = JsonObject(
            buildMap {
                current["packages"]?.let { put("packages", it) }
                npm?.let { put("npmCommand", it) }
            },
        )
        Files.createDirectories(home)
        Files.writeString(home.resolve(SETTINGS_FILE), PackageJson.encodeToString(JsonObject.serializer(), updated))
    }

    private fun installedSources(): List<String> = sourcesOf(readSettings()["packages"]).filter(::isRemoteSource)

    private fun readSettings(): JsonObject {
        val file = home.resolve(SETTINGS_FILE).takeIf { Files.isRegularFile(it) } ?: return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: SerializationException) {
            log.w(e.redactedFailure()) { "Pi package settings are unreadable; they are rewritten" }
            JsonObject(emptyMap())
        }
    }
}

/** Remote (npm and git) package sources the configuration lists, in order and without repeats. */
internal fun remoteSources(config: EngineConfigValues): List<String> =
    sourcesOf(settingsOf(config)?.get("packages")).filter(::isRemoteSource).distinct()

/** The installed folder of a remote [source] in [home], or null for a local source. */
internal fun installedPackagePath(source: String, home: Path): Path? = when {
    source.startsWith(NPM) -> home.resolve("npm").resolve("node_modules").resolve(npmName(source.removePrefix(NPM)))
    isRemoteSource(source) -> gitLocation(source)?.let { (host, path) -> home.resolve("git/$host").resolve(path) }
    else -> null
}

/** `@scope/name@1.0` → `@scope/name`; `name@1.0` → `name`. */
private fun npmName(spec: String): String {
    val start = if (spec.startsWith("@")) spec.indexOf('/').coerceAtLeast(0) else 0
    val version = spec.indexOf('@', start + 1).takeIf { it > 0 } ?: spec.length
    return spec.substring(0, version)
}

/** `git:github.com/owner/repo@v1` or `https://github.com/owner/repo` → host and repository path. */
private fun gitLocation(source: String): Pair<String, String>? {
    val address = source.removePrefix("git:").substringAfter("://").substringBefore('@').removeSuffix(".git")
    val host = address.substringBefore('/')
    val path = address.substringAfter('/', "")
    return if (host.isEmpty() || path.isEmpty() || ".." in path) null else host to path
}

private fun isRemoteSource(source: String): Boolean =
    source.startsWith("npm:") || source.startsWith("git:") || source.startsWith("https://") ||
        source.startsWith("http://")

private fun sourcesOf(packages: JsonElement?): List<String> = (packages as? JsonArray).orEmpty().mapNotNull { entry ->
    when (entry) {
        is JsonPrimitive -> entry.takeIf { it.isString }?.content
        is JsonObject -> (entry["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        is JsonArray -> null
    }
}

private fun settingsOf(config: EngineConfigValues): JsonObject? =
    (config.documents[PiDocuments.Settings] as? ConfigDocument.Json)?.value

private val PackageJson = Json { prettyPrint = true }
private const val NPM = "npm:"
