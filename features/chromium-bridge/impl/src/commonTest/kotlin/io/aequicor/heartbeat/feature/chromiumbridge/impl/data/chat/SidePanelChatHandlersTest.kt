package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatList
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatModel
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatProject
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatRejection
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSummary
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.agent.ChromiumExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.ChatWireLimits
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.WirePage
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SidePanelChatHandlersTest {
    private val chats = FakeChats()
    private val imported = mutableListOf<List<WirePage>>()
    private var importResult: PageImport = PageImport.Imported(listOf(ResourceRef("attachment:1", "text/markdown")))
    private val handlers = SidePanelChatHandlers(
        chats,
        PageImporter { pages ->
            imported += pages
            importResult
        },
        lazy { error("Unused browser") },
    )
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    @Test
    fun `the chat list is sent at once and then throttled to the latest change`() = runTest {
        val session = FakeSession(this)
        chats.list.value = list("first")

        val reply = call(session, "chats.watch") as BridgeReply.Ok
        runCurrent()
        chats.list.value = list("second")
        chats.list.value = list("third")
        runCurrent()
        advanceTimeBy(300)
        runCurrent()

        val watch = reply.result.jsonObject["watch"]?.jsonPrimitive?.content
        val titles = session.emitted.map { (_, params) ->
            params["chats"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("first", "third"), titles)
        val first = session.emitted.first().second
        assertEquals(watch, first["watch"]?.jsonPrimitive?.content)
        assertEquals("p1", first["projects"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val updatedAt = first["chats"]!!.jsonArray.single().jsonObject["updatedAt"]!!.jsonPrimitive.content
        assertEquals(1_700_000_000_000, updatedAt.toLong())
    }

    @Test
    fun `the list carries chat settings, archived chats last and the composer`() = runTest {
        val session = FakeSession(this)
        val settings = StudioChatSettings("m1", "high", ApprovalMode.AutoEdits)
        chats.list.value = StudioChatList(
            chats = listOf(
                StudioChatSummary("old", "Old", null, at, false, false, false, true, isArchived = true),
                StudioChatSummary("c1", "Live", null, at, false, false, false, true, settings = settings),
            ),
        )
        chats.composer.value = StudioChatComposer(
            models = listOf(StudioChatModel("m1", "Model One", "One", listOf("low", "high"), "high")),
            newChat = StudioChatSettings("m1"),
        )

        call(session, "chats.watch")
        runCurrent()

        val params = session.emitted.single().second
        val listed = params["chats"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("c1", "old"), listed.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("auto_edits", listed.first()["settings"]!!.jsonObject["approval"]!!.jsonPrimitive.content)
        assertEquals(true, listed.last()["archived"]!!.jsonPrimitive.boolean)
        val composer = params["composer"]!!.jsonObject
        assertEquals(
            "high",
            composer["models"]!!.jsonArray.single().jsonObject["newChatEffort"]!!.jsonPrimitive.content,
        )
        assertEquals("ask", composer["newChat"]!!.jsonObject["approval"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a transcript watch resets first and then sends only changes`() = runTest {
        val session = FakeSession(this)
        val transcript = MutableStateFlow(listOf<StudioChatEntry>(StudioChatEntry.Prompt("m1", at, "Hi", 0)))
        chats.transcripts["c1"] = transcript

        call(session, "chat.watch", buildJsonObject { put("chatId", "c1") })
        runCurrent()
        transcript.value = transcript.value + StudioChatEntry.Reply(
            "m2",
            at,
            listOf(StudioChatPart.Text("Hello")),
            true,
        )
        advanceTimeBy(200)
        runCurrent()

        val payloads = session.emitted.map { it.second }
        val reset = payloads[0]
        val change = payloads[1]
        assertEquals(true, reset["reset"]?.jsonPrimitive?.boolean)
        assertEquals("prompt", reset["upserts"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, change["reset"]?.jsonPrimitive?.boolean)
        val upsert = change["upserts"]!!.jsonArray.single().jsonObject
        assertEquals("m2", upsert["id"]?.jsonPrimitive?.content)
        assertEquals("Hello", upsert["parts"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `oversized history reset and replacement delta stay within receiver cap`() = runTest {
        val session = FakeSession(this)
        val text = "Я".repeat(ChatWireLimits.MAX_TEXT_CHARS)
        fun entries(prefix: String): List<StudioChatEntry> = (1..ChatWireLimits.MAX_ENTRIES).map {
            StudioChatEntry.Prompt("$prefix-$it", at, text, 0)
        }
        val transcript = MutableStateFlow(entries("old"))
        chats.transcripts["c1"] = transcript

        call(session, "chat.watch", buildJsonObject { put("chatId", "c1") })
        runCurrent()
        transcript.value = entries("new")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(2, session.emitted.size)
        session.emitted.forEach { (event, params) ->
            assertTrue(encodeFrame(BridgeFrame.Event(event, params)).size < BridgeProtocol.MAX_FROM_BROWSER_BYTES)
            assertTrue(params["olderOmitted"]!!.jsonPrimitive.content.toInt() > 0)
        }
        val latest = session.emitted.last().second
        assertTrue(latest["removed"]!!.jsonArray.isNotEmpty())
        assertEquals("new-200", latest["upserts"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cancelled watches stop and a connection holds at most eight`() = runTest {
        val session = FakeSession(this)
        val watches = (1..8).map {
            (call(session, "chats.watch") as BridgeReply.Ok).result.jsonObject["watch"]!!.jsonPrimitive.content
        }

        assertEquals(BridgeErrorCode.BUSY, (call(session, "chats.watch") as BridgeReply.Refused).code)
        call(session, "watch.cancel", buildJsonObject { put("watch", watches.first()) })
        runCurrent()
        assertEquals(BridgeReply.Ok::class, call(session, "chats.watch")::class)
    }

    @Test
    fun `sending maps the studio result to the wire`() = runTest {
        val session = FakeSession(this)
        chats.sendResult = StudioChatSendResult.Accepted("c9")

        val accepted = call(session, "chat.send", buildJsonObject { put("text", "Hi") }) as BridgeReply.Ok
        chats.sendResult = StudioChatSendResult.Rejected(StudioChatRejection.Busy)
        val again = buildJsonObject {
            put("chatId", "c9")
            put("text", "Again")
        }
        val busy = call(session, "chat.send", again) as BridgeReply.Refused

        assertEquals("c9", accepted.result.jsonObject["chatId"]?.jsonPrimitive?.content)
        assertEquals(BridgeErrorCode.BUSY, busy.code)
        assertEquals(
            mapOf("external_browser" to setOf(session.id.toString())),
            chats.sent.first().environment?.preselected,
        )
        val page = ResourceRef("attachment:1", "text/markdown")
        assertEquals(
            listOf(StudioChatSend(null, null, "Hi", listOf(page)), StudioChatSend("c9", null, "Again", listOf(page))),
            chats.sent.map { it.copy(environment = null) },
        )
        assertFailsWith<IllegalArgumentException> { call(session, "chat.send", buildJsonObject { put("text", " ") }) }
    }

    @Test
    fun `a new chat takes the panel's settings and binds its browser environment`() = runTest {
        val session = FakeSession(this)
        val browser = ChromiumExternalBrowser(MutableStateFlow(listOf(session)), backgroundScope)
        val boundHandlers = SidePanelChatHandlers(chats, PageImporter { importResult }, lazyOf(browser))
        chats.sendResult = StudioChatSendResult.Accepted("c2")
        fun send(approval: String) = buildJsonObject {
            put("text", "Hi")
            putJsonObject("settings") {
                put("modelId", "m1")
                put("approval", approval)
            }
        }

        boundHandlers.handle(BridgeCall("chat.send", send("auto_approve"), session))

        val sent = chats.sent.single()
        assertEquals(StudioChatSettings("m1", null, ApprovalMode.AutoApprove), sent.settings)
        val environment = checkNotNull(sent.environment)
        assertEquals(mapOf("external_browser" to setOf(session.id.toString())), environment.preselected)
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "chat-session")
        val owner = Json.encodeToString(ref)
        val lease = environment.bind(ref)
        assertTrue(browser.isExternalSession(owner))
        assertTrue(browser.isTabInSession(owner, "${session.id}:1"))
        assertFalse(browser.isTabInSession(owner, "other:1"))
        lease.close()
        assertFalse(browser.isExternalSession(owner))
        assertFailsWith<IllegalArgumentException> { call(session, "chat.send", send("sometimes")) }
    }

    @Test
    fun `captured pages are imported before the prompt and refused imports stop it`() = runTest {
        val session = FakeSession(this)
        chats.sendResult = StudioChatSendResult.Accepted("c1")
        val page = buildJsonObject {
            put("kind", "page")
            put("url", "https://example.com/a")
            put("title", "A")
            put("text", "Body")
        }
        val params = buildJsonObject {
            put("text", "Summarize")
            put("attachments", JsonArray(listOf(page)))
        }

        call(session, "chat.send", params)
        importResult = PageImport.Refused
        val refused = call(session, "chat.send", params) as BridgeReply.Refused

        assertEquals(listOf(WirePage(url = "https://example.com/a", title = "A", text = "Body")), imported.first())
        assertEquals(BridgeErrorCode.BUSY, refused.code)
        assertEquals(1, chats.sent.size)
        val unsafePage = buildJsonObject { put("url", "file:///etc/passwd") }
        val unsafe = buildJsonObject {
            put("text", "x")
            put("attachments", JsonArray(listOf(unsafePage)))
        }
        assertFailsWith<IllegalArgumentException> { call(session, "chat.send", unsafe) }
    }

    @Test
    fun `stop and permission answers report what happened`() = runTest {
        val session = FakeSession(this)

        val stopped = call(session, "chat.stop", buildJsonObject { put("chatId", "c1") }) as BridgeReply.Ok
        val live = buildJsonObject {
            put("chatId", "c1")
            put("requestId", "r1")
            put("optionId", "allow")
            putJsonObject("answer") { put("text", "yes") }
        }
        val gone = buildJsonObject {
            put("chatId", "c1")
            put("requestId", "gone")
            put("optionId", "allow")
        }
        val answered = call(session, "permission.respond", live)
        val stale = call(session, "permission.respond", gone) as BridgeReply.Refused

        assertEquals(JsonPrimitive(true), stopped.result.jsonObject["stopped"])
        assertEquals(BridgeReply.Ok(JsonObject(emptyMap())), answered)
        assertEquals(BridgeErrorCode.STALE_REQUEST, stale.code)
        assertEquals(StudioPermissionAnswer.Text("yes"), chats.answers.single())
    }

    private suspend fun call(session: BridgeSession, method: String, params: JsonObject = JsonObject(emptyMap())) =
        handlers.handle(BridgeCall(method, params, session))

    private fun list(title: String) = StudioChatList(
        projects = listOf(StudioChatProject("p1", "Heartbeat")),
        chats = listOf(StudioChatSummary("c1", title, "p1", at, false, false, false, true)),
    )

    private class FakeChats : StudioChats {
        val list = MutableStateFlow(StudioChatList())
        val composer = MutableStateFlow(StudioChatComposer())
        val transcripts = mutableMapOf<String, Flow<List<StudioChatEntry>>>()
        val sent = mutableListOf<StudioChatSend>()
        val answers = mutableListOf<StudioPermissionAnswer?>()
        var sendResult: StudioChatSendResult = StudioChatSendResult.Rejected(StudioChatRejection.Unavailable)

        override val availability: Flow<Boolean> = flowOf(true)

        override fun observeList(): Flow<StudioChatList> = list

        override fun observeComposer(): Flow<StudioChatComposer> = composer

        override fun observeTranscript(chatId: String): Flow<List<StudioChatEntry>> =
            transcripts[chatId] ?: flowOf(emptyList())

        override suspend fun send(request: StudioChatSend): StudioChatSendResult = sendResult.also { sent += request }

        override suspend fun stop(chatId: String): Boolean = chatId == "c1"

        override suspend fun respond(
            chatId: String,
            requestId: String,
            optionId: String,
            answer: StudioPermissionAnswer?,
        ): Boolean = (requestId == "r1").also { if (it) answers += answer }

        override suspend fun configure(chatId: String, change: StudioSettingChange): Boolean = false

        override suspend fun chooseNewChatEffort(modelId: String, effort: String?): Boolean = false

        override suspend fun edit(chatId: String, edit: SessionEdit): Boolean = false
    }

    private class FakeSession(test: TestScope) : BridgeSession {
        val emitted = mutableListOf<Pair<String, JsonObject>>()
        override val id: Long = 1
        override val origin: String = "chrome-extension://test/"
        override val client: StateFlow<BridgeClient?> =
            MutableStateFlow(BridgeClient(origin, "0.1.0", "Chrome", "140", setOf("chat")))
        override val events: SharedFlow<BridgeIncomingEvent> = MutableSharedFlow()
        override val scope: CoroutineScope = test.backgroundScope

        override fun accept(client: BridgeClient) = Unit

        override suspend fun emit(event: String, params: JsonObject) {
            emitted += event to params
        }

        override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement =
            JsonArray(emptyList())
    }
}
