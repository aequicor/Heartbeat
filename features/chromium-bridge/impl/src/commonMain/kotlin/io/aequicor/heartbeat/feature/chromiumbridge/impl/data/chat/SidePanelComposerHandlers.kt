package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatConfigureParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatEditParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ComposerEffortParams
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * The side panel's composer and sidebar controls: a chat's model, reasoning effort and approvals, the effort of new
 * chats and the chat's metadata. Changes the studio did not apply come back as `not_applied`; the next
 * `chats.changed` shows what holds.
 */
@ContributesIntoSet(ProfileScope::class, binding = binding<BridgeRequestHandler>())
@Inject
internal class SidePanelComposerHandlers(private val chats: StudioChats) : BridgeRequestHandler {
    override val methods: Set<String> = setOf(CHAT_CONFIGURE, COMPOSER_EFFORT, CHAT_EDIT)

    override suspend fun handle(call: BridgeCall): BridgeReply = when (call.method) {
        CHAT_CONFIGURE -> {
            val params = decode(call, ChatConfigureParams.serializer())
            params.value?.checked(MAX_MODEL_ID_CHARS)
            val change = requireNotNull(params.toChange()) { "Invalid change" }
            applied(chats.configure(params.chatId.checked(MAX_ID_CHARS), change))
        }

        COMPOSER_EFFORT -> {
            val params = decode(call, ComposerEffortParams.serializer())
            params.effort?.checked(MAX_MODEL_ID_CHARS)
            applied(chats.chooseNewChatEffort(params.modelId.checked(MAX_MODEL_ID_CHARS), params.effort))
        }

        CHAT_EDIT -> {
            val params = decode(call, ChatEditParams.serializer())
            val edit = requireNotNull(params.toEdit()) { "Invalid edit" }
            applied(chats.edit(params.chatId.checked(MAX_ID_CHARS), edit))
        }

        else -> BridgeReply.Refused(BridgeErrorCode.UNKNOWN_METHOD, "Unknown method")
    }

    private fun applied(isApplied: Boolean): BridgeReply = if (isApplied) {
        BridgeReply.Ok(JsonObject(emptyMap()))
    } else {
        BridgeReply.Refused(BridgeErrorCode.NOT_APPLIED, "The studio did not apply the change")
    }

    private fun <T> decode(call: BridgeCall, serializer: KSerializer<T>): T =
        BridgeProtocol.json.decodeFromJsonElement(serializer, call.params)

    private fun String.checked(max: Int): String = also {
        require(it.isNotEmpty() && it.length <= max) { "Invalid value" }
    }

    private companion object {
        const val CHAT_CONFIGURE = "chat.configure"
        const val COMPOSER_EFFORT = "composer.effort"
        const val CHAT_EDIT = "chat.edit"
        const val MAX_ID_CHARS = 256
        const val MAX_MODEL_ID_CHARS = 1024
    }
}
