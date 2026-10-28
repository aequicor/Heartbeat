package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * What one Pi process takes from the profile's engine configuration: files of its agent directory, launch flags,
 * environment and extra `--tools` entries. The empty configuration yields exactly the hermetic default launch.
 * Holds the user's paths and substituted secrets, so it is never printed.
 */
internal data class PiAgentConfig(
    /** Files of the agent directory by name. */
    val files: Map<String, String> = emptyMap(),
    /** Built-in extensions loaded explicitly while discovery stays off (`-e builtin:<name>`). */
    val builtins: List<String> = emptyList(),
    /** Profile resources or a trusted project need discovery; disabled built-ins are excluded instead. */
    val isDiscovering: Boolean = false,
    val isSkillsLoaded: Boolean = false,
    val isPromptsLoaded: Boolean = false,
    /** Project sessions read the project's `AGENTS.md`/`CLAUDE.md`. */
    val hasProjectContext: Boolean = true,
    val environment: Map<String, String> = emptyMap(),
    val tools: List<String> = emptyList(),
    /** MCP servers switched off because a vault entry they refer to is missing. */
    val missingSecrets: Int = 0,
) {
    /** The profile's `AGENTS.md` is passed explicitly where context discovery is off. */
    val hasInstructions: Boolean get() = AGENTS_FILE in files

    override fun toString(): String = "PiAgentConfig(files=${files.keys}, builtins=$builtins, tools=${tools.size})"
}

/**
 * Derives the process configuration from [values]. Keys Heartbeat owns are removed, relative resource paths resolve
 * against the persistent profile [home] (the agent directory lives only as long as the process), and vault
 * references in `mcp.json` are replaced by [secret] values; a server whose secret is missing is switched off.
 * A configured, trusted project discovers its own resources even with an empty profile configuration.
 */
internal suspend fun piAgentConfig(
    values: EngineConfigValues,
    home: Path,
    isProjectTrusted: Boolean = false,
    isMcpEnabled: Boolean = false,
    secret: suspend (String) -> String?,
): PiAgentConfig {
    if (values.isEmpty && !isProjectTrusted) return PiAgentConfig()
    val settings = values.json(PiDocuments.Settings) ?: JsonObject(emptyMap()).takeIf { isProjectTrusted }
    val enabled = enabledBuiltins(values, isMcpEnabled)
    val isDiscovering = isProjectTrusted || settings.hasItems("extensions") || settings.hasItems("packages")
    val hasPackages = settings.hasItems("packages")
    val mcp = values.json(PiDocuments.Mcp)?.takeIf { isMcpEnabled }?.let { substituteSecrets(it, secret) }
    return PiAgentConfig(
        files = agentFiles(values, settings?.let { agentSettings(it, home, enabled, isDiscovering) }, mcp?.document),
        builtins = if (isDiscovering) emptyList() else enabled.map { "builtin:$it" },
        isDiscovering = isDiscovering,
        isSkillsLoaded = settings.hasItems("skills") || hasPackages,
        isPromptsLoaded = isProjectTrusted || settings.hasItems("prompts") || hasPackages,
        hasProjectContext = values.flag(PiHostKeys.ProjectContextFiles, default = true),
        environment = environmentOf(values),
        tools = (enabled.mapNotNull(BuiltinTools::get).flatten() + values.strings(PiHostKeys.ExtensionTools))
            .filter { isMcpEnabled || piToolGroup(it)?.policyName != "mcp" },
        missingSecrets = mcp?.missing ?: 0,
    )
}

private fun enabledBuiltins(values: EngineConfigValues, isMcpEnabled: Boolean): List<String> =
    Builtins.filter { (name, binding) -> values.flag(binding) && (name != "mcp" || isMcpEnabled) }.keys.toList()

private fun agentFiles(values: EngineConfigValues, settings: JsonObject?, mcp: JsonObject?) = buildMap {
    settings?.let { put(SETTINGS_FILE, PiJson.encodeToString(JsonObject.serializer(), it)) }
    mcp?.let { put(MCP_FILE, PiJson.encodeToString(JsonObject.serializer(), it)) }
    values.text(PiDocuments.Agents)?.let { put(AGENTS_FILE, it) }
    values.text(PiDocuments.System)?.let { put(SYSTEM_FILE, it) }
    values.text(PiDocuments.AppendSystem)?.let { put(APPEND_SYSTEM_FILE, it) }
}

private fun environmentOf(values: EngineConfigValues) = buildMap {
    if (values.flag(PiHostKeys.Offline)) put("PI_OFFLINE", "1")
    if (values.flag(PiHostKeys.LongCacheRetention)) put("PI_CACHE_RETENTION", "long")
}

/** `settings.json` for the agent directory. */
private fun agentSettings(
    settings: JsonObject,
    home: Path,
    enabled: List<String>,
    isDiscovering: Boolean,
): JsonObject {
    val owned = PiConfigSchema.ownedKeys.map { it.path.single() }.toSet()
    val result = settings.filterKeys { it !in owned }.toMutableMap()
    ResourceKeys.forEach { key ->
        (result[key] as? JsonArray)?.let { list -> result[key] = JsonArray(list.flatMap { resource(it, home) }) }
    }
    (result["packages"] as? JsonArray)?.let { list ->
        result["packages"] = JsonArray(list.mapNotNull { localPackage(it, home) })
    }
    if (isDiscovering) {
        val disabled = Builtins.keys.filter { it !in enabled }.map { JsonPrimitive("-builtin:$it") }
        val extensions = (result["extensions"] as? JsonArray).orEmpty()
        if (disabled.isNotEmpty()) result["extensions"] = JsonArray(extensions + disabled)
    }
    return JsonObject(result)
}

/**
 * A resource entry as Pi reads it from the temporary agent directory, with relative paths meaning [home] as in
 * `~/.pi/agent`. Pi matches a pattern (`!glob`, a plain glob) against the path relative to the agent directory, the
 * file name and the absolute path, so a relative pattern is kept for its file name match and gets a copy anchored at
 * [home] for the relative one; paths, `+path` and `-path` resolve against [home].
 */
private fun resource(entry: JsonElement, home: Path): List<JsonElement> {
    val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return listOf(entry)
    val exclusion = "!".takeIf { text.startsWith(it) }.orEmpty()
    val pattern = text.removePrefix(exclusion)
    val isPattern = exclusion.isNotEmpty() || (isGlob(text) && text[0] !in "+-")
    if (!isPattern || !isRelativePath(pattern)) return listOf(absolute(entry, home))
    return listOf(entry, JsonPrimitive(exclusion + anchored(pattern, home)))
}

/** [path] under [home] as text: Pi compares patterns with `/` separators, and a glob is not a valid [Path]. */
private fun anchored(path: String, home: Path): String =
    "${home.toString().replace('\\', '/').trimEnd('/')}/${path.removePrefix("./")}"

/** A resource entry with a relative path resolved against [home]; patterns, prefixes and sources stay as they are. */
private fun absolute(entry: JsonElement, home: Path): JsonElement {
    val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return entry
    val prefix = text.take(1).takeIf { it == "+" || it == "-" }.orEmpty()
    val path = text.removePrefix(prefix)
    return when {
        !isRelativePath(path) -> entry
        isGlob(path) -> JsonPrimitive(prefix + anchored(path, home))
        else -> JsonPrimitive(prefix + home.resolve(path).normalize())
    }
}

/** Pi reads an entry with `*` or `?` as a glob. */
private fun isGlob(text: String): Boolean = '*' in text || '?' in text

/**
 * A package entry Pi loads without installing: a remote source becomes its folder installed in [home], a local one
 * resolves against [home]; a remote source that is not installed is left out (the panel installs packages).
 */
private fun localPackage(entry: JsonElement, home: Path): JsonElement? {
    val source = when (entry) {
        is JsonPrimitive -> entry
        is JsonObject -> entry["source"] as? JsonPrimitive
        is JsonArray -> null
    } ?: return entry
    val installed = installedPackagePath(source.content, home)
    val local = when {
        installed == null -> absolute(source, home)
        Files.isDirectory(installed) -> JsonPrimitive(installed.toString())
        else -> return null
    }
    return if (entry is JsonObject) JsonObject(entry + ("source" to local)) else local
}

/** Not a pattern, home path, absolute path or `scheme:` source (`npm:`, `git:`, `builtin:`, a Windows drive). */
private fun isRelativePath(path: String): Boolean = path.isNotEmpty() && path[0] !in "!~/\\" && ':' !in path

private data class Substituted(val document: JsonObject, val missing: Int)

/** `mcp.json` with vault references replaced; servers that miss a secret are switched off. */
private suspend fun substituteSecrets(mcp: JsonObject, secret: suspend (String) -> String?): Substituted {
    val servers = mcp["mcpServers"] as? JsonObject ?: return Substituted(mcp, 0)
    var missing = 0
    val resolved = servers.mapValues { (_, server) ->
        var isComplete = true
        val replaced = replaceStrings(server) { text ->
            val slot = secretSlotOf(text) ?: return@replaceStrings text
            secret(slot) ?: text.also { isComplete = false }
        }
        if (isComplete || replaced !is JsonObject) {
            replaced
        } else {
            missing++
            JsonObject(replaced + ("enabled" to JsonPrimitive(false)))
        }
    }
    return Substituted(JsonObject(mcp + ("mcpServers" to JsonObject(resolved))), missing)
}

private suspend fun replaceStrings(element: JsonElement, replace: suspend (String) -> String): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, child) -> replaceStrings(child, replace) })
        is JsonArray -> JsonArray(element.map { replaceStrings(it, replace) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(replace(element.content)) else element
    }

private fun EngineConfigValues.json(id: ConfigDocumentId): JsonObject? = (documents[id] as? ConfigDocument.Json)?.value

private fun EngineConfigValues.text(id: ConfigDocumentId): String? =
    (documents[id] as? ConfigDocument.Text)?.value?.takeIf { it.isNotBlank() }

private fun EngineConfigValues.flag(binding: ConfigBinding, default: Boolean = false): Boolean =
    (read(binding) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: default

private fun EngineConfigValues.strings(binding: ConfigBinding): List<String> =
    (read(binding) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun JsonObject?.hasItems(key: String): Boolean = (this?.get(key) as? JsonArray)?.isNotEmpty() == true

private val Builtins = mapOf(
    "mcp" to PiHostKeys.Mcp,
    "codemode" to PiHostKeys.Codemode,
    "tool-search" to PiHostKeys.ToolSearch,
    "llama.cpp" to PiHostKeys.LlamaCpp,
)

/** `--tools` entries a built-in extension needs; Pi drops extension tools no entry names. */
private val BuiltinTools = mapOf(
    "mcp" to listOf("mcp__*"),
    "codemode" to listOf("codemode"),
    "tool-search" to listOf("tool_search"),
)

private val ResourceKeys = listOf("extensions", "skills", "prompts")

private val PiJson = Json { prettyPrint = true }

internal const val SETTINGS_FILE = "settings.json"
internal const val MCP_FILE = "mcp.json"
internal const val AGENTS_FILE = "AGENTS.md"
internal const val SYSTEM_FILE = "SYSTEM.md"
internal const val APPEND_SYSTEM_FILE = "APPEND_SYSTEM.md"
