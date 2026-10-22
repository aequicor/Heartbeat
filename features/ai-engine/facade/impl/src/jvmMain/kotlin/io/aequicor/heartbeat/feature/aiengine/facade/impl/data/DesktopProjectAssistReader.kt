package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AssistDirectory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectAssistReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** File-only native metadata discovery. All limits are shared across roots; existing symbolic links are skipped. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopProjectAssistReader(
    private val workspaces: LocalWorkspaces,
    private val launch: EngineLaunchConfig,
    private val dispatchers: DispatcherProvider,
) : ProjectAssistReader {
    private val log = Log.tag("Composer/ProjectMetadata")

    override suspend fun read(
        engine: EngineId,
        workspace: WorkspaceRef?,
        directories: List<AssistDirectory>,
    ): List<EngineAssist> {
        val project = workspace ?: return emptyList()
        val before = launch.context(engine)
        if (before.trust[project] != ProjectTrust.Trusted) return emptyList()
        val directory = workspaces.resolve(project) ?: return emptyList()
        val items = try {
            withTimeoutOrNull(2.seconds) {
                val context = currentCoroutineContext()
                runInterruptible(dispatchers.io) { scan(Path.of(directory), directories, context) }
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "project assist metadata unavailable" }
            emptyList()
        }
        return items.takeIf { launch.context(engine) == before }.orEmpty()
    }

    private fun scan(root: Path, directories: List<AssistDirectory>, context: CoroutineContext): List<EngineAssist> {
        val canonical = root.toRealPath()
        val budget = ScanBudget(ENTRY_LIMIT)
        val result = directories.flatMap { scanDirectory(canonical, it, context, budget) }
        if (budget.remaining == 0) log.w { "project assist metadata reached its entry limit" }
        return result.distinctBy { it.kind to it.label }.sortedBy { it.label }
    }

    private fun scanDirectory(
        root: Path,
        spec: AssistDirectory,
        context: CoroutineContext,
        budget: ScanBudget,
    ): List<EngineAssist> {
        context.ensureActive()
        val directory = root.resolve(spec.path).normalize()
        val isRootAllowed = directory.startsWith(root) && isUnlinked(root, directory)
        if (!isRootAllowed || !Files.isDirectory(directory, NOFOLLOW_LINKS) || budget.remaining == 0) return emptyList()
        return Files.walk(directory, MAX_DEPTH).use { paths ->
            val result = mutableListOf<EngineAssist>()
            val iterator = paths.iterator()
            while (budget.remaining > 0 && iterator.hasNext()) {
                context.ensureActive()
                budget.visit()
                val file = iterator.next()
                if (isCandidate(root, file, spec)) metadata(file, directory, spec)?.let(result::add)
            }
            result
        }
    }

    private fun isCandidate(root: Path, file: Path, spec: AssistDirectory): Boolean {
        val isNameMatched = if (spec.isSkill) {
            file.fileName.toString() == "SKILL.md"
        } else {
            file.fileName.toString().endsWith(".md")
        }
        return isNameMatched && Files.isRegularFile(file, NOFOLLOW_LINKS) && isUnlinked(root, file)
    }

    private class ScanBudget(var remaining: Int) {
        fun visit() {
            remaining--
        }
    }

    private fun metadata(file: Path, directory: Path, spec: AssistDirectory): EngineAssist? = try {
        val text = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
            input.readNBytes(if (spec.isLiteralOnly) CONTENT_BYTES + 1 else FRONTMATTER_BYTES).toString(Charsets.UTF_8)
        }
        assist(text, file, directory, spec)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "project assist file not readable" }
        null
    }

    private fun assist(text: String, file: Path, directory: Path, spec: AssistDirectory): EngineAssist? {
        val fields = if (spec.isNativeSkillMetadataRequired) nativeSkillMetadata(text) else frontmatter(text)
        if (fields == null) return null
        val isLiteral = !spec.isLiteralOnly || (text.toByteArray().size <= CONTENT_BYTES && "!`" !in text)
        val name = assistName(file, directory, spec, fields)
        val isNameValid = name.split(':').all { NAME.matches(it) }
        return if (!isLiteral || !isNameValid || fields["user-invocable"] == "false") {
            null
        } else {
            EngineAssist(
                id = "${spec.path}:${directory.relativize(file).toString().replace('\\', '/')}",
                label = name,
                description = fields["description"].orEmpty(),
                insert = "${spec.prefix}$name ",
                kind = spec.kind,
            )
        }
    }

    private fun assistName(file: Path, directory: Path, spec: AssistDirectory, fields: Map<String, String>): String =
        when {
            !spec.isSkill -> directory.relativize(file).joinToString(":").removeSuffix(".md")
            spec.isNativeSkillMetadataRequired -> fields["name"].orEmpty()
            else -> fields["name"]?.takeIf { NAME.matches(it) } ?: file.parent.fileName.toString()
        }

    private fun isUnlinked(root: Path, path: Path): Boolean {
        var current = root
        for (part in root.relativize(path)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    private companion object {
        const val ENTRY_LIMIT = 2_000
        const val FRONTMATTER_BYTES = 8_192
        const val CONTENT_BYTES = 262_144
        const val MAX_DEPTH = 8
        val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    }
}

/** Only literal top-level metadata is read; YAML tags, interpolation and dynamic shell content are never evaluated. */
internal fun frontmatter(text: String): Map<String, String> {
    val lines = text.lineSequence()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap()
    return lines.drop(1).takeWhile { it.trim() != "---" }.mapNotNull(::literalField).toMap()
}

private fun literalField(line: String): Pair<String, String>? {
    if (line.startsWith(' ') || line.startsWith('\t')) return null
    val key = line.substringBefore(':')
    if (key !in setOf("name", "description", "user-invocable")) return null
    val value = line.substringAfter(':', "").trim().removeSurrounding("\"").removeSurrounding("'")
    return if (value.isBlank() || value in setOf("|", ">", "|-", ">-")) null else key to value
}
