package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

private val log = Log.tag("Pi/ComposerAssists")

/** Directories Pi reads project skills from, as documented by the process launcher. */
private val SKILL_DIRECTORIES = listOf(".agents/skills", ".pi/skills")

private const val SKILL_FILE = "SKILL.md"

/** Reads at most this many bytes of a skill file; the frontmatter lives at the very top. */
private const val FRONTMATTER_BYTES = 8_192

/** Maximum candidate entries inspected across the project skill directories, including non-skills. */
private const val SKILL_LIMIT = 200

/**
 * Native composer assists of the Pi engine: the skills of the session's project, read from the same
 * directories the Pi process itself loads (`.agents/skills`, `.pi/skills`). A session without a project runs
 * fully hermetic (`--no-skills`), so it offers nothing. Reading never starts a process; an unreadable
 * directory simply contributes no skills.
 */
internal class PiComposerAssists(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) : ListsComposerAssists {
    override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> {
        val root = workspace?.let { workspaces.resolve(it) } ?: return emptyList()
        return withContext(dispatchers.io) { scan(Path.of(root), currentCoroutineContext()) }
    }

    private fun scan(root: Path, context: CoroutineContext): List<EngineAssist> {
        val seen = mutableSetOf<String>()
        val assists = mutableListOf<EngineAssist>()
        var remaining = SKILL_LIMIT
        for (directory in SKILL_DIRECTORIES) {
            val entries = piSkillEntries(root.resolve(directory), remaining, context)
            remaining -= entries.size
            for (entry in entries) {
                context.ensureActive()
                skillAssist(entry, context)?.takeIf { seen.add(it.id) }?.let(assists::add)
            }
            if (remaining == 0) break
        }
        return assists
    }

    private fun skillAssist(entry: Path, context: CoroutineContext): EngineAssist? {
        val file = entry.resolve(SKILL_FILE)
        if (!Files.isRegularFile(file)) return null
        val frontmatter = readFrontmatter(file, context) ?: return null
        val id = entry.fileName.toString()
        val name = frontmatter.name ?: id
        return EngineAssist(
            id = "skill:$id",
            label = name,
            description = frontmatter.description,
            insert = "$name ",
            kind = EngineAssist.Kind.Skill,
        )
    }

    private fun readFrontmatter(file: Path, context: CoroutineContext): SkillFrontmatter? = try {
        val bytes = ByteArray(FRONTMATTER_BYTES.toLong().coerceAtMost(Files.size(file)).toInt())
        Files.newInputStream(file).use { input ->
            var read = 0
            while (read < bytes.size) {
                context.ensureActive()
                val portion = input.read(bytes, read, bytes.size - read)
                if (portion < 0) break
                read += portion
            }
        }
        SkillFrontmatter.of(String(bytes, Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "skill file not readable" }
        null
    }

    private data class SkillFrontmatter(val name: String?, val description: String) {
        companion object {
            /** The leading `---` block of a skill file; missing keys stay null or empty. */
            fun of(text: String): SkillFrontmatter {
                val lines = text.lineSequence()
                val iterator = lines.iterator()
                if (!iterator.hasNext() || iterator.next().trim() != "---") {
                    return SkillFrontmatter(null, "")
                }
                var name: String? = null
                var description = ""
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    val trimmed = line.trim()
                    if (trimmed == "---") break
                    when {
                        trimmed.startsWith(
                            "name:",
                        ) -> name = trimmed.removePrefix("name:").trim().takeIf(String::isNotEmpty)

                        trimmed.startsWith("description:") ->
                            description = trimmed.removePrefix("description:").trim()
                    }
                }
                return SkillFrontmatter(name, description)
            }
        }
    }
}

/** Reads a bounded candidate prefix before sorting or opening skill bodies, on the caller's IO dispatcher. */
internal fun piSkillEntries(directory: Path, limit: Int, context: CoroutineContext): List<Path> {
    context.ensureActive()
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.newDirectoryStream(directory).use { stream ->
        val entries = mutableListOf<Path>()
        val iterator = stream.iterator()
        while (entries.size < limit && iterator.hasNext()) {
            context.ensureActive()
            entries.add(iterator.next())
        }
        entries.sorted()
    }
}
