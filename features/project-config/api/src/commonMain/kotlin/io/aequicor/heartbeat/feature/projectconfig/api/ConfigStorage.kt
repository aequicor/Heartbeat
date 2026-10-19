package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable

/** Durable location of project configuration; runtime libraries remain the trusted execution cache. */
@Serializable
public enum class ConfigStorage {
    /** Automatically read and write the project's `.heartbeat` tree. */
    Project,

    /** Keep configuration in Heartbeat's profile libraries without accessing the project tree. */
    App,
}

/** Profile default and explicit project overrides, stored in Heartbeat independently of project files. */
@Serializable
public data class ConfigStorageSettings(
    val default: ConfigStorage = ConfigStorage.Project,
    /** Overrides keyed by the stable workspace reference value, portable JSON object keys. */
    val projects: Map<String, ConfigStorage> = emptyMap(),
) {
    /** Effective location of [project]; removing its override restores inheritance. */
    public fun location(project: WorkspaceRef): ConfigStorage = projects[project.value] ?: default
}
