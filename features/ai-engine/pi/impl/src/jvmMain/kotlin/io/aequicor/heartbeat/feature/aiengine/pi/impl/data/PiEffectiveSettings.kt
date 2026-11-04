package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.MASKED_SETTING_VALUE
import io.aequicor.heartbeat.feature.aiengine.facade.api.ObservedValue
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsLayer
import io.aequicor.heartbeat.feature.aiengine.facade.api.at
import io.aequicor.heartbeat.feature.aiengine.facade.api.isSecretKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EffectiveSettingsRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineDeclaredResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineEffectiveSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/**
 * The engine layer of Pi for one launch: the settings and instruction content Heartbeat writes into the agent
 * directory that Pi reads through `PI_CODING_AGENT_DIR`, per documented surface of [PiConfigSchema].
 *
 * Only a value the composed agent files really contain is claimed. A key Heartbeat does not write stays unreported,
 * because Pi would resolve it from its own default that Heartbeat cannot observe. MCP surfaces stay unreported as
 * well: `mcp.json` is written only while the launch allows MCP and its content has vault references substituted, so
 * neither its presence nor its value is provable from the saved configuration alone.
 *
 * A resolution is declared only for a value written verbatim at its documented key, while the saved project has no
 * value of its own there; the project layer, including additive project instructions, stays with the facade. Vault
 * references and secret-named entries are masked and never resolved, no project file or engine process is touched,
 * and a cancelled call returns no claim.
 */
internal suspend fun piEffectiveSettings(request: EffectiveSettingsRequest, home: Path): EngineEffectiveSettings {
    currentCoroutineContext().ensureActive()
    // The same composition a launch writes, without resolving any vault entry: MCP is off here on purpose, because
    // the launch decides whether Pi receives mcp.json at all.
    val agent = piAgentConfig(
        values = request.profile,
        home = home,
        isProjectTrusted = request.project != null,
        secret = { null },
    )
    val settings = agent.files[SETTINGS_FILE]?.let(::piSettingsOf)
    val values = LinkedHashMap<String, ObservedValue>()
    val merged = LinkedHashMap<String, EngineDeclaredResolution>()
    PiEffectiveSurfaces.forEach { field ->
        val binding = field.binding
        if (binding.document == PiDocuments.Host) return@forEach
        val layer = when (binding) {
            is ConfigBinding.JsonPath -> piSettingsLayer(field, binding, request, settings)
            is ConfigBinding.WholeDocument -> piDocumentLayer(field, binding, agent.files)
        } ?: return@forEach
        values[field.id] = layer.value
        val resolution = layer.resolution
        if (resolution != null) merged[field.id] = resolution
    }
    currentCoroutineContext().ensureActive()
    return EngineEffectiveSettings(values = values, merged = merged)
}

/** The engine's view of a settings key, with the resolution only while the key is written verbatim. */
private fun piSettingsLayer(
    field: ConfigField,
    binding: ConfigBinding.JsonPath,
    request: EffectiveSettingsRequest,
    settings: JsonObject?,
): PiSurfaceLayer? {
    if (binding.document != PiDocuments.Settings) return null
    if (binding.path.first() in PiOwnedSettingsKeys) return null
    val profile = request.profile.read(binding) ?: return null
    val written = settings?.at(binding.path) ?: return null
    val observed = piObserved(field, written)
    val resolution = if (written == profile && request.project?.read(binding) == null) {
        EngineDeclaredResolution(observed, PiVerbatimLayers)
    } else {
        null
    }
    return PiSurfaceLayer(observed, resolution)
}

/** The engine's view of an instruction document; a project may add or replace instructions, so nothing is merged. */
private fun piDocumentLayer(
    field: ConfigField,
    binding: ConfigBinding.WholeDocument,
    files: Map<String, String>,
): PiSurfaceLayer? {
    val name = PiAgentFiles[binding.document] ?: return null
    val text = files[name] ?: return null
    return PiSurfaceLayer(piObserved(field, JsonPrimitive(text)))
}

/** The settings object Heartbeat wrote; only its own encoding reaches this parse. */
private fun piSettingsOf(text: String): JsonObject? = Json.parseToJsonElement(text) as? JsonObject

/** A typed engine observation and, only where Heartbeat can prove it, the value Pi applies after the layers. */
private data class PiSurfaceLayer(val value: ObservedValue, val resolution: EngineDeclaredResolution? = null)

/** The documented surfaces the facade reports, in page order. */
private val PiEffectiveSurfaces: List<ConfigField> by lazy {
    PiConfigSchema.pages.asSequence()
        .flatMap { page -> page.sections.asSequence().flatMap { it.fields.asSequence() } }
        .filter { it.kind !is ConfigFieldKind.ManagedNote }
        .distinctBy { it.id }
        .toList()
}

/** Top-level settings keys the composition removes: Pi never reads them from the agent directory. */
private val PiOwnedSettingsKeys: Set<String> = PiConfigSchema.ownedKeys.map { it.path.first() }.toSet()

/** Instruction documents Heartbeat writes into the agent directory by document id. */
private val PiAgentFiles = mapOf(
    PiDocuments.Agents to AGENTS_FILE,
    PiDocuments.System to SYSTEM_FILE,
    PiDocuments.AppendSystem to APPEND_SYSTEM_FILE,
)

/** Layers of a value Heartbeat writes verbatim: the saved profile value Pi reads back from its own settings file. */
private val PiVerbatimLayers = setOf(SettingsLayer.Heartbeat, SettingsLayer.Engine)

/** An observed JSON value with the surface's own secret type and every nested secret entry masked. */
private fun piObserved(field: ConfigField, value: JsonElement): ObservedValue {
    val path = (field.binding as? ConfigBinding.JsonPath)?.path.orEmpty()
    val primitive = value as? JsonPrimitive
    if (primitive != null && isSecretText(path, primitive)) return ObservedValue.Secret
    return ObservedValue.Literal(piMasked(value))
}

/** Whether a string at [path] is a vault reference or a plain value of a secret-named setting. */
private fun isSecretText(path: List<String>, primitive: JsonPrimitive): Boolean {
    if (!primitive.isString) return false
    return (path.isNotEmpty() && isSecretKey(path.last())) || secretSlotOf(primitive.content) != null
}

/** Masks every vault reference and every entry of a secret-named object key; contents are never kept. */
private fun piMasked(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            if (isSecretKey(key)) JsonPrimitive(MASKED_SETTING_VALUE) else piMasked(value)
        },
    )

    is JsonArray -> JsonArray(element.map(::piMasked))

    is JsonPrimitive -> if (element.isString && secretSlotOf(element.content) != null) {
        JsonPrimitive(MASKED_SETTING_VALUE)
    } else {
        element
    }
}
