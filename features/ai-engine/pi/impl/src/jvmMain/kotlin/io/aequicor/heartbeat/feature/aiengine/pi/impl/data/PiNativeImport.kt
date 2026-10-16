package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNote
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNoteReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.isSecretKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretReference
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeImport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.name

/**
 * Import of the user's own Pi configuration (`$PI_CODING_AGENT_DIR` or `~/.pi/agent`) into the profile. Settings
 * lose the keys Heartbeat owns, plain credentials of MCP servers move into the vault, and extension, skill and
 * template folders are copied into the profile home without links or installed dependencies. Credentials of
 * providers (`auth.json`), model and trust files, themes and keybindings are never read. Callers run it on IO.
 */
internal class PiNativeImporter(private val source: Path, private val home: Path) {
    private val log = Log.tag("PiNativeImport")

    /** What an import of each part would bring. */
    fun preview(): ImportPreview = ImportPreview(
        source.toString(),
        Parts.mapNotNull { part ->
            when (part) {
                SETTINGS -> json(SETTINGS_FILE)?.let { ImportItem(part, title(part), it.size, ownedNotes(it)) }

                MCP -> json(MCP_FILE)?.let { mcp ->
                    val servers = mcp["mcpServers"] as? JsonObject
                    ImportItem(part, title(part), servers?.size, moveSecrets(mcp).notes)
                }

                RESOURCES -> resourceFiles().takeIf { it.isNotEmpty() }?.let { ImportItem(part, title(part), it.size) }

                else -> text(part)?.let { ImportItem(part, title(part)) }
            }
        },
    )

    /** Reads [selection]; the caller saves the values and closes the returned secrets. */
    fun read(selection: ImportSelection): NativeImport {
        val notes = mutableListOf<ImportNote>()
        val imported = mutableListOf<String>()
        var settings = readSettings(selection, notes, imported)
        val moved = if (MCP in selection.items) json(MCP_FILE)?.let(::moveSecrets) else null
        moved?.let {
            notes += it.notes
            imported += MCP
        }
        if (RESOURCES in selection.items) {
            val copied = copyResources(notes)
            if (copied.isNotEmpty()) {
                settings = withResources(settings ?: JsonObject(emptyMap()), copied)
                imported += RESOURCES
            }
        }
        val texts = TextParts.filter { (part, _) -> part in selection.items }
            .mapNotNull { (part, id) -> text(part)?.let { Triple(part, id, it) } }
        imported += texts.map { it.first }
        val documents = buildMap {
            settings?.let { put(PiDocuments.Settings, ConfigDocument.Json(it)) }
            moved?.let { put(PiDocuments.Mcp, ConfigDocument.Json(it.document)) }
            texts.forEach { (_, id, text) -> put(id, ConfigDocument.Text(text)) }
        }
        val secrets = moved?.secrets.orEmpty().mapValues { (_, value) -> Secret(value.toCharArray()) }
        log.i { "Pi configuration read for import parts=${imported.size} secrets=${secrets.size} notes=${notes.size}" }
        return NativeImport(EngineConfigValues(documents), secrets, imported, notes)
    }

    private fun readSettings(
        selection: ImportSelection,
        notes: MutableList<ImportNote>,
        imported: MutableList<String>,
    ): JsonObject? {
        if (SETTINGS !in selection.items) return null
        val found = json(SETTINGS_FILE) ?: return null
        notes += ownedNotes(found)
        imported += SETTINGS
        return JsonObject(found - PiConfigSchema.ownedKeys.map { it.path.single() }.toSet())
    }

    private fun json(file: String): JsonObject? {
        val path = source.resolve(file).takeIf { Files.isRegularFile(it) } ?: return null
        if (Files.size(path) > MAX_DOCUMENT_BYTES) return null
        return try {
            Json.parseToJsonElement(Files.readString(path)) as? JsonObject
        } catch (e: SerializationException) {
            // The user's file is not plain JSON; its content is not logged and the part is not offered.
            log.w(e.redactedFailure()) { "Pi file is not plain JSON and is not imported" }
            null
        }
    }

    private fun text(part: String): String? {
        val file = TextFiles[part] ?: return null
        val path = source.resolve(file).takeIf { Files.isRegularFile(it) } ?: return null
        return if (Files.size(path) > MAX_DOCUMENT_BYTES) null else Files.readString(path).takeIf { it.isNotBlank() }
    }

    private fun ownedNotes(settings: JsonObject): List<ImportNote> = PiConfigSchema.ownedKeys
        .filter { it.path.single() in settings }
        .map { ImportNote(SETTINGS, ImportNoteReason.OwnedKeyDropped, it.path) }

    /** Top-level extension, skill and template entries that can be copied. */
    private fun resourceFiles(): List<Path> = ResourceDirectories.flatMap { directory ->
        val root = source.resolve(directory)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@flatMap emptyList()
        Files.list(root).use { entries -> entries.filter(::isCopyable).toList() }
    }

    /** Copies resources into the profile home; returns the copied entries by directory, relative to the home. */
    private fun copyResources(notes: MutableList<ImportNote>): Map<String, List<String>> {
        var budget = MAX_RESOURCE_BYTES
        val copied = mutableMapOf<String, MutableList<String>>()
        resourceFiles().forEach { entry ->
            val directory = entry.parent.name
            val size = treeSize(entry)
            if (size > budget) {
                notes += ImportNote(RESOURCES, ImportNoteReason.TooLarge, listOf(directory, entry.name))
                return@forEach
            }
            budget -= size
            copyTree(entry, home.resolve(directory).resolve(entry.name), notes)
            copied.getOrPut(directory) { mutableListOf() } += "$directory/${entry.name}"
        }
        log.i { "Pi resources copied entries=${copied.values.sumOf { it.size }}" }
        return copied
    }

    private fun copyTree(from: Path, to: Path, notes: MutableList<ImportNote>) {
        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(to)
            Files.list(from).use { children -> children.toList() }.forEach { child ->
                if (isCopyable(child)) {
                    copyTree(child, to.resolve(child.name), notes)
                } else {
                    notes += ImportNote(RESOURCES, ImportNoteReason.Skipped, listOf(child.name))
                }
            }
        } else {
            Files.createDirectories(to.parent)
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun treeSize(path: Path): Long = if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.list(path).use { children -> children.toList() }.filter(::isCopyable).sumOf(::treeSize)
    } else {
        Files.size(path)
    }

    private fun isCopyable(path: Path): Boolean =
        !Files.isSymbolicLink(path) && path.name !in SkippedNames && !path.name.startsWith(".")
}

/** Profile settings that load the copied resources; one entry per extension, folders for skills and templates. */
private fun withResources(settings: JsonObject, copied: Map<String, List<String>>): JsonObject {
    val entries = mapOf(
        "extensions" to copied["extensions"].orEmpty(),
        "skills" to listOfNotNull("skills".takeIf { "skills" in copied }),
        "prompts" to listOfNotNull("prompts".takeIf { "prompts" in copied }),
    ).filterValues { it.isNotEmpty() }
    return JsonObject(
        settings + entries.mapValues { (key, added) ->
            val current = (settings[key] as? JsonArray).orEmpty()
            JsonArray((current + added.map(::JsonPrimitive)).distinct())
        },
    )
}

/** `mcp.json` with plain credentials replaced by vault references. */
internal data class MovedSecrets(
    val document: JsonObject,
    val secrets: Map<String, String>,
    val notes: List<ImportNote>,
) {
    override fun toString(): String = "MovedSecrets(secrets=${secrets.size}, notes=${notes.size})"
}

internal fun moveSecrets(mcp: JsonObject): MovedSecrets {
    val servers = mcp["mcpServers"] as? JsonObject ?: return MovedSecrets(mcp, emptyMap(), emptyList())
    val secrets = mutableMapOf<String, String>()
    val notes = mutableListOf<ImportNote>()
    val moved = servers.mapValues { (name, server) ->
        if (server !is JsonObject) return@mapValues server
        JsonObject(
            server + CredentialMaps.mapNotNull { key ->
                val values = server[key] as? JsonObject ?: return@mapNotNull null
                key to JsonObject(
                    values.mapValues { (variable, value) ->
                        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val path = listOf("mcpServers", name, key, variable)
                        when {
                            text == null || secretSlotOf(text) != null -> value

                            HostReference.matches(text) -> value.also {
                                notes += ImportNote(MCP, ImportNoteReason.HostVariableNotPassed, path)
                            }

                            isSecretKey(variable) && !text.startsWith("!") -> {
                                val slot = slotFor(name, key, variable)
                                secrets[slot] = text
                                notes += ImportNote(MCP, ImportNoteReason.SecretMovedToVault, path)
                                JsonPrimitive(secretReference(slot))
                            }

                            else -> value
                        }
                    },
                )
            },
        )
    }
    return MovedSecrets(JsonObject(mcp + ("mcpServers" to JsonObject(moved))), secrets, notes)
}

/** A bounded vault slot whose hash identifies the original server, credential map and variable independently. */
internal fun slotFor(server: String, credentialMap: String, variable: String): String {
    val readable = "MCP_${server}_$variable".uppercase().replace(NonSlot, "_")
    // JSON preserves case and punctuation and separates components even when their names contain separators.
    val identity = JsonArray(listOf(server, credentialMap, variable).map(::JsonPrimitive)).toString()
    val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())).take(HASH)
    return readable.take(MAX_SLOT - HASH - 1) + "_" + hash.uppercase()
}

private fun title(part: String) = when (part) {
    SETTINGS -> LocalizedText("Settings (settings.json)", mapOf("ru" to "Настройки (settings.json)"))
    MCP -> LocalizedText("MCP servers (mcp.json)", mapOf("ru" to "MCP-серверы (mcp.json)"))
    RESOURCES -> LocalizedText("Extensions, skills and templates", mapOf("ru" to "Расширения, навыки и шаблоны"))
    else -> LocalizedText(TextFiles.getValue(part))
}

private const val SETTINGS = "settings"
private const val MCP = "mcp"
private const val RESOURCES = "resources"
private val TextParts: List<Pair<String, ConfigDocumentId>> = listOf(
    "agents" to PiDocuments.Agents,
    "system" to PiDocuments.System,
    "append_system" to PiDocuments.AppendSystem,
)
private val TextFiles = mapOf("agents" to AGENTS_FILE, "system" to SYSTEM_FILE, "append_system" to APPEND_SYSTEM_FILE)
private val Parts = listOf(SETTINGS, MCP, RESOURCES) + TextParts.map { it.first }
private val ResourceDirectories = listOf("extensions", "skills", "prompts")
private val SkippedNames = setOf("node_modules")
private val CredentialMaps = listOf("env", "headers")
private val HostReference = Regex("\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?")
private val NonSlot = Regex("[^A-Z0-9_]+")
private const val MAX_SLOT = 48
private const val HASH = 24
private const val MAX_DOCUMENT_BYTES = 1_048_576L
private const val MAX_RESOURCE_BYTES = 50L * 1024 * 1024

/** Where the user's own Pi keeps its configuration: `$PI_CODING_AGENT_DIR` or `~/.pi/agent`. */
internal fun piUserAgentDirectory(environment: Map<String, String> = System.getenv()): Path =
    environment["PI_CODING_AGENT_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".pi", "agent")
