package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningLimits
import io.aequicor.heartbeat.feature.agentlearning.api.ModelScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
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

/**
 * Learned instructions in a configuration tree: `learning/<name>.json` holds the kind, title, description and model
 * scope, `learning/<name>.md` the body. The project is not written: a unit applies in the project whose folder holds
 * it, or where an imported bundle is placed.
 */
internal object LearningConfigCodec {
    val section: ConfigSectionId = ConfigSectionId.Learning

    private const val METADATA = "json"
    private const val BODY = "md"
    private const val NAME_CHARS = 48
    private const val FALLBACK_NAME = "instruction"
    private val log = Log.tag("LearningConfigCodec")
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /** Unit of a file directly inside the section directory with a known extension, or null. */
    fun unitOf(path: ConfigPath): String? {
        val segments = path.segments
        if (segments.size != 2 || segments[0] != section.value) return null
        val extension = segments[1].substringAfterLast('.', "")
        val name = segments[1].removeSuffix(".$extension")
        return name.takeIf { extension == METADATA || extension == BODY }?.takeIf { it.isNotEmpty() && '.' !in it }
    }

    /** Portable files of [instruction] under unit [name]. */
    fun encode(instruction: LearnedInstruction, name: String): ConfigUnit {
        val metadata = LearningMetadata(
            kind = instruction.kind,
            title = instruction.title,
            description = instruction.description.takeIf { it.isNotEmpty() },
            engine = instruction.modelScope?.engine?.value,
            model = instruction.modelScope?.model?.value,
        )
        return ConfigUnit.of(
            ConfigUnitRef(section, name),
            listOf(
                ConfigFile(path(name, METADATA), json.encodeToString(LearningMetadata.serializer(), metadata) + "\n"),
                ConfigFile(path(name, BODY), instruction.content.trim() + "\n"),
            ),
        )
    }

    /** What [unit] describes, or the problems that keep it from being applied. */
    fun decode(unit: ConfigUnit): LearningDecoded {
        val name = unit.ref.name
        val metadataFile = unit.files.firstOrNull { it.path == path(name, METADATA) }
        val bodyFile = unit.files.firstOrNull { it.path == path(name, BODY) }
        val problems = buildList {
            if (metadataFile == null) add(ConfigProblem(ConfigProblemCode.Missing, path(name, METADATA)))
            if (bodyFile == null) add(ConfigProblem(ConfigProblemCode.Missing, path(name, BODY)))
            unit.files.filter { it != metadataFile && it != bodyFile }
                .forEach { add(ConfigProblem(ConfigProblemCode.Unsupported, it.path)) }
        }
        if (metadataFile == null || bodyFile == null || problems.isNotEmpty()) return LearningDecoded.Invalid(problems)
        val metadata = metadata(metadataFile) ?: return invalid(ConfigProblemCode.Syntax, metadataFile.path)
        return lesson(metadata, bodyFile.text.trim(), metadataFile.path, bodyFile.path)
    }

    /** Identity the registry keeps unique within a project ([LearnedInstruction.isSameLesson] without the project). */
    fun identity(instruction: LearningLesson): String = listOf(
        instruction.kind.name,
        instruction.scope?.engine?.value.orEmpty(),
        instruction.scope?.model?.value.orEmpty(),
        instruction.title.trim().lowercase(),
    ).joinToString("|")

    /** Identity of a stored [instruction]; equal to the identity of a unit it was encoded to. */
    fun identity(instruction: LearnedInstruction): String = identity(instruction.lesson())

    /** Portable part of [instruction]. */
    fun LearnedInstruction.lesson(): LearningLesson =
        LearningLesson(kind, title, description, content.trim(), modelScope)

    /** Preview of [lesson]. */
    fun summary(lesson: LearningLesson, name: String): ConfigSummary {
        val kind = if (lesson.kind == InstructionKind.Skill) {
            ConfigContentKind.LearnedSkill
        } else {
            ConfigContentKind.LearnedInstruction
        }
        return ConfigSummary(lesson.title, lesson.description, listOf(ConfigContent(kind, name)))
    }

    /**
     * Default unit names of [instructions]: the title transliterated to Latin letters, digits and `-`, deduplicated
     * by creation order with `-2`, `-3`…
     */
    fun names(instructions: List<LearnedInstruction>): Map<LearnedInstruction, String> {
        val taken = mutableSetOf<String>()
        val ordered = instructions.sortedWith(compareBy({ it.createdAtMillis }, { it.id.value }))
        return ordered.associateWith { instruction ->
            val base = slug(instruction.title)
            (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first(taken::add)
        }
    }

    private fun lesson(
        metadata: LearningMetadata,
        content: String,
        metadataPath: ConfigPath,
        bodyPath: ConfigPath,
    ): LearningDecoded {
        problem(metadata, content, metadataPath, bodyPath)?.let { return LearningDecoded.Invalid(listOf(it)) }
        return LearningDecoded.Valid(
            LearningLesson(
                kind = metadata.kind,
                title = metadata.title.trim(),
                description = metadata.description.orEmpty().trim(),
                content = content,
                scope = metadata.scope(),
            ),
        )
    }

    private fun problem(
        metadata: LearningMetadata,
        content: String,
        metadataPath: ConfigPath,
        bodyPath: ConfigPath,
    ): ConfigProblem? {
        val scope = scopeProblem(metadata)
        return when {
            metadata.title.isBlank() || content.isEmpty() -> ConfigProblem(ConfigProblemCode.Syntax, metadataPath)

            metadata.kind == InstructionKind.Skill && metadata.description.isNullOrBlank() ->
                ConfigProblem(ConfigProblemCode.Syntax, metadataPath, "a skill needs a description")

            scope != null -> ConfigProblem(ConfigProblemCode.Syntax, metadataPath, scope)

            metadata.title.trim().length > LearningLimits.TITLE ||
                metadata.description.orEmpty().trim().length > LearningLimits.DESCRIPTION ->
                ConfigProblem(ConfigProblemCode.Limit, metadataPath)

            content.length > LearningLimits.content(metadata.kind) -> ConfigProblem(ConfigProblemCode.Limit, bodyPath)

            else -> null
        }
    }

    private fun LearningMetadata.scope(): ModelScope? {
        val model = model?.let(::ModelId)
        return engine?.let { ModelScope(EngineId(it), model) }
    }

    private fun scopeProblem(metadata: LearningMetadata): String? = when {
        (metadata.kind == InstructionKind.Model) != (metadata.engine != null) ->
            "engine is required for model instructions only"

        metadata.engine == null && metadata.model != null -> "model needs an engine"

        metadata.engine?.isBlank() == true || metadata.model?.isBlank() == true -> "engine and model must not be empty"

        else -> null
    }

    private fun metadata(file: ConfigFile): LearningMetadata? = try {
        json.decodeFromString(LearningMetadata.serializer(), file.text)
    } catch (e: SerializationException) {
        log.w(e) { "Learned instruction metadata does not match the format" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "Learned instruction metadata has invalid values" }
        null
    }

    private fun invalid(code: ConfigProblemCode, path: ConfigPath) = LearningDecoded.Invalid(
        listOf(ConfigProblem(code, path)),
    )

    private fun path(name: String, extension: String) = ConfigPath.of(section.value, "$name.$extension")

    private fun slug(title: String): String {
        val latin = title.lowercase().map { TRANSLIT[it] ?: it.toString() }.joinToString("")
        val slug = latin.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(NAME_CHARS).trimEnd('-')
        return slug.ifEmpty { FALLBACK_NAME }
    }

    private val TRANSLIT: Map<Char, String> = (
        "а:a б:b в:v г:g д:d е:e ё:e ж:zh з:z и:i й:y к:k л:l м:m н:n о:o п:p р:r с:s т:t у:u ф:f х:kh ц:ts " +
            "ч:ch ш:sh щ:sch ъ: ы:y ь: э:e ю:yu я:ya"
    ).split(' ').associate { it[0] to it.substringAfter(':') }
}

/** A learned instruction parsed from a unit, without identity, project and switch. */
internal data class LearningLesson(
    val kind: InstructionKind,
    val title: String,
    val description: String,
    val content: String,
    val scope: ModelScope?,
) {
    override fun toString(): String = "LearningLesson(kind=$kind)"
}

/** Outcome of [LearningConfigCodec.decode]. */
internal sealed interface LearningDecoded {
    /** The unit describes [lesson]. */
    data class Valid(val lesson: LearningLesson) : LearningDecoded

    /** The unit cannot be applied. */
    data class Invalid(val problems: List<ConfigProblem>) : LearningDecoded
}

/** `learning/<name>.json`. */
@Serializable
private data class LearningMetadata(
    @SerialName("kind") val kind: InstructionKind,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("engine") val engine: String? = null,
    @SerialName("model") val model: String? = null,
)
