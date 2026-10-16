package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
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
            ResourceKindUi.valueOf(it.kind.name),
            ResourceOriginUi.valueOf(it.origin.name),
            it.path,
            it.name,
            ResourceStateUi.valueOf(it.state.name),
            it.description,
        )
    }.toImmutableList(),
    external,
    isTruncated,
)
