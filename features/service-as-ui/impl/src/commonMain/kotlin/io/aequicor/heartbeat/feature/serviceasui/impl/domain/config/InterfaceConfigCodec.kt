package io.aequicor.heartbeat.feature.serviceasui.impl.domain.config

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecCheck
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecRules
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.checkUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.formatUiSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Interfaces in a configuration tree: `interfaces/<harness>/<surface>.json` holds the placement, title, description
 * and launchers, `interfaces/<harness>/<surface>.spec.json` the spec formatted for people. A unit belongs to the
 * harness its folder names; the owner identity, revision, author and switch stay in the profile.
 */
internal object InterfaceConfigCodec {
    val section: ConfigSectionId = ConfigSectionId.Interfaces

    private const val METADATA = ".json"
    private const val SPEC = ".spec.json"
    private const val UNIT_SEGMENTS = 3
    private val SLUG = Regex("[a-z][a-z0-9_]{0,31}")
    private val log = Log.tag("InterfaceConfigCodec")
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /** Unit `<harness>/<surface>` of a metadata or spec file, or null. */
    fun unitOf(path: ConfigPath): String? {
        val segments = path.segments.takeIf { it.size == UNIT_SEGMENTS && it[0] == section.value } ?: return null
        val file = segments[2]
        val surface = when {
            file.endsWith(SPEC) -> file.removeSuffix(SPEC)
            file.endsWith(METADATA) -> file.removeSuffix(METADATA)
            else -> return null
        }
        return "${segments[1]}/$surface".takeIf { '.' !in surface && surface.isNotEmpty() }
    }

    /** Harness unit of interface unit [name]. */
    fun parentOf(name: String): String = name.substringBefore('/')

    /** Portable files of [content] under the folder of harness [harness]. */
    fun encode(content: UiSurfaceContent, harness: String): ConfigUnit {
        val name = "$harness/${content.name.value}"
        val metadata = InterfaceFile(
            placement = content.placement,
            title = content.title,
            description = content.description.takeIf { it.isNotEmpty() },
            launchers = content.launchers.sortedBy { it.ordinal }.ifEmpty { null },
        )
        val spec = formatUiSpec(content.spec, isIndented = true) ?: content.spec
        return ConfigUnit.of(
            ConfigUnitRef(section, name),
            listOf(
                ConfigFile(path(name, METADATA), json.encodeToString(InterfaceFile.serializer(), metadata) + "\n"),
                ConfigFile(path(name, SPEC), spec.trimEnd('\n') + "\n"),
            ),
        )
    }

    /** What [unit] describes, or the problems that keep it from being applied; the spec is checked as a library one. */
    fun decode(unit: ConfigUnit): InterfaceDecoded {
        val name = unit.ref.name
        val surface = name.substringAfter('/', "")
        if (!SLUG.matches(parentOf(name)) || !SLUG.matches(surface)) {
            return invalid(ConfigProblem(ConfigProblemCode.Name, path(name, METADATA)))
        }
        val metadataFile = unit.files.firstOrNull { it.path == path(name, METADATA) }
        val specFile = unit.files.firstOrNull { it.path == path(name, SPEC) }
        val problems = listOfNotNull(
            ConfigProblem(ConfigProblemCode.Missing, path(name, METADATA)).takeIf { metadataFile == null },
            ConfigProblem(ConfigProblemCode.Missing, path(name, SPEC)).takeIf { specFile == null },
        ) + unit.files.filter { it != metadataFile && it != specFile }
            .map { ConfigProblem(ConfigProblemCode.Unsupported, it.path) }
        if (metadataFile == null || specFile == null || problems.isNotEmpty()) return InterfaceDecoded.Invalid(problems)
        val metadata = metadata(metadataFile)
            ?: return invalid(ConfigProblem(ConfigProblemCode.Syntax, metadataFile.path))
        metaProblem(metadata, metadataFile.path)?.let { return invalid(it) }
        return spec(metadata, specFile)?.let { InterfaceDecoded.Invalid(listOf(it)) }
            ?: InterfaceDecoded.Valid(
                InterfaceContent(
                    harness = parentOf(name),
                    surface = surface,
                    placement = metadata.placement,
                    title = metadata.title.trim(),
                    description = metadata.description.orEmpty().trim(),
                    launchers = metadata.launchers.orEmpty().toSet(),
                    spec = checkNotNull(formatUiSpec(specFile.text, isIndented = false)),
                ),
            )
    }

    /** Preview of [content]. */
    fun summary(content: InterfaceContent): ConfigSummary = ConfigSummary(
        content.title,
        content.description,
        listOf(ConfigContent(ConfigContentKind.Interface, content.surface)),
    )

    private fun metaProblem(metadata: InterfaceFile, path: ConfigPath): ConfigProblem? = when {
        metadata.title.isBlank() -> ConfigProblem(ConfigProblemCode.Syntax, path, "title is required")

        metadata.title.trim().length > ServiceUiLimits.TITLE_CHARS ||
            metadata.description.orEmpty().trim().length > ServiceUiLimits.DESCRIPTION_CHARS ->
            ConfigProblem(ConfigProblemCode.Limit, path)

        metadata.launchers.orEmpty().isNotEmpty() &&
            metadata.placement != UiPlacement.Panel && metadata.placement != UiPlacement.Modal ->
            ConfigProblem(ConfigProblemCode.Syntax, path, "only panel and modal interfaces have launchers")

        else -> null
    }

    /** Problem of the spec file, or null when it is a valid library spec for the placement of [metadata]. */
    private fun spec(metadata: InterfaceFile, file: ConfigFile): ConfigProblem? {
        if (file.text.encodeToByteArray().size > ServiceUiLimits.SPEC_BYTES) {
            return ConfigProblem(ConfigProblemCode.Limit, file.path)
        }
        val compact = formatUiSpec(file.text, isIndented = false)
            ?: return ConfigProblem(ConfigProblemCode.Syntax, file.path, "not JSON or nested too deeply")
        val check = checkUiSpec(compact, UiSpecRules(UiSpecOrigin.Library, metadata.placement))
        return (check as? UiSpecCheck.Invalid)?.diagnostics?.firstOrNull { !it.isWarning }
            ?.let { ConfigProblem(ConfigProblemCode.Syntax, file.path, "${it.path}: ${it.problem}") }
    }

    private fun metadata(file: ConfigFile): InterfaceFile? = try {
        json.decodeFromString(InterfaceFile.serializer(), file.text)
    } catch (e: SerializationException) {
        log.w(e.redactedFailure()) { "Interface metadata does not match the format" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e.redactedFailure()) { "Interface metadata has invalid values" }
        null
    }

    private fun invalid(problem: ConfigProblem) = InterfaceDecoded.Invalid(listOf(problem))

    private fun path(name: String, suffix: String): ConfigPath =
        ConfigPath.of(section.value, parentOf(name), name.substringAfter('/') + suffix)
}

/** Portable content of an interface parsed from a unit; [spec] is compact JSON. */
internal data class InterfaceContent(
    val harness: String,
    val surface: String,
    val placement: UiPlacement,
    val title: String,
    val description: String,
    val launchers: Set<UiSlot>,
    val spec: String,
) {
    override fun toString(): String = "InterfaceContent($harness/$surface, placement=$placement)"
}

/** Outcome of [InterfaceConfigCodec.decode]. */
internal sealed interface InterfaceDecoded {
    /** The unit describes [content]. */
    data class Valid(val content: InterfaceContent) : InterfaceDecoded

    /** The unit cannot be applied. */
    data class Invalid(val problems: List<ConfigProblem>) : InterfaceDecoded
}

/** `interfaces/<harness>/<surface>.json`. */
@Serializable
private data class InterfaceFile(
    @SerialName("placement") val placement: UiPlacement,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("launchers") val launchers: List<UiSlot>? = null,
)
