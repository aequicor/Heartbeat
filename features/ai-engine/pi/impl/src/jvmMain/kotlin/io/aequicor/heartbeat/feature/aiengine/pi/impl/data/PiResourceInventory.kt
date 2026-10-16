package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceState
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * File inventory of the roots owned by Heartbeat. Installed packages and ancestors outside the saved project are
 * not traversed. Enabled means selected by saved settings and launch flags, not a guarantee that extension code runs.
 */
internal suspend fun piResourceInventory(
    home: Path,
    profile: EngineConfigValues,
    directory: String?,
    trust: ProjectTrust,
): ResourceInventory {
    val flags = piAgentConfig(profile, home, isProjectTrusted = directory != null && trust == ProjectTrust.Trusted) {
        null
    }
    val profileRoot = if (Files.exists(home)) home.toRealPath() else home.toAbsolutePath().normalize()
    val profileSettings = (profile.documents[PiDocuments.Settings] as? ConfigDocument.Json)?.value ?: JsonObject(
        emptyMap(),
    )
    val roots = buildList {
        directory?.let { add(ResourceRoot(Path.of(it).toRealPath(), ResourceOrigin.Project, trust, flags)) }
        add(ResourceRoot(profileRoot, ResourceOrigin.Profile, trust, flags))
    }
    val resources = roots.flatMap { root ->
        val settings = if (root.origin == ResourceOrigin.Profile) profileSettings else root.projectSettings()
        root.inventory(settings, hasProject = directory != null)
    }
    return ResourceInventory(
        PiEngineId,
        null,
        resources,
        external = roots.sumOf { it.external },
        isTruncated = roots.any { it.files.isTruncated },
    )
}

private class ResourceRoot(
    val root: Path,
    val origin: ResourceOrigin,
    val trust: ProjectTrust,
    val flags: PiAgentConfig,
) {
    val files = PiResourceFiles(root)
    var external = 0
        private set
    private val base = if (origin == ResourceOrigin.Project) root.resolve(".pi") else root
    private val isTrusted get() = trust == ProjectTrust.Trusted

    fun projectSettings(): JsonObject = files.text(base.resolve("settings.json"))
        ?.let(::resourceJson) ?: JsonObject(emptyMap())

    fun inventory(settings: JsonObject, hasProject: Boolean): List<EngineResource> =
        ResourceKind.entries.flatMap { resources(it, settings.entries(it), hasProject) }

    private fun resources(kind: ResourceKind, entries: List<String>, hasProject: Boolean): List<EngineResource> {
        val patterns = PiResourcePatterns(entries, base)
        val explicit = entries.asSequence().filterNot(String::isResourcePattern).mapNotNull(::local)
            .flatMap { files.collect(it, kind) }.distinct().toList()
        val automatic = automatic(kind)
        val agents = agents(kind)
        return (explicit + automatic + agents).distinct().map { path ->
            val matching = if (path in agents) PiResourcePatterns(entries, root.resolve(".agents")) else patterns
            val isSelected = matching.enabled(path, isAutomatic = path !in explicit)
            val selection = Selection(path in explicit, isSelected, path in automatic || path in agents)
            resource(path, kind, state(kind, selection, hasProject))
        }
    }

    private fun automatic(kind: ResourceKind): List<Path> {
        val path = base.resolve(kind.folder())
        return if (origin == ResourceOrigin.Project && !isTrusted && kind == ResourceKind.Prompt) {
            files.contentPrompts(path)
        } else {
            files.collect(path, kind, isAutomatic = true)
        }
    }

    private fun agents(kind: ResourceKind): List<Path> = if (origin == ResourceOrigin.Project &&
        kind == ResourceKind.Skill
    ) {
        files.collect(root.resolve(".agents/skills"), kind, isAutomatic = true, isAgents = isTrusted)
    } else {
        emptyList()
    }

    private fun resource(path: Path, kind: ResourceKind, state: ResourceState): EngineResource {
        val defaultName = if (kind == ResourceKind.Skill) {
            path.parent.fileName.toString()
        } else {
            path.fileName.toString().substringBeforeLast('.')
        }
        val metadata = if (kind == ResourceKind.Extension) emptyMap() else metadata(files.text(path))
        return EngineResource(
            kind,
            origin,
            root.relativize(path).posix(),
            if (kind == ResourceKind.Skill) metadata["name"] ?: defaultName else defaultName,
            state,
            metadata["description"],
        )
    }

    private fun state(kind: ResourceKind, selection: Selection, hasProject: Boolean): ResourceState {
        if (origin == ResourceOrigin.Profile && !selection.isExplicit) return ResourceState.NotListed
        if (origin == ResourceOrigin.Project && !isTrusted) {
            return if (kind != ResourceKind.Extension && selection.isAutomatic) {
                ResourceState.Enabled
            } else {
                ResourceState.NeedsTrust
            }
        }
        return when {
            !selection.isSelected -> ResourceState.Excluded
            isKindOff(kind, hasProject) -> ResourceState.KindOff
            else -> ResourceState.Enabled
        }
    }

    private fun isKindOff(kind: ResourceKind, hasProject: Boolean): Boolean = when (kind) {
        ResourceKind.Extension -> !flags.isDiscovering
        ResourceKind.Prompt -> !flags.isPromptsLoaded
        ResourceKind.Skill -> !hasProject && !flags.isSkillsLoaded
    }

    private data class Selection(val isExplicit: Boolean, val isSelected: Boolean, val isAutomatic: Boolean)

    private fun local(entry: String): Path? {
        if (entry.startsWith("builtin:")) return null
        if (entry.startsWith('~') || (entry.contains(':') && !entry.matches(WindowsPath))) {
            external++
            return null
        }
        val entered = Path.of(entry)
        if (WindowsPath.matches(entry) && !entered.isAbsolute) {
            external++
            return null
        }
        val path = base.resolve(entered).normalize()
        val isConfined = path.startsWith(root) && (!Files.exists(root) || isInside(root, path, RESOURCE_MAX_LINKS))
        if (!isConfined) {
            external++
            return null
        }
        return path
    }
}

private fun JsonObject.entries(kind: ResourceKind): List<String> = (get(kind.folder()) as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun ResourceKind.folder(): String = when (this) {
    ResourceKind.Extension -> "extensions"
    ResourceKind.Skill -> "skills"
    ResourceKind.Prompt -> "prompts"
}

/** Display-only leading YAML scalars; complex values are omitted rather than displayed as YAML syntax. */
private fun metadata(text: String?): Map<String, String> {
    val lines = text?.lineSequence()?.iterator() ?: return emptyMap()
    if (!lines.hasNext() || lines.next().trim() != "---") return emptyMap()
    return buildMap {
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trim() == "---") break
            val key = line.substringBefore(':')
            val value = line.substringAfter(':', "").trim().removeSurrounding("\"").removeSurrounding("'")
            if (key in listOf(
                    "name",
                    "description",
                ) && value.isNotBlank() && value !in listOf("|", ">")
            ) {
                put(key, value)
            }
        }
    }
}

private val WindowsPath = Regex("^[A-Za-z]:[/\\\\].*")

private const val RESOURCE_MAX_LINKS = 8
