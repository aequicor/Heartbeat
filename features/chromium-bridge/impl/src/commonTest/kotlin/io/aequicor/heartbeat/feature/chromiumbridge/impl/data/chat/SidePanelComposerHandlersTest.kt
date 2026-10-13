package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeClient
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SidePanelComposerHandlersTest {
    private val chats = RecordingChats()
    private val handlers = SidePanelComposerHandlers(chats)

    @Test
    fun `chat settings reach the studio as its own changes`() = runTest {
        call("chat.configure", "chatId" to "c1", "kind" to "model", "value" to "m2")
        call("chat.configure", "chatId" to "c1", "kind" to "effort", "value" to null)
        call("chat.configure", "chatId" to "c1", "kind" to "approval", "value" to "auto_edits")

        assertEquals<List<StudioSettingChange>>(
            listOf(
                StudioSettingChange.Model("m2"),
                StudioSettingChange.Effort(null),
                StudioSettingChange.Approval(ApprovalMode.AutoEdits),
            ),
            chats.changes,
        )
        assertFailsWith<IllegalArgumentException> {
            call("chat.configure", "chatId" to "c1", "kind" to "approval", "value" to "maybe")
        }
    }

    @Test
    fun `edits and the new-chat effort map to the studio and refusals say not applied`() = runTest {
        call("chat.edit", "chatId" to "c1", "kind" to "rename", "title" to "Plan")
        call("chat.edit", "chatId" to "c1", "kind" to "archive", "value" to true)
        call("composer.effort", "modelId" to "m1", "effort" to "high")
        chats.isApplied = false
        val refused = call("chat.edit", "chatId" to "c1", "kind" to "pin", "value" to true) as BridgeReply.Refused

        assertEquals<List<SessionEdit>>(
            listOf(SessionEdit.Rename("Plan"), SessionEdit.SetArchived(true), SessionEdit.SetPinned(true)),
            chats.edits,
        )
        assertEquals<List<Pair<String, String?>>>(listOf("m1" to "high"), chats.efforts)
        assertEquals(BridgeErrorCode.NOT_APPLIED, refused.code)
        assertFailsWith<IllegalArgumentException> { call("chat.edit", "chatId" to "c1", "kind" to "rename") }
    }

    private suspend fun call(method: String, vararg params: Pair<String, Any?>): BridgeReply {
        val json = buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonNull)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
        return handlers.handle(BridgeCall(method, json, Session))
    }

    private class RecordingChats : StudioChats {
        val changes = mutableListOf<StudioSettingChange>()
        val edits = mutableListOf<SessionEdit>()
        val efforts = mutableListOf<Pair<String, String?>>()
        var isApplied = true

        override val availability: Flow<Boolean> = emptyFlow()

        override fun observeList(): Flow<StudioChatList> = emptyFlow()

        override fun observeComposer(): Flow<StudioChatComposer> = emptyFlow()

        override fun observeTranscript(chatId: String): Flow<List<StudioChatEntry>> = emptyFlow()

        override suspend fun send(request: StudioChatSend): StudioChatSendResult = error("Unused")

        override suspend fun stop(chatId: String): Boolean = error("Unused")

        override suspend fun respond(
            chatId: String,
            requestId: String,
            optionId: String,
            answer: StudioPermissionAnswer?,
        ): Boolean = error("Unused")

        override suspend fun configure(chatId: String, change: StudioSettingChange): Boolean =
            isApplied.also { changes += change }

        override suspend fun chooseNewChatEffort(modelId: String, effort: String?): Boolean =
            isApplied.also { efforts += modelId to effort }

        override suspend fun edit(chatId: String, edit: SessionEdit): Boolean = isApplied.also { edits += edit }
    }

    private object Session : BridgeSession {
        override val id: Long = 1
        override val origin: String = "chrome-extension://test/"
        override val client: StateFlow<BridgeClient?> = MutableStateFlow(null)
        override val events: SharedFlow<BridgeIncomingEvent> = MutableSharedFlow()
        override val scope: CoroutineScope get() = error("Unused")

        override fun accept(client: BridgeClient) = Unit

        override suspend fun emit(event: String, params: JsonObject) = Unit

        override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement =
            JsonArray(emptyList())
    }
}
