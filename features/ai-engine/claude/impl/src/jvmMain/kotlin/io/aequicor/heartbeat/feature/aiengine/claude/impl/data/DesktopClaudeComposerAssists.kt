package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Imports only literal command/skill entrypoints into an operation-owned plugin. Project settings, hooks,
 * permissions, agents and plugin manifests are never imported. Inline shell expansion is unsupported and
 * hidden from suggestions. Relative supporting references keep their original project directory; access to
 * those files and execution of scripts still go through the turn's normal tool policy.
 * Claude namespaces imported commands as `/heartbeat-project:name`; suggestions use that exact spelling.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopClaudeComposerAssists(
    private val reader: ProjectAssistReader,
    private val workspaces: LocalWorkspaces,
    private val launches: EngineLaunchConfig,
    private val dispatchers: DispatcherProvider,
) : ClaudeComposerAssists {
    private val log = Log.tag("Claude/ProjectAssists")

    override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> =
        entries(workspace).map { it.assist }

    override suspend fun prepare(workspace: WorkspaceRef?): ClaudePreparedAssists? {
        val before = launches.context(ClaudeEngine.Id)
        val entries = entries(workspace)
        if (entries.isEmpty()) return null
        var prepared: ClaudePreparedAssists? = null
        var isTransferred = false
        try {
            runInterruptible(dispatchers.io) { prepared = writePlugin(entries) }
            if (launches.context(ClaudeEngine.Id) != before) return null
            isTransferred = true
            return prepared
        } finally {
            if (!isTransferred) prepared?.close()
        }
    }

    private suspend fun entries(workspace: WorkspaceRef?): List<Entry> {
        val project = workspace ?: return emptyList()
        val before = launches.context(ClaudeEngine.Id)
        if (before.trust[project] != ProjectTrust.Trusted) return emptyList()
        val candidates = reader.read(ClaudeEngine.Id, project, DIRECTORIES)
        if (candidates.isEmpty()) return emptyList()
        val directory = workspaces.resolve(project) ?: return emptyList()
        val entries = try {
            withTimeoutOrNull(2.seconds) {
                val context = currentCoroutineContext()
                runInterruptible(dispatchers.io) {
                    readEntries(Path.of(directory).toRealPath(), candidates, context)
                }
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "project commands could not be imported" }
            emptyList()
        }
        return entries.takeIf { launches.context(ClaudeEngine.Id) == before }.orEmpty()
    }

    private fun readEntries(
        root: Path,
        candidates: List<EngineAssist>,
        context: kotlin.coroutines.CoroutineContext,
    ): List<Entry> {
        var remaining = TOTAL_BYTES
        return candidates.distinctBy { it.label }.mapNotNull { assist ->
            context.ensureActive()
            if (remaining <= 0) return@mapNotNull null
            val entry = readEntry(root, assist) ?: return@mapNotNull null
            remaining -= entry.content.toByteArray().size
            entry.takeIf { remaining >= 0 }
        }
    }

    private fun readEntry(root: Path, assist: EngineAssist): Entry? {
        val path = root.resolve(assist.id.replaceFirst(':', '/')).normalize()
        if (!path.startsWith(root)) return null
        if (root.relativize(path).runningFold(root, Path::resolve).any(Files::isSymbolicLink)) return null
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(FILE_BYTES + 1) }
        if (bytes.size > FILE_BYTES) return null
        val text = literalClaudeContent(bytes.toString(Charsets.UTF_8), path.parent.toString()) ?: return null
        return Entry(assist, text)
    }

    private fun writePlugin(entries: List<Entry>): ClaudePreparedAssists {
        val root = Files.createTempDirectory(claudeMcpDirectory(), "heartbeat-project-")
        restrictToOwner(root, directory = true)
        val plugin = Prepared(root)
        try {
            val manifest = Files.createDirectory(root.resolve(".claude-plugin")).resolve("plugin.json")
            val descriptor = buildJsonObject {
                put("name", "heartbeat-project")
                put("version", "1.0.0")
                put(
                    "commands",
                    buildJsonObject {
                        entries.forEachIndexed { index, entry ->
                            put(entry.assist.label, buildJsonObject { put("source", "./commands/entry-$index.md") })
                        }
                    },
                )
            }
            Files.writeString(manifest, descriptor.toString())
            // Both skill and command entries are explicit commands: the host never grants a Skill tool or
            // imports skill frontmatter that could change permissions, fork agents or install hooks.
            val commands = Files.createDirectory(root.resolve("commands"))
            entries.forEachIndexed { index, entry ->
                val path = commands.resolve("entry-$index.md")
                Files.writeString(path, entry.content)
            }
            return plugin
        } catch (e: CancellationException) {
            plugin.close()
            throw e
        } catch (e: Exception) {
            plugin.close()
            throw e
        }
    }

    private data class Entry(val assist: EngineAssist, val content: String)

    private class Prepared(private val path: Path) : ClaudePreparedAssists {
        override val directory: String = path.toString()
        override fun close() {
            try {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } catch (e: java.io.IOException) {
                Log.tag("Claude/ProjectAssists").w(e.redactedFailure()) { "temporary commands could not be removed" }
            }
        }
    }

    private companion object {
        const val FILE_BYTES = 262_144
        const val TOTAL_BYTES = 4 * 1_024 * 1_024
        val DIRECTORIES = listOf(
            AssistDirectory(".claude/skills", EngineAssist.Kind.Skill, true, "/heartbeat-project:", true),
            AssistDirectory(".agents/skills", EngineAssist.Kind.Skill, true, "/heartbeat-project:", true),
            AssistDirectory(".claude/commands", EngineAssist.Kind.Command, false, "/heartbeat-project:", true),
        )
    }
}

/** Drops all project-controlled frontmatter and refuses CLI shell interpolation before native tool approval. */
internal fun literalClaudeContent(text: String, sourceDirectory: String): String? {
    if ("!`" in text || "!`" in sourceDirectory) return null
    val normalized = text.replace("\r\n", "\n")
    val body = if (normalized.startsWith("---\n")) {
        val end = normalized.indexOf("\n---", 4)
        if (end < 0) return null
        normalized.substring(end + 4).trimStart('\n')
    } else {
        normalized
    }
    return "Source directory: $sourceDirectory\n" +
        "Resolve relative supporting-file references against this directory.\n\n" +
        body.replace("\${CLAUDE_SKILL_DIR}", sourceDirectory)
}
