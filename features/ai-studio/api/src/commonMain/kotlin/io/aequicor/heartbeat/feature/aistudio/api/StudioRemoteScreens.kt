package io.aequicor.heartbeat.feature.aistudio.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Remote views of the studio screen for hosts outside the app window (the Chromium side panel): each view is a
 * studio screen of its own, run by the profile, that the host draws from frames and drives with screen intents.
 * Frames and intents are the studio's own JSON (`features:ai-studio:screen-model`); a host only relays them.
 *
 * A view lives while a host collects its frames and a short grace period after, so a reconnecting host finds its
 * chat, drafts and selection again. Native desktop surfaces (file and folder pickers, opening or exporting an
 * attachment, panes side by side) are refused. Unavailable while `ai_studio.engine_runtime` is off.
 */
public interface StudioRemoteScreens {
    /** Whether remote views can be opened (`ai_studio.engine_runtime`). */
    public val availability: Flow<Boolean>

    /**
     * Opens view [viewId], or joins it again within its grace period. [schema] is the fingerprint of the host's
     * screen model; a view is refused when it differs from the app's.
     */
    public suspend fun open(viewId: String, schema: String): StudioRemoteOpen

    /**
     * Applies host [intents] to view [viewId] in [StudioRemoteIntent.seq] order; intents at or below the last
     * applied sequence number are ignored, so a host may resend after reconnecting.
     */
    public suspend fun send(viewId: String, intents: List<StudioRemoteIntent>): StudioRemoteReply
}

/** One screen intent of a host, numbered by the host. */
public data class StudioRemoteIntent(public val seq: Long, public val intent: JsonElement)

/** Result of [StudioRemoteScreens.open]. */
public sealed interface StudioRemoteOpen {
    /** Frames of the view: a snapshot first. Collecting keeps the view attached; cancelling starts its grace. */
    public data class Opened(public val frames: Flow<JsonObject>) : StudioRemoteOpen

    /** The view cannot be opened. */
    public data class Refused(public val reason: StudioRemoteRefusal) : StudioRemoteOpen
}

/** Why a view was refused. */
public enum class StudioRemoteRefusal {
    /** Remote views are off. */
    Unavailable,

    /** The host speaks another screen model; it needs the extension built with this app. */
    Incompatible,

    /** Too many views are open in this profile. */
    Busy,
}

/** Result of [StudioRemoteScreens.send]. */
public sealed interface StudioRemoteReply {
    /** Intents were taken up to [ack]; [refused] lists those not allowed in a remote view or not understood. */
    public data class Applied(public val ack: Long, public val refused: List<Long>) : StudioRemoteReply

    /** No such view (closed after its grace period, or the app restarted): the host opens it again. */
    public data object UnknownView : StudioRemoteReply
}
