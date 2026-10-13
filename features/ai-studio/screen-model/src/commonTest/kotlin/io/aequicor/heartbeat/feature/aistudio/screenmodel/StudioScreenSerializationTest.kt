package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StudioScreenSerializationTest {
    @Test
    fun `child intent wire names survive the shared package move`() {
        val prefix = "io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent."
        assertEquals(prefix + "Embed", AiStudioScreenIntent.Embed.serializer().descriptor.serialName)
        assertEquals(prefix + "QuestionInput", AiStudioScreenIntent.QuestionInput.serializer().descriptor.serialName)
        assertEquals(prefix + "ChecklistInput", AiStudioScreenIntent.ChecklistInput.serializer().descriptor.serialName)
    }

    private val at = Instant.parse("2026-10-13T09:20:30.123456789Z")

    private val tool = ToolUi("t1", "git status", ToolStatusUi.Done, "clean\n", diff = "+a\n-b")

    private val state = AiStudioScreenState(
        phase = StudioPhase.Ready,
        appUpdate = AppUpdateUi("1.4.0", AppUpdateStageUi.Downloading, 25, AppUpdateFailureUi.Network),
        projects = persistentListOf(ProjectUi("p1", "Heartbeat", EnvironmentUi.Local, "main")),
        sessions = persistentListOf(SessionUi("s1", "Перенос панели ✅", "p1", at, isRunning = true)),
        panes = persistentListOf(PaneUi(1, sessionId = "s1", projectId = "p1")),
        focusedPaneId = 1,
        running = persistentSetOf("s1"),
        runStartedAt = persistentMapOf("s1" to at),
        transcripts = persistentMapOf(
            "s1" to persistentListOf(
                MessageUi.Prompt("m1", at, "Сравни\nварианты"),
                MessageUi.Reply(
                    "m2",
                    at,
                    "Итог",
                    persistentListOf(tool),
                    isStreaming = true,
                    cardIds = persistentListOf("card-1", "card-2"),
                    parts = persistentListOf(
                        ReplyPartUi.Reasoning("r1", "думаю"),
                        ReplyPartUi.Tool(tool),
                        ReplyPartUi.CollapsedTools("c1", 3, isRunning = false),
                        ReplyPartUi.Text("x1", "**Готово** 🚀"),
                    ),
                ),
                MessageUi.Stopped("m3", at, 42.seconds),
                MessageUi.ContextNotice("m4", at, isRollover = true),
                MessageUi.Failed("m5", at),
            ),
        ),
        drafts = persistentMapOf("s1" to "черновик"),
        diagramFocusRequests = persistentMapOf(1 to 2L),
        attachmentPreviews = persistentMapOf("a1" to AttachmentPreviewUi(imageBytes = byteArrayOf(0, 1, -1, 127))),
        now = at,
    )

    @Test
    fun `the screen state survives a JSON round trip`() {
        val json = StudioViewJson.encodeToString(AiStudioScreenState.serializer(), state)
        val decoded = StudioViewJson.decodeFromString(AiStudioScreenState.serializer(), json)

        assertEquals(state.copy(attachmentPreviews = decoded.attachmentPreviews), decoded)
        assertContentEquals(
            state.attachmentPreviews.getValue("a1").imageBytes,
            decoded.attachmentPreviews.getValue("a1").imageBytes,
        )
    }

    @Test
    fun `intents survive a JSON round trip as the sealed hierarchy`() {
        val intents = listOf(
            AiStudioScreenIntent.Retry,
            AiStudioScreenIntent.InstallAppUpdate,
            AiStudioScreenIntent.OpenSession("s1"),
            AiStudioScreenIntent.Submit(1),
            AiStudioScreenIntent.RespondPermission("s1", "r1", "allow"),
            AiStudioScreenIntent.ToggleSearch,
        )
        for (intent in intents) {
            val json = StudioViewJson.encodeToString(AiStudioScreenIntent.serializer(), intent)

            assertEquals(intent, StudioViewJson.decodeFromString(AiStudioScreenIntent.serializer(), json))
        }
    }
}
