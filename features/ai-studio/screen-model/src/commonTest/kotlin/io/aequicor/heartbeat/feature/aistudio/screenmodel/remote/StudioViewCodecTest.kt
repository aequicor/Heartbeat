package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioViewCodecTest {
    private val at = Instant.parse("2026-10-13T09:20:00Z")
    private val encoder = StudioViewEncoder()
    private val mirror = StudioViewMirror()

    private fun prompt(id: String) = MessageUi.Prompt(id, at, "Вопрос $id")

    private fun reply(id: String, text: String, isStreaming: Boolean = false) =
        MessageUi.Reply(id, at, text, persistentListOf(), isStreaming)

    private fun screen(vararg feeds: Pair<String, List<MessageUi>>, draft: String = "") = AiStudioScreenState(
        phase = StudioPhase.Ready,
        sessions = persistentListOf(SessionUi("a", "A", null, at)),
        transcripts = feeds.associate { (key, messages) -> key to messages.toImmutableList() }.toImmutableMap(),
        drafts = persistentMapOf("a" to draft),
    )

    /** Sends [state] through the encoder, JSON and the mirror, as the bridge does; checks the mirrored screen. */
    private fun relay(state: AiStudioScreenState): Pair<StudioViewFrame, AiStudioScreenState> {
        val frame = checkNotNull(encoder.frame(state, ack = 0))
        val wire = StudioViewJson.encodeToString(StudioViewFrame.serializer(), frame)
        val received = StudioViewJson.decodeFromString(StudioViewFrame.serializer(), wire)
        val mirrored = mirror.apply(received)
        assertEquals(state, mirrored)
        return received to mirrored
    }

    @Test
    fun `the first frame is a snapshot and an unchanged screen sends nothing until the ack moves`() {
        val state = screen("a" to listOf(prompt("1"), reply("2", "Ответ")))

        assertIs<StudioViewFrame.Snapshot>(relay(state).first)
        assertNull(encoder.frame(state, ack = 0))
        assertEquals(1L, encoder.frame(state, ack = 1)?.ack)
    }

    @Test
    fun `a streaming reply sends only that message and the panel keeps the other instances`() {
        val (_, before) = relay(screen("a" to listOf(prompt("1"), reply("2", "Ду", isStreaming = true))))

        val (frame, after) = relay(screen("a" to listOf(prompt("1"), reply("2", "Думаю…", isStreaming = true))))

        val delta = assertIs<StudioViewFrame.Delta>(frame)
        val patch = assertIs<FeedPatch.Edit>(delta.feeds.getValue("t:a"))
        assertEquals(listOf("2"), patch.upserts.map { it.id })
        assertNull(patch.order)
        assertTrue(delta.shell.isEmpty())
        assertSame(before.transcripts.getValue("a")[0], after.transcripts.getValue("a")[0])
    }

    @Test
    fun `removals, reordering, new and gone feeds and shell changes reach the panel`() {
        val (_, first) = relay(screen("a" to listOf(prompt("1"), reply("2", "x")), "b" to listOf(prompt("3"))))
        relay(screen("a" to listOf(prompt("1"), prompt("4"), reply("2", "x")), "c" to listOf(prompt("5")), draft = "Ч"))

        val (frame, last) = relay(screen("a" to listOf(reply("2", "y"), prompt("1")), draft = "Ч"))

        val delta = assertIs<StudioViewFrame.Delta>(frame)
        assertEquals(setOf("t:a", "t:c"), delta.feeds.keys)
        assertEquals(FeedPatch.Gone, delta.feeds["t:c"])
        assertTrue(delta.shell.isEmpty())
        assertSame(first.transcripts.getValue("a")[0], last.transcripts.getValue("a")[1])
    }

    @Test
    fun `a delta for another revision asks for a snapshot, and reset sends one`() {
        relay(screen("a" to listOf(prompt("1"))))

        assertFailsWith<StudioViewGapException> { mirror.apply(StudioViewFrame.Delta(rev = 9, ack = 0, base = 8)) }
        encoder.reset()
        assertIs<StudioViewFrame.Snapshot>(relay(screen("a" to listOf(prompt("1"), prompt("2")))).first)
    }

    @Test
    fun `the schema fingerprint is stable and follows the shape of the types`() {
        assertEquals(StudioViewSchema.fingerprint, StudioViewSchema.fingerprint)
        assertTrue(Regex("[0-9a-f]{16}").matches(StudioViewSchema.fingerprint))
        assertNotEquals(fingerprintOf(Old.serializer().descriptor), fingerprintOf(New.serializer().descriptor))
    }

    @Test
    fun `the second generic map contributes its value shape`() {
        val first = MapSerializer(String.serializer(), String.serializer()).descriptor
        val old = MapSerializer(String.serializer(), Old.serializer()).descriptor
        val new = MapSerializer(String.serializer(), New.serializer()).descriptor
        assertNotEquals(fingerprintOf(first, old), fingerprintOf(first, new))
    }

    @Test
    fun `recursive schemas terminate deterministically`() {
        val descriptor = Recursive.serializer().descriptor
        assertEquals(fingerprintOf(descriptor), fingerprintOf(descriptor))
    }

    @Serializable
    private data class Recursive(val next: Recursive?)

    @Serializable
    @SerialName("Shape")
    private data class Old(val text: String)

    @Serializable
    @SerialName("Shape")
    private data class New(val body: String)
}
