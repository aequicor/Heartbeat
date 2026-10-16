package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Resource file presentation; the UI never consumes engine API types. */
@Immutable
data class ResourceFileUi(
    val kind: ResourceKindUi,
    val origin: ResourceOriginUi,
    val path: String,
    val name: String,
    val state: ResourceStateUi,
    val description: String?,
)

/** Resource category shown in the inventory. */
enum class ResourceKindUi { Extension, Skill, Prompt }

/** Saved profile or project source of a resource. */
enum class ResourceOriginUi { Profile, Project }

/** Resource selection state in the saved configuration. */
enum class ResourceStateUi { Enabled, Excluded, NotListed, NeedsTrust, KindOff }

/** An inventory of saved settings; external paths are counted but never read. */
@Immutable
data class ResourceInventoryUi(val files: ImmutableList<ResourceFileUi>, val external: Int, val isTruncated: Boolean)

internal fun ResourceInventory.toUi(): ResourceInventoryUi = ResourceInventoryUi(
    resources.map {
        ResourceFileUi(
            it.kind.toUi(),
            it.origin.toUi(),
            it.path,
            it.name,
            it.state.toUi(),
            it.description,
        )
    }.toImmutableList(),
    external,
    isTruncated,
)

// Exhaustive mappings: a new API value fails the build here instead of the panel at runtime.
private fun ResourceKind.toUi(): ResourceKindUi = when (this) {
    ResourceKind.Extension -> ResourceKindUi.Extension
    ResourceKind.Skill -> ResourceKindUi.Skill
    ResourceKind.Prompt -> ResourceKindUi.Prompt
}

private fun ResourceOrigin.toUi(): ResourceOriginUi = when (this) {
    ResourceOrigin.Profile -> ResourceOriginUi.Profile
    ResourceOrigin.Project -> ResourceOriginUi.Project
}

private fun ResourceState.toUi(): ResourceStateUi = when (this) {
    ResourceState.Enabled -> ResourceStateUi.Enabled
    ResourceState.Excluded -> ResourceStateUi.Excluded
    ResourceState.NotListed -> ResourceStateUi.NotListed
    ResourceState.NeedsTrust -> ResourceStateUi.NeedsTrust
    ResourceState.KindOff -> ResourceStateUi.KindOff
}
