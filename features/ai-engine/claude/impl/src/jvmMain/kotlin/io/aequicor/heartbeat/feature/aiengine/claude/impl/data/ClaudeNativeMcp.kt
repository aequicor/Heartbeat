package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Imports only native user/local MCP definitions, without their ambient settings, hooks or instructions.
 * Shared `.mcp.json` stays excluded: Heartbeat project trust does not replace Claude's server approval.
 * The selected CLI config directory and exact physical working directory determine the user/local scopes.
 * Local definitions require an exact normalized working-directory key; canonical git/worktree root scopes
 * outside that key are omitted. Disabled names from all local scopes are conservatively
 * excluded, including canonical git-worktree scopes outside the current ancestor chain. Reserved host names
 * cannot replace Heartbeat's bridges. The CLI
 * expands environment variables in the explicit config against its own captured launch environment.
 * Missing files are empty; unreadable, malformed, nonregular and files exceeding 1 MiB fail the launch closed.
 */
internal suspend fun claudeNativeMcp(
    startup: ClaudeStartup,
    workspace: Path,
    userHome: Path = claudeMcpHome(startup),
    read: (Path) -> JsonObject? = ::readClaudeMcpObject,
): JsonObject {
    if (!startup.isMcpEnabled) return JsonObject(emptyMap())
    currentCoroutineContext().ensureActive()
    val configured = (
        startup.configDirectory ?: startup.environment.lastOrNull {
            it.name == "CLAUDE_CONFIG_DIR"
        }?.value
    )?.takeIf { it.isNotBlank() }
    val directory = configured?.let { workspace.resolve(it).normalize() } ?: userHome.resolve(".claude")
    val legacy = read(directory.resolve(".config.json"))
    val userDirectory = configured?.let { workspace.resolve(it).normalize() } ?: userHome
    val root = legacy ?: read(userDirectory.resolve(".claude.json"))
    currentCoroutineContext().ensureActive()
    val global = root ?: return JsonObject(emptyMap())
    val projects = global.objectOrEmpty("projects")
    val scopes = projects.values.map { it as? JsonObject ?: throw IOException("Invalid MCP configuration") }
    val disabled = scopes.flatMap { it.disabledServers() }.toSet()
    val key = claudeMcpScopeKey(workspace.toAbsolutePath().normalize().toString())
    val local = projects.entries.firstOrNull { (path, _) -> claudeMcpScopeKey(path) == key }?.value as? JsonObject
    val servers = buildMap {
        putAll(global.servers())
        putAll(local?.servers().orEmpty())
    }.filterKeys { it !in disabled && !isHostedMcpNamespace(it) }
    return JsonObject(servers)
}

private fun claudeMcpHome(startup: ClaudeStartup): Path {
    val environment = claudeEnvironment(System.getenv(), startup)
    val key = if (System.getProperty("os.name").startsWith("Windows")) "USERPROFILE" else "HOME"
    val home = environment.entries.lastOrNull { it.key.equals(key, ignoreCase = key == "USERPROFILE") }?.value
    return Path.of(home?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"))
}

/** Claude stores forward slashes on Windows; comparisons also follow that platform's case-insensitive paths. */
internal fun claudeMcpScopeKey(
    path: String,
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
): String = if (isWindows) path.replace('\\', '/').trimEnd('/').lowercase() else path.trimEnd('/')

/** Matches Claude's ASCII tool-name sanitization before checking the complete reserved tool prefix. */
internal fun isHostedMcpNamespace(name: String): Boolean {
    // Java regex consumes Unicode code points; the CLI's non-u JS regex replaces UTF-16 code units.
    val canonical = buildString {
        name.forEach { append(if (it in MCP_NAME_CHARACTERS) it else '_') }
    }
    val prefix = "mcp__${canonical}__"
    return prefix.startsWith("mcp__${HOSTED_TOOLS_SERVER}__") ||
        prefix.startsWith("mcp__${HOSTED_SEARCH_SERVER}__")
}

private fun JsonObject.objectOrEmpty(key: String): JsonObject = when (val value = get(key)) {
    null -> JsonObject(emptyMap())
    is JsonObject -> value
    else -> throw IOException("Invalid MCP configuration")
}

private fun JsonObject.disabledServers(): List<String> = when (val value = get("disabledMcpServers")) {
    null -> emptyList()

    is JsonArray -> value.map {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
            ?: throw IOException("Invalid MCP configuration")
    }

    else -> throw IOException("Invalid MCP configuration")
}

private fun JsonObject.servers(): JsonObject = when (val value = get("mcpServers")) {
    null -> JsonObject(emptyMap())
    is JsonObject -> value
    else -> throw IOException("Invalid MCP configuration")
}

/** Bounded EOF-checked regular-file read; never embeds source paths, config values or parser errors in failures. */
internal fun readClaudeMcpObject(path: Path): JsonObject? {
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null
    return readExistingClaudeMcpObject(path)
}

private fun readExistingClaudeMcpObject(path: Path): JsonObject? = try {
    if (!Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).isRegularFile
    ) {
        throw IOException("Invalid MCP configuration")
    }
    val bytes = Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid MCP configuration")
        val buffer = java.nio.ByteBuffer.allocate(MAX_MCP_CONFIG_BYTES + 1)
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read through EOF or one byte beyond the limit, including files that grow after opening.
        }
        if (buffer.position() > MAX_MCP_CONFIG_BYTES) throw IOException("MCP configuration exceeds limit")
        buffer.array().copyOf(buffer.position())
    }
    try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (failure: IllegalArgumentException) {
        importLog.w(failure.redacted()) { "Native MCP configuration is malformed" }
        throw IOException("Invalid MCP configuration")
    }
} catch (missing: NoSuchFileException) {
    importLog.w(missing.redacted()) { "Native MCP configuration is absent" }
    null
}

/** Owner-only explicit config: imported names are inserted first, so hosted bridge definitions always win. */
internal fun mergeClaudeMcp(servers: JsonObject, bridge: Path?, directory: Path): Path {
    val hosted = bridge?.let(::readClaudeMcpObject)?.servers().orEmpty()
    val config = JsonObject(mapOf("mcpServers" to JsonObject(servers + hosted)))
    val file = bridge ?: Files.createTempFile(directory, "heartbeat-mcp-", ".json")
    try {
        restrictToOwner(file, directory = false)
        Files.writeString(file, config.toString())
        return file
    } catch (failure: IOException) {
        Files.deleteIfExists(file)
        throw failure
    }
}

private const val MCP_NAME_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
private const val MAX_MCP_CONFIG_BYTES = 1024 * 1024

private val importLog = Log.tag("ClaudeProcess/NativeMcp")
