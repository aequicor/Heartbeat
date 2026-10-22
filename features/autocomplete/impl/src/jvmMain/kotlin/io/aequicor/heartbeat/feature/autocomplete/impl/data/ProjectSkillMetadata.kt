package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

private val log = Log.tag("Autocomplete/SkillMetadata")
private const val METADATA_BYTES = 16_384
private const val BODY_BYTES = 65_536
private val SKILL_PATH = Regex("(?:\\.agents|\\.claude|\\.pi)/skills/(?:[^/]+/)+SKILL\\.md")
private val SKILL_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")

/** Reads a bounded literal header, without loading the instruction body or evaluating YAML. */
internal fun readProjectSkill(file: Path, relative: String, size: Long): ProjectSkill? {
    if (!SKILL_PATH.matches(relative) || size == 0L) return null
    return try {
        Files.newInputStream(file, NOFOLLOW_LINKS).use { stream ->
            projectSkillMetadata(stream.readNBytes(METADATA_BYTES).toString(Charsets.UTF_8))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "project skill metadata unavailable" }
        null
    }
}

/**
 * Reads a whole skill file of [root] for delivery with a prompt. Every path segment must be a plain name under the
 * real root and no segment may be a link; oversized files and files declaring a different [name] are refused.
 */
internal fun readProjectSkillBody(root: Path, relative: String, name: String): String? {
    val segments = relative.split('/')
    val isPlain = segments.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it }
    if (!SKILL_PATH.matches(relative) || !isPlain) return null
    return try {
        val file = segments.runningFold(root.toRealPath()) { parent, segment -> parent.resolve(segment) }
            .drop(1)
            .takeIf { path -> path.none(Files::isSymbolicLink) }
            ?.last()
            ?.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        val bytes = file?.let { path ->
            Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(BODY_BYTES + 1) }
        }
        if (bytes != null && bytes.size > BODY_BYTES) log.w { "project skill exceeds the delivered size limit" }
        bytes?.takeIf { it.size <= BODY_BYTES }
            ?.toString(Charsets.UTF_8)
            ?.takeIf { projectSkillMetadata(it)?.name == name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "project skill body unavailable" }
        null
    }
}

/** Supports literal quoted scalars and folded/literal descriptions; incomplete headers are not skills. */
internal fun projectSkillMetadata(text: String): ProjectSkill? {
    val lines = text.removePrefix("\uFEFF").lines()
    if (lines.firstOrNull()?.trim() != "---") return null
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
    val fields = literalFields(lines.subList(1, end))
    val name = fields["name"] ?: return null
    val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
    if (!SKILL_NAME.matches(name) || fields["user-invocable"] == "false") return null
    return ProjectSkill(name, description)
}

private fun literalFields(lines: List<String>): Map<String, String> {
    val fields = mutableMapOf<String, String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index++]
        val key = line.substringBefore(':')
        if (line.startsWith(' ') || line.startsWith('\t') ||
            key !in setOf("name", "description", "user-invocable")
        ) {
            continue
        }
        val value = line.substringAfter(':', "").trim()
        fields[key] = if (value in setOf("|", "|-", "|+", ">", ">-", ">+")) {
            val block = lines.drop(index).takeWhile { it.isBlank() || it.startsWith(' ') }
            index += block.size
            block.map(String::trim).joinToString(if (value.startsWith('>')) " " else "\n").trim()
        } else {
            value.removeSurrounding("\"").removeSurrounding("'")
                .replace("\\\"", "\"").replace("''", "'")
        }
    }
    return fields
}
