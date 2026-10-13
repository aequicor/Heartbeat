package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatRejection
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatChanged
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatRef
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatSendParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatSent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatStopped
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatWireLimits
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatsChanged
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.PermissionAnswerParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.WatchRef
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.WireEntry
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.toWire
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.wireWindow
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.TranscriptChange
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.TranscriptDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The side-panel chat: watches of the chat list and of one transcript, sending prompts, stopping a turn and
 * answering permissions. Everything goes through the profile's [StudioChats]; the panel never sees more than the
 * capped wire form, and events are throttled so streaming does not flood the browser.
 */
internal class SidePanelChatHandlers(
    private val chats: StudioChats,
    private val pages: PageImporter,
    private val browser: Lazy<ExternalBrowser>,
) : BridgeRequestHandler {
    private val log = Log.tag("SidePanelChat")
    private val watches = BridgeWatches()

    override val methods: Set<String> = setOf(CHATS_WATCH, CHAT_WATCH, WATCH_CANCEL, CHAT_SEND, CHAT_STOP, RESPOND)

    override suspend fun handle(call: BridgeCall): BridgeReply = when (call.method) {
        CHATS_WATCH -> watchList(call.session)
        CHAT_WATCH -> watchChat(call.session, decode(call, ChatRef.serializer()).chatId.checkedId())
        WATCH_CANCEL -> cancel(call.session, decode(call, WatchRef.serializer()).watch)
        CHAT_SEND -> send(call.session, decode(call, ChatSendParams.serializer()))
        CHAT_STOP -> stop(decode(call, ChatRef.serializer()).chatId.checkedId())
        RESPOND -> respond(decode(call, PermissionAnswerParams.serializer()))
        else -> BridgeReply.Refused(BridgeErrorCode.UNKNOWN_METHOD, "Unknown method")
    }

    private suspend fun watchList(session: BridgeSession): BridgeReply = opened(
        watches.open(session) { watch ->
            combine(chats.observeList(), chats.observeComposer(), ::Pair)
                .throttled(LIST_PERIOD_MILLIS)
                .collect { (list, composer) ->
                    session.emit(CHATS_CHANGED, encode(ChatsChanged.serializer(), list.toWire(watch, composer)))
                }
        },
    )

    private suspend fun watchChat(session: BridgeSession, chatId: String): BridgeReply = opened(
        watches.open(session) { watch ->
            val diff = TranscriptDiff(WireEntry::id)
            var previousOmitted = -1
            chats.observeTranscript(chatId).throttled(CHAT_PERIOD_MILLIS).collect { entries ->
                val window = entries.wireWindow()
                val omitted = entries.size - window.size
                val change = diff.next(window) ?: if (omitted != previousOmitted) {
                    TranscriptChange(false, emptyList(), emptyList())
                } else {
                    return@collect
                }
                previousOmitted = omitted
                val event = ChatChanged(
                    watch = watch,
                    chatId = chatId,
                    isReset = change.isReset,
                    upserts = change.upserts,
                    removed = change.removed,
                    olderOmitted = omitted,
                )
                emitChange(session, event)
            }
        },
    )

    @HighFrequency
    private suspend fun emitChange(session: BridgeSession, event: ChatChanged) {
        log.v { "Transcript change upserts=${event.upserts.size} removed=${event.removed.size}" }
        session.emit(CHAT_CHANGED, encode(ChatChanged.serializer(), event))
    }

    private fun opened(watch: String?): BridgeReply = if (watch == null) {
        BridgeReply.Refused(BridgeErrorCode.BUSY, "Too many watches")
    } else {
        BridgeReply.Ok(encode(WatchRef.serializer(), WatchRef(watch)))
    }

    private suspend fun cancel(session: BridgeSession, watch: String): BridgeReply {
        watches.cancel(session, watch)
        return BridgeReply.Ok(JsonObject(emptyMap()))
    }

    private suspend fun send(session: BridgeSession, params: ChatSendParams): BridgeReply {
        require(params.text.isNotBlank() && params.text.length <= MAX_PROMPT_CHARS) { "Invalid prompt" }
        require(params.attachments.size <= ChatWireLimits.MAX_PAGES && params.attachments.all { it.isValid() }) {
            "Invalid attachments"
        }
        val attachments = when (val imported = pages.import(params.attachments)) {
            is PageImport.Imported -> imported.resources
            PageImport.Refused -> return BridgeReply.Refused(BridgeErrorCode.BUSY, "Attachments are busy")
        }
        val settings = params.settings?.let { wire ->
            require(wire.modelId.isNotEmpty() && wire.modelId.length <= MAX_MODEL_ID_CHARS) { "Invalid model" }
            requireNotNull(wire.toSettings()) { "Invalid approval" }
        }
        val request = StudioChatSend(
            params.chatId?.checkedId(),
            params.projectId?.checkedId(),
            params.text,
            attachments,
            settings = settings,
            environment = object : StudioChatEnvironment {
                override val preselected = mapOf("external_browser" to setOf(session.id.toString()))

                override fun bind(sessionRef: SessionRef): AutoCloseable =
                    browser.value.bindSession(Json.encodeToString(sessionRef), session.id.toString())
            },
        )
        return when (val result = chats.send(request)) {
            is StudioChatSendResult.Accepted -> BridgeReply.Ok(encode(ChatSent.serializer(), ChatSent(result.chatId)))
            is StudioChatSendResult.Rejected -> result.reason.refusal()
        }
    }

    private suspend fun stop(chatId: String): BridgeReply =
        BridgeReply.Ok(encode(ChatStopped.serializer(), ChatStopped(chats.stop(chatId))))

    private suspend fun respond(params: PermissionAnswerParams): BridgeReply {
        val isAccepted = chats.respond(
            params.chatId.checkedId(),
            params.requestId.checkedId(),
            params.optionId.checkedId(),
            params.answer?.toAnswer(),
        )
        return if (isAccepted) {
            BridgeReply.Ok(JsonObject(emptyMap()))
        } else {
            BridgeReply.Refused(BridgeErrorCode.STALE_REQUEST, "The request is no longer waiting")
        }
    }

    private fun StudioChatRejection.refusal(): BridgeReply.Refused = when (this) {
        StudioChatRejection.Unavailable -> BridgeReply.Refused(BridgeErrorCode.UNAVAILABLE, "Profile chats are off")
        StudioChatRejection.UnknownChat -> BridgeReply.Refused(BridgeErrorCode.UNKNOWN_CHAT, "Unknown chat")
        StudioChatRejection.UnknownProject -> BridgeReply.Refused(BridgeErrorCode.BAD_REQUEST, "Unknown project")
        StudioChatRejection.NotContinuable -> BridgeReply.Refused(BridgeErrorCode.NOT_CONTINUABLE, "Chat is closed")
        StudioChatRejection.Busy -> BridgeReply.Refused(BridgeErrorCode.BUSY, "A turn is running")
        StudioChatRejection.NoModel -> BridgeReply.Refused(BridgeErrorCode.NO_MODEL, "No model for a new chat")
        StudioChatRejection.Failed -> BridgeReply.Refused(BridgeErrorCode.INTERNAL, "The turn did not start")
    }

    private fun <T> decode(call: BridgeCall, serializer: KSerializer<T>): T =
        BridgeProtocol.json.decodeFromJsonElement(serializer, call.params)

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        BridgeProtocol.json.encodeToJsonElement(serializer, value).jsonObject

    private fun String.checkedId(): String = also {
        require(
            it.isNotEmpty() && it.length <= MAX_ID_CHARS,
        ) { "Invalid id" }
    }

    private companion object {
        const val CHATS_WATCH = "chats.watch"
        const val CHAT_WATCH = "chat.watch"
        const val WATCH_CANCEL = "watch.cancel"
        const val CHAT_SEND = "chat.send"
        const val CHAT_STOP = "chat.stop"
        const val RESPOND = "permission.respond"
        const val CHATS_CHANGED = "chats.changed"
        const val CHAT_CHANGED = "chat.changed"
        const val LIST_PERIOD_MILLIS = 250L
        const val CHAT_PERIOD_MILLIS = 150L
        const val MAX_PROMPT_CHARS = 100_000
        const val MAX_ID_CHARS = 256
        const val MAX_MODEL_ID_CHARS = 1024
    }
}

/** At most one value per [periodMillis], always ending with the latest. */
private fun <T> Flow<T>.throttled(periodMillis: Long): Flow<T> = conflate().transform { value ->
    emit(value)
    delay(periodMillis)
}
