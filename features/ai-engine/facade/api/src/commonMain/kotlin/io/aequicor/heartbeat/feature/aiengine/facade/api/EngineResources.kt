package io.aequicor.heartbeat.feature.aiengine.facade.api

/** What a resource file is to the engine. */
public enum class ResourceKind {
    /** Code the engine loads into its process. */
    Extension,

    /** Instructions the model reads when a task needs them. */
    Skill,

    /** A prompt template, invoked as a command. */
    Prompt,
}

/** Where a resource file lies. */
public enum class ResourceOrigin {
    /** The profile's own folder of the engine, where imported resources are copied. */
    Profile,

    /** A saved project. */
    Project,
}

/**
 * Selection by saved configuration and launch flags. This is not a loader diagnosis: invalid file contents,
 * name collisions and extension runtime errors can still prevent a selected resource from loading.
 */
public enum class ResourceState {
    /** Selected by configuration. */
    Enabled,

    /** The settings leave it out: a `!glob` or `-path` entry, or include patterns it does not match. */
    Excluded,

    /** It lies in the profile's folder, but no settings entry lists it. */
    NotListed,

    /** A project resource that loads only once the project is trusted. */
    NeedsTrust,

    /** The profile lists no resources of this kind, so the engine starts without looking for them in projects. */
    KindOff,
}

/**
 * One resource file: its [path] relative to the root of its [origin] (the profile's folder, the project root) with
 * `/` separators, the [name] the engine knows it by and its [description] from the file's frontmatter. Paths and
 * descriptions are the user's and never logged.
 */
public data class EngineResource(
    val kind: ResourceKind,
    val origin: ResourceOrigin,
    val path: String,
    val name: String,
    val state: ResourceState,
    val description: String? = null,
) {
    override fun toString(): String = "EngineResource(kind=$kind, origin=$origin, state=$state)"
}

/**
 * Resource files of [engine] in the profile and, with [workspace], in that project, grouped by kind,
 * with project resources before profile resources. [external]
 * counts settings entries naming places outside those folders (absolute and home paths), whose files are not listed;
 * [isTruncated] when the listing is incomplete: the folders hold more than it reads, some entries could not be read,
 * a mask was beyond the limits of a single match (it is treated as not matching), or the read ran out of time.
 */
public data class ResourceInventory(
    val engine: EngineId,
    val workspace: WorkspaceRef?,
    val resources: List<EngineResource>,
    val external: Int = 0,
    val isTruncated: Boolean = false,
)
