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
    /** User extensions or packages are configured: discovery runs and disabled built-ins are excluded instead. */
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
 */
internal suspend fun piAgentConfig(
    values: EngineConfigValues,
    home: Path,
    secret: suspend (String) -> String?,
): PiAgentConfig {
    if (values.isEmpty) return PiAgentConfig()
    val settings = values.json(PiDocuments.Settings)
    val enabled = Builtins.filter { (_, binding) -> values.flag(binding) }.map { (name, _) -> name }
    val isDiscovering = settings.hasItems("extensions") || settings.hasItems("packages")
    val hasPackages = settings.hasItems("packages")
    val mcp = values.json(PiDocuments.Mcp)?.let { substituteSecrets(it, secret) }
    return PiAgentConfig(
        files = agentFiles(values, settings?.let { agentSettings(it, home, enabled, isDiscovering) }, mcp?.document),
        builtins = if (isDiscovering) emptyList() else enabled.map { "builtin:$it" },
        isDiscovering = isDiscovering,
        isSkillsLoaded = settings.hasItems("skills") || hasPackages,
        isPromptsLoaded = settings.hasItems("prompts") || hasPackages,
        hasProjectContext = values.flag(PiHostKeys.ProjectContextFiles, default = true),
        environment = environmentOf(values),
        tools = enabled.mapNotNull(BuiltinTools::get).flatten() + values.strings(PiHostKeys.ExtensionTools),
        missingSecrets = mcp?.missing ?: 0,
    )
}

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
        (result[key] as? JsonArray)?.let { list -> result[key] = JsonArray(list.map { absolute(it, home) }) }
    }
    (result["packages"] as? JsonArray)?.let { list ->
        result["packages"] = JsonArray(
            list.map { absolutePackage(it, home) },
        )
    }
    if (isDiscovering) {
        val disabled = Builtins.keys.filter { it !in enabled }.map { JsonPrimitive("-builtin:$it") }
        val extensions = (result["extensions"] as? JsonArray).orEmpty()
        if (disabled.isNotEmpty()) result["extensions"] = JsonArray(extensions + disabled)
    }
    return JsonObject(result)
}

/** A resource entry with a relative path resolved against [home]; patterns, prefixes and sources stay as they are. */
private fun absolute(entry: JsonElement, home: Path): JsonElement {
    val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return entry
    val prefix = text.take(1).takeIf { it == "+" || it == "-" }.orEmpty()
    val path = text.removePrefix(prefix)
    return if (isRelativePath(path)) JsonPrimitive(prefix + home.resolve(path).normalize()) else entry
}

private fun absolutePackage(entry: JsonElement, home: Path): JsonElement = when (entry) {
    is JsonPrimitive -> absolute(entry, home)
    is JsonObject -> entry["source"]?.let { JsonObject(entry + ("source" to absolute(it, home))) } ?: entry
    is JsonArray -> entry
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
