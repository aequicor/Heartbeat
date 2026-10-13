package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemotePage
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteRefusal
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WatchRef
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The studio screen in the side panel: `studio.watch` opens (or joins again) a remote studio view and relays its
 * frames as `studio.changed` events; `studio.intent` hands the panel's screen intents to that view. The bridge
 * relays the studio's JSON without reading it.
 */
@ContributesIntoSet(ProfileScope::class, binding = binding<BridgeRequestHandler>())
@Inject
internal class SidePanelStudioHandlers(private val studio: StudioRemoteScreens, private val watches: BridgeWatches) :
    BridgeRequestHandler {
    private val log = Log.tag("SidePanelStudio")

    override val methods: Set<String> = setOf(STUDIO_WATCH, STUDIO_INTENT, STUDIO_ATTACH_PAGE)

    override suspend fun handle(call: BridgeCall): BridgeReply = when (call.method) {
        STUDIO_WATCH -> watch(call.session, decode(call, ViewOpen.serializer()))
        STUDIO_INTENT -> intents(decode(call, ViewIntents.serializer()))
        STUDIO_ATTACH_PAGE -> attachPage(decode(call, ViewPage.serializer()))
        else -> BridgeReply.Refused(BridgeErrorCode.UNKNOWN_METHOD, "Unknown method")
    }

    private suspend fun watch(session: BridgeSession, params: ViewOpen): BridgeReply {
        require(params.view.isNotEmpty() && params.view.length <= MAX_ID_CHARS) { "Invalid view" }
        return when (val open = studio.open(params.view, params.schema)) {
            is StudioRemoteOpen.Refused -> open.reason.refusal()
            is StudioRemoteOpen.Opened -> relay(session, params.view, open.frames)
        }
    }

    private suspend fun relay(session: BridgeSession, view: String, frames: Flow<JsonObject>): BridgeReply {
        // Large snapshots can exceed the early-event buffer before the caller knows its watch id. Reserve a
        // running job now, but only collect after the response reaches the transport. Failed sends release it.
        val delivered = CompletableDeferred<Boolean>()
        val watch = watches.open(session) { watch ->
            if (delivered.await()) frames.collect { frame -> emitFrame(session, watch, view, frame) }
        }
        return if (watch == null) {
            BridgeReply.Refused(BridgeErrorCode.BUSY, "Too many watches")
        } else {
            log.i { "Studio view watched" }
            BridgeReply.Ok(BridgeProtocol.json.encodeToJsonElement(WatchRef.serializer(), WatchRef(watch))) {
                delivered.complete(it)
            }
        }
    }

    @HighFrequency
    private suspend fun emitFrame(session: BridgeSession, watch: String, view: String, frame: JsonObject) {
        log.v { "Studio frame ${frame.size} properties" }
        session.emit(STUDIO_CHANGED, JsonObject(mapOf("watch" to json(watch), "view" to json(view), "frame" to frame)))
    }

    private suspend fun intents(params: ViewIntents): BridgeReply {
        require(params.intents.size <= MAX_INTENTS) { "Too many intents" }
        val intents = params.intents.map { StudioRemoteIntent(it.seq, it.intent) }
        return studio.send(params.view, params.generation, intents).reply()
    }

    private suspend fun attachPage(params: ViewPage): BridgeReply {
        require(params.page.isValid()) { "Invalid captured page" }
        val page = params.page.let { StudioRemotePage(it.url, it.title, it.text, it.selection) }
        return studio.attachPage(params.view, params.generation, params.seq, params.paneId, page).reply()
    }

    private fun StudioRemoteReply.reply(): BridgeReply = when (this) {
        is StudioRemoteReply.Applied -> BridgeReply.Ok(
            BridgeProtocol.json.encodeToJsonElement(ViewAck.serializer(), ViewAck(ack, refused)),
        )

        StudioRemoteReply.UnknownView -> BridgeReply.Refused(BridgeErrorCode.UNKNOWN_VIEW, "Unknown view")
    }

    private fun StudioRemoteRefusal.refusal(): BridgeReply.Refused = when (this) {
        StudioRemoteRefusal.Unavailable -> BridgeReply.Refused(BridgeErrorCode.UNAVAILABLE, "Studio views are off")

        StudioRemoteRefusal.Incompatible ->
            BridgeReply.Refused(BridgeErrorCode.UNSUPPORTED_PROTOCOL, "The extension was built for another app")

        StudioRemoteRefusal.Busy -> BridgeReply.Refused(BridgeErrorCode.BUSY, "Too many studio views")
    }

    private fun <T> decode(call: BridgeCall, serializer: KSerializer<T>): T =
        BridgeProtocol.json.decodeFromJsonElement(serializer, call.params)

    private fun json(value: String): JsonElement = JsonPrimitive(value)

    /** `studio.watch` parameters: the panel's view id and its screen model fingerprint. */
    @Serializable
    private data class ViewOpen(val view: String, val schema: String)

    /** `studio.intent` parameters. */
    @Serializable
    private data class ViewIntents(val view: String, val generation: String, val intents: List<ViewIntent>)

    /** One numbered screen intent of the panel. */
    @Serializable
    private data class ViewIntent(val seq: Long, val intent: JsonElement)

    /** Page capture in the same ordered stream as `studio.intent`. */
    @Serializable
    private data class ViewPage(
        val view: String,
        val generation: String,
        val seq: Long,
        val paneId: Int,
        val page: WirePage,
    )

    /** `studio.intent` result. */
    @Serializable
    private data class ViewAck(val ack: Long, val refused: List<Long>)

    private companion object {
        const val STUDIO_WATCH = "studio.watch"
        const val STUDIO_INTENT = "studio.intent"
        const val STUDIO_ATTACH_PAGE = "studio.attachPage"
        const val STUDIO_CHANGED = "studio.changed"
        const val MAX_INTENTS = 64
        const val MAX_ID_CHARS = 256
    }
}
