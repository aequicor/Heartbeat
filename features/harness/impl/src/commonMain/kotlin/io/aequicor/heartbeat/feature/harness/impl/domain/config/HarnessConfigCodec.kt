package io.aequicor.heartbeat.feature.harness.impl.domain.config

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.api.isHarnessInputSchema
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.DESCRIPTION_CHARS
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.TITLE_CHARS
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigContent
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigContentKind
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSummary
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Harnesses in a configuration tree: `harnesses/<name>/harness.json` holds the title, description, tool policy and
 * the ordered item list; each item's text or code is `harnesses/<name>/<item>.md` (skills, instructions, templates)
 * or `<item>.kts` (scripts, workflows). The folder name is the immutable harness name. Ids, timestamps, the author,
 * the legacy scope and the harness switch stay in the profile.
 */
internal object HarnessConfigCodec {
    val section: ConfigSectionId = ConfigSectionId.Harnesses

    private const val METADATA = "harness.json"
    private const val TEXT = "md"
    private const val CODE = "kts"
    private const val UNIT_SEGMENTS = 3
    private val NAME = Regex("[a-z][a-z0-9_]{1,19}")
    private val ITEM = Regex("[a-z][a-z0-9_]{0,31}")
    private val log = Log.tag("HarnessConfigCodec")
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /** Harness of a file directly inside its folder, or null. */
    fun unitOf(path: ConfigPath): String? =
        path.segments.takeIf { it.size == UNIT_SEGMENTS && it[0] == section.value }?.get(1)

    /** Portable files of [harness]. */
    fun encode(harness: Harness): ConfigUnit {
        val name = harness.name.value
        val metadata = HarnessFile(
            title = harness.title,
            description = harness.description.takeIf { it.isNotEmpty() },
            tools = harness.tools.takeIf { it != ToolPolicySpec() },
            items = harness.items.map { it.file() },
        )
        val metadataText = json.encodeToString(HarnessFile.serializer(), metadata) + "\n"
        val bodies = harness.items.map { item ->
            ConfigFile(path(name, "${item.name.value}.${item.file().kind.extension}"), item.content + "\n")
        }
        val files = listOf(ConfigFile(path(name, METADATA), metadataText)) + bodies
        return ConfigUnit.of(ConfigUnitRef(section, name), files)
    }

    /** What [unit] describes, or the problems that keep it from being applied. Item ids are placeholders. */
    fun decode(unit: ConfigUnit): HarnessDecoded {
        val name = unit.ref.name
        if (!NAME.matches(name)) return invalid(ConfigProblemCode.Name, path(name, METADATA))
        val metadataFile = unit.files.firstOrNull { it.path == path(name, METADATA) }
            ?: return invalid(ConfigProblemCode.Missing, path(name, METADATA))
        val metadata = metadata(metadataFile) ?: return invalid(ConfigProblemCode.Syntax, metadataFile.path)
        val problems = listOfNotNull(metaProblem(metadata, metadataFile.path)).toMutableList()
        val used = mutableSetOf(metadataFile.path)
        val items = metadata.items.filter { ITEM.matches(it.name) }.mapNotNull { item ->
            val file = path(name, "${item.name}.${item.kind.extension}")
            val body = unit.files.firstOrNull { it.path == file }?.also { used += it.path }?.text?.removeSuffix("\n")
            val problem = itemProblem(item, body, file)
            problem?.let(problems::add)
            body?.takeIf { problem == null }?.let { item.toItem(it) }
        }
        problems += unit.files.filter { it.path !in used }.map { ConfigProblem(ConfigProblemCode.Unsupported, it.path) }
        if (problems.isNotEmpty()) return HarnessDecoded.Invalid(problems)
        val content = HarnessContent(
            name = HarnessName(name),
            title = metadata.title.trim(),
            description = metadata.description.orEmpty().trim(),
            items = items,
            tools = metadata.tools ?: ToolPolicySpec(),
        )
        return HarnessDecoded.Valid(content)
    }

    /** Preview of [content]: its items by kind and whether it changes tool switches. */
    fun summary(content: HarnessContent): ConfigSummary {
        val tools = ConfigContent(ConfigContentKind.ToolPolicy, METADATA).takeIf { content.tools != ToolPolicySpec() }
        val items = content.items.map { ConfigContent(it.kind, it.name.value) }
        return ConfigSummary(content.title, content.description, items + listOfNotNull(tools))
    }

    private fun metaProblem(metadata: HarnessFile, path: ConfigPath): ConfigProblem? {
        val isTooLong = metadata.title.trim().length > TITLE_CHARS ||
            metadata.description.orEmpty().trim().length > DESCRIPTION_CHARS
        return when {
            metadata.title.isBlank() -> ConfigProblem(ConfigProblemCode.Syntax, path, "title is required")

            isTooLong -> ConfigProblem(ConfigProblemCode.Limit, path)

            metadata.items.size > HarnessLimits.ITEMS -> ConfigProblem(ConfigProblemCode.Limit, path, "too many items")

            metadata.items.map { it.name }.distinct().size != metadata.items.size ->
                ConfigProblem(ConfigProblemCode.Name, path, "item names must be unique")

            metadata.items.any { !ITEM.matches(it.name) } ->
                ConfigProblem(ConfigProblemCode.Name, path, "invalid item name")

            else -> null
        }
    }

    private fun itemProblem(file: ItemFile, body: String?, path: ConfigPath): ConfigProblem? = when {
        body == null -> ConfigProblem(ConfigProblemCode.Missing, path)

        body.length > file.kind.limit || file.description.orEmpty().length > DESCRIPTION_CHARS ->
            ConfigProblem(ConfigProblemCode.Limit, path)

        file.arguments.orEmpty().any { !ITEM.matches(it) } ->
            ConfigProblem(ConfigProblemCode.Syntax, path, "invalid template argument")

        file.input?.let(::isHarnessInputSchema) == false ->
            ConfigProblem(ConfigProblemCode.Syntax, path, "unsupported workflow input schema")

        else -> null
    }

    private fun ItemFile.toItem(body: String): HarnessItem {
        val id = ItemId("import:$name")
        val item = ItemName(name)
        val text = description.orEmpty()
        return when (kind) {
            ItemKind.Skill -> HarnessItem.Skill(id, item, text, body, isEnabled)
            ItemKind.Instruction -> HarnessItem.Instruction(id, item, body, isEnabled)
            ItemKind.Template -> HarnessItem.Template(id, item, text, body, arguments.orEmpty().toSet(), isEnabled)
            ItemKind.Script -> HarnessItem.Script(id, item, text, body, isEnabled)
            ItemKind.Workflow -> HarnessItem.Workflow(id, item, text, body, input ?: JsonObject(emptyMap()), isEnabled)
        }
    }

    private fun metadata(file: ConfigFile): HarnessFile? = try {
        json.decodeFromString(HarnessFile.serializer(), file.text)
    } catch (e: SerializationException) {
        log.w(e) { "Harness metadata does not match the format" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "Harness metadata has invalid values" }
        null
    }

    private fun invalid(code: ConfigProblemCode, path: ConfigPath) =
        HarnessDecoded.Invalid(listOf(ConfigProblem(code, path)))

    private fun path(name: String, file: String) = ConfigPath.of(section.value, name, file)

    private fun HarnessItem.file(): ItemFile {
        val text = when (this) {
            is HarnessItem.Instruction -> null
            is HarnessItem.Skill -> description
            is HarnessItem.Template -> description
            is HarnessItem.Script -> description
            is HarnessItem.Workflow -> description
        }?.takeIf { it.isNotEmpty() }
        val kind = when (this) {
            is HarnessItem.Skill -> ItemKind.Skill
            is HarnessItem.Instruction -> ItemKind.Instruction
            is HarnessItem.Template -> ItemKind.Template
            is HarnessItem.Script -> ItemKind.Script
            is HarnessItem.Workflow -> ItemKind.Workflow
        }
        val arguments = (this as? HarnessItem.Template)?.arguments?.sorted()?.ifEmpty { null }
        val input = (this as? HarnessItem.Workflow)?.input?.takeIf { it.isNotEmpty() }
        return ItemFile(name.value, kind, isEnabled, text, arguments, input)
    }

    private val HarnessItem.content: String
        get() = when (this) {
            is HarnessItem.Skill -> body
            is HarnessItem.Instruction -> text
            is HarnessItem.Template -> body
            is HarnessItem.Script -> source
            is HarnessItem.Workflow -> source
        }

    private val ItemKind.extension: String get() = if (isCode) CODE else TEXT
}

/** Portable content of a harness parsed from a unit; item ids are placeholders until applied. */
internal data class HarnessContent(
    val name: HarnessName,
    val title: String,
    val description: String,
    val items: List<HarnessItem>,
    val tools: ToolPolicySpec,
) {
    override fun toString(): String = "HarnessContent(name=$name, items=${items.size})"
}

/** Outcome of [HarnessConfigCodec.decode]. */
internal sealed interface HarnessDecoded {
    /** The unit describes [content]. */
    data class Valid(val content: HarnessContent) : HarnessDecoded

    /** The unit cannot be applied. */
    data class Invalid(val problems: List<ConfigProblem>) : HarnessDecoded
}

/** Kind of a [HarnessItem] as previews show it. */
internal val HarnessItem.kind: ConfigContentKind
    get() = when (this) {
        is HarnessItem.Skill -> ConfigContentKind.Skill
        is HarnessItem.Instruction -> ConfigContentKind.Instruction
        is HarnessItem.Template -> ConfigContentKind.Template
        is HarnessItem.Script -> ConfigContentKind.Script
        is HarnessItem.Workflow -> ConfigContentKind.Workflow
    }

/** `harnesses/<name>/harness.json`. */
@Serializable
private data class HarnessFile(
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("tools") val tools: ToolPolicySpec? = null,
    @SerialName("items") val items: List<ItemFile> = emptyList(),
)

/** One entry of [HarnessFile.items]; its text or code is the file named after it. */
@Serializable
private data class ItemFile(
    @SerialName("name") val name: String,
    @SerialName("kind") val kind: ItemKind,
    @SerialName("enabled") val isEnabled: Boolean = true,
    @SerialName("description") val description: String? = null,
    @SerialName("arguments") val arguments: List<String>? = null,
    @SerialName("input") val input: JsonObject? = null,
)

@Serializable
private enum class ItemKind(val limit: Int, val isCode: Boolean = false) {
    @SerialName("skill")
    Skill(HarnessLimits.SKILL_CHARS),

    @SerialName("instruction")
    Instruction(HarnessLimits.INSTRUCTION_CHARS),

    @SerialName("template")
    Template(HarnessLimits.TEMPLATE_CHARS),

    @SerialName("script")
    Script(HarnessLimits.SOURCE_CHARS, isCode = true),

    @SerialName("workflow")
    Workflow(HarnessLimits.SOURCE_CHARS, isCode = true),
}
