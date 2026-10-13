package io.aequicor.heartbeat.feature.serviceasui.api

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Who owns a surface: a [kind] of provider (for example `harness`) and the provider's own [id] of the owner.
 * A surface is shown only while its owner exists, is enabled and is active where the surface would appear.
 */
@Serializable
public data class UiOwner(public val kind: String, public val id: String) {
    init {
        require(kind.matches(OwnerKind)) { "Owner kind must be a lowercase slug" }
        require(id.isNotBlank() && id.length <= MAX_OWNER_ID && ':' !in id) { "Owner id must be short and without ':'" }
    }
}

/** Immutable surface slug, unique within its owner: actions of the owner's other surfaces open it by this name. */
@Serializable
@JvmInline
public value class UiSurfaceName(public val value: String) {
    init {
        require(value.matches(SurfaceName)) { "Surface name must be a lowercase slug of at most 32 characters" }
    }
}

/** Stable identity of a surface in routes and logs, derived from its owner and name (see [of]). */
@Serializable
@JvmInline
public value class UiSurfaceId(public val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_SURFACE_ID)
    }

    /** Derives surface ids. */
    public companion object {
        /** The id of the surface [name] of [owner]. */
        public fun of(owner: UiOwner, name: UiSurfaceName): UiSurfaceId =
            UiSurfaceId("${owner.kind}:${owner.id}:${name.value}")
    }
}

/** A place of a host that shows inline surfaces, such as one pane of the studio; the host binds its context. */
@Serializable
@JvmInline
public value class UiHostId(public val value: String) {
    init {
        require(value.matches(PlaceId)) { "Host id must be a short lowercase identifier" }
    }
}

/** A place where panels open beside the host's content, such as the side panel of the studio window. */
@Serializable
@JvmInline
public value class UiDockId(public val value: String) {
    init {
        require(value.matches(PlaceId)) { "Dock id must be a short lowercase identifier" }
    }
}

private const val MAX_OWNER_ID = 128
private const val MAX_SURFACE_ID = 200
private val OwnerKind = Regex("[a-z][a-z0-9_]{0,31}")
private val SurfaceName = Regex("[a-z][a-z0-9_]{0,31}")
private val PlaceId = Regex("[a-z0-9][a-z0-9._-]{0,63}")
