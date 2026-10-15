package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Where a surface shows itself. */
@Serializable
public enum class UiPlacement {
    /** Inline in a compact row above the chat input while the owner is active in the chat. */
    @SerialName("composer")
    Composer,

    /** Inline in the start window of a new chat for which the owner is selected. */
    @SerialName("start")
    Start,

    /** In a floating top-right summary while the owner is active in the chat or preselected for a new chat. */
    @SerialName("context")
    Context,

    /** In a chat response via `ui_show`, with live owner data and actions, without a slot launcher. */
    @SerialName("card")
    Card,

    /** In the side panel, opened by a launcher or by an action of another surface. */
    @SerialName("panel")
    Panel,

    /** In a modal dialog, opened by a launcher or by an action of another surface. */
    @SerialName("modal")
    Modal,
    ;

    /** Whether the surface belongs to a host slot instead of opening its own panel or modal. */
    public val isInline: Boolean get() = this == Composer || this == Start || this == Context
}

/** Slots of a host: they draw their surfaces and the launchers of panels or modals. */
@Serializable
public enum class UiSlot {
    /** The row above the chat input. */
    @SerialName("composer")
    Composer,

    /** The start window of a new chat. */
    @SerialName("start")
    Start,

    /**
     * The content-sized, non-modal summary. The host first shifts chat and composer left, then may narrow them
     * while composer context controls fit on one row. Otherwise Summary reveals it over the unchanged chat.
     */
    @SerialName("context")
    Context,
    ;

    /** Placement of the surfaces drawn inline in this slot. */
    public val placement: UiPlacement
        get() = when (this) {
            Composer -> UiPlacement.Composer
            Start -> UiPlacement.Start
            Context -> UiPlacement.Context
        }
}

/**
 * Editable content of a surface: owner, name, where it appears, how it is presented and its declarative [spec]
 * (JSON of the spec format, validated by the feature before it is written). [launchers] put buttons that open a panel
 * or modal surface into inline slots.
 */
@Serializable
public data class UiSurfaceContent(
    val owner: UiOwner,
    val name: UiSurfaceName,
    val placement: UiPlacement,
    val title: String,
    val description: String = "",
    val launchers: Set<UiSlot> = emptySet(),
    val spec: String,
) {
    init {
        require(title.isNotBlank() && title.length <= ServiceUiLimits.TITLE_CHARS) { "Invalid surface title" }
        require(description.length <= ServiceUiLimits.DESCRIPTION_CHARS) { "Surface description is too long" }
        require(launchers.isEmpty() || placement == UiPlacement.Panel || placement == UiPlacement.Modal) {
            "Only panel and modal surfaces have launchers"
        }
        require(spec.isNotBlank() && spec.encodeToByteArray().size <= ServiceUiLimits.SPEC_BYTES) {
            "Surface spec is empty or too large"
        }
    }

    /** Identity of the surface this content creates or replaces. */
    val id: UiSurfaceId get() = UiSurfaceId.of(owner, name)

    /** UTF-8 bytes counted against [ServiceUiLimits.LIBRARY_BYTES]. */
    val bytes: Int
        get() = spec.encodeToByteArray().size + title.encodeToByteArray().size + description.encodeToByteArray().size

    override fun toString(): String = "UiSurfaceContent(id=${id.value}, placement=$placement)"
}

/** A stored surface: its [content] at [revision], whether it is enabled and who changed it last. */
@Serializable
public data class UiSurface(
    val content: UiSurfaceContent,
    @SerialName("enabled") val isEnabled: Boolean = true,
    val revision: Long = 1,
    val author: SessionRef? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(revision >= 1) { "Surface revision starts at 1" }
    }

    /** Identity of this surface. */
    val id: UiSurfaceId get() = content.id

    override fun toString(): String =
        "UiSurface(id=${id.value}, placement=${content.placement}, revision=$revision, isEnabled=$isEnabled)"
}

/** All surfaces of a profile; [revision] grows with every applied write and orders saved snapshots. */
@Serializable
public data class UiLibrary(val surfaces: List<UiSurface> = emptyList(), val revision: Long = 0) {
    init {
        require(revision >= 0) { "Library revision cannot be negative" }
        require(surfaces.map { it.id }.distinct().size == surfaces.size) { "Surface ids must be unique" }
    }

    /** The surface with [id], or null. */
    public operator fun get(id: UiSurfaceId): UiSurface? = surfaces.firstOrNull { it.id == id }

    /** Bytes counted against [ServiceUiLimits.LIBRARY_BYTES]. */
    public val bytes: Int get() = surfaces.sumOf { it.content.bytes }

    override fun toString(): String = "UiLibrary(surfaces=${surfaces.size}, revision=$revision)"
}

/** One change of the library; checked against the expected revision of the surface it changes. */
public sealed interface UiWriteOp {
    /** Creates ([expectedRevision] = 0) or replaces the surface identified by the owner and name of [content]. */
    public data class Put(
        val content: UiSurfaceContent,
        val expectedRevision: Long,
        val author: SessionRef?,
        val at: Instant,
    ) : UiWriteOp

    /** Enables or disables [surface] without changing its content. */
    public data class SetEnabled(
        val surface: UiSurfaceId,
        val isEnabled: Boolean,
        val expectedRevision: Long,
        val at: Instant,
    ) : UiWriteOp

    /** Removes [surface]. */
    public data class Delete(val surface: UiSurfaceId, val expectedRevision: Long) : UiWriteOp

    /** Removes every surface of [owners]: their provider confirmed that they no longer exist. */
    public data class Purge(val owners: Set<UiOwner>) : UiWriteOp
}

/** Why a write was not applied. */
public enum class UiWriteRejection {
    /** The surface exists with another revision, or a creation found it already present. */
    Conflict,

    /** The surface does not exist. */
    Missing,

    /** The result would exceed [ServiceUiLimits]. */
    Limit,
}

/** Outcome of [UiLibrary.apply]. */
public sealed interface UiWriteResult {
    /** The new [library]; [surface] is the created or changed surface, [removed] lists deleted surfaces. */
    public data class Applied(
        val library: UiLibrary,
        val surface: UiSurface? = null,
        val removed: Set<UiSurfaceId> = emptySet(),
    ) : UiWriteResult

    /** Nothing changed, for [reason]. */
    public data class Rejected(val reason: UiWriteRejection) : UiWriteResult
}

/** Applies [op] to this library. Pure: the caller persists [UiWriteResult.Applied.library]. */
public fun UiLibrary.apply(op: UiWriteOp): UiWriteResult = when (op) {
    is UiWriteOp.Put -> put(op)
    is UiWriteOp.SetEnabled -> setEnabled(op)
    is UiWriteOp.Delete -> delete(op)
    is UiWriteOp.Purge -> purge(op.owners)
}

private fun UiLibrary.put(op: UiWriteOp.Put): UiWriteResult {
    val existing = this[op.content.id]
    val mismatch = revisionMismatch(existing, op.expectedRevision)
    val surface = existing?.copy(
        content = op.content,
        revision = existing.revision + 1,
        author = op.author,
        updatedAt = op.at,
    ) ?: UiSurface(op.content, author = op.author, createdAt = op.at, updatedAt = op.at)
    val next = if (existing == null) surfaces + surface else surfaces.replaced(surface)
    return when {
        mismatch != null -> UiWriteResult.Rejected(mismatch)
        !next.isWithinLimits() -> UiWriteResult.Rejected(UiWriteRejection.Limit)
        else -> UiWriteResult.Applied(copy(surfaces = next, revision = revision + 1), surface)
    }
}

private fun UiLibrary.setEnabled(op: UiWriteOp.SetEnabled): UiWriteResult {
    val existing = this[op.surface] ?: return UiWriteResult.Rejected(UiWriteRejection.Missing)
    revisionMismatch(existing, op.expectedRevision)?.let { return UiWriteResult.Rejected(it) }
    val surface = existing.copy(isEnabled = op.isEnabled, revision = existing.revision + 1, updatedAt = op.at)
    return UiWriteResult.Applied(copy(surfaces = surfaces.replaced(surface), revision = revision + 1), surface)
}

private fun List<UiSurface>.replaced(surface: UiSurface): List<UiSurface> =
    map { if (it.id == surface.id) surface else it }

private fun UiLibrary.delete(op: UiWriteOp.Delete): UiWriteResult {
    val existing = this[op.surface] ?: return UiWriteResult.Rejected(UiWriteRejection.Missing)
    revisionMismatch(existing, op.expectedRevision)?.let { return UiWriteResult.Rejected(it) }
    val surfaces = surfaces.filterNot { it.id == existing.id }
    return UiWriteResult.Applied(copy(surfaces = surfaces, revision = revision + 1), removed = setOf(existing.id))
}

private fun UiLibrary.purge(owners: Set<UiOwner>): UiWriteResult {
    val (removed, kept) = surfaces.partition { it.content.owner in owners }
    if (removed.isEmpty()) return UiWriteResult.Applied(this)
    return UiWriteResult.Applied(
        copy(surfaces = kept, revision = revision + 1),
        removed = removed.mapTo(mutableSetOf()) { it.id },
    )
}

/** Null when [expected] matches [existing]; 0 means "the surface must not exist yet". */
private fun revisionMismatch(existing: UiSurface?, expected: Long): UiWriteRejection? = when {
    existing == null && expected == 0L -> null
    existing == null -> UiWriteRejection.Missing
    existing.revision == expected -> null
    else -> UiWriteRejection.Conflict
}

private fun List<UiSurface>.isWithinLimits(): Boolean {
    val perOwner = groupingBy { it.content.owner }.eachCount().values
    return size <= ServiceUiLimits.SURFACES &&
        perOwner.all { it <= ServiceUiLimits.SURFACES_PER_OWNER } &&
        sumOf { it.content.bytes } <= ServiceUiLimits.LIBRARY_BYTES
}
