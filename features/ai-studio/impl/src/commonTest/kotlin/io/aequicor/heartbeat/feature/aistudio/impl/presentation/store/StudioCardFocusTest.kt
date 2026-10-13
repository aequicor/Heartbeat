package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioPane
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaceCardFocus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfacePlace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioCardFocusTest {
    private val native = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")
    private val now = Instant.fromEpochMilliseconds(0)
    private val workspace = StudioWorkspace(
        emptyList(),
        listOf(StudioSession("chat", "project", "Chat", now, nativeSession = native)),
    )
    private val request = StudioSurfaceCardFocus(
        0,
        "second",
        StudioSurfacePlace(native, WorkspaceRef("project")),
    )
    private val machine = AiStudioState.Ready(
        panes = listOf(StudioPane(0, sessionId = "chat")),
        focusedPaneId = 0,
        settings = DefaultRunSettings,
        defaultProjectId = "project",
    )
    private val state = AiStudioScreenState().reflectMachine(machine).copy(
        transcripts = persistentMapOf(
            "chat" to persistentListOf(
                MessageUi.Reply(
                    "reply",
                    now,
                    "",
                    persistentListOf(),
                    false,
                    cardIds = persistentListOf("first", "second"),
                ),
            ),
        ),
    )

    @Test
    fun `focus identifies the exact card in a reply and repeated clicks get a new sequence`() {
        val focused = state.withCardFocus(request, workspace, 1)
        assertEquals(CardFocusRequestUi("chat", "reply", "second", 1), focused.cardFocusRequests[0])
        assertEquals(2L, focused.withCardFocus(request, workspace, 2).cardFocusRequests[0]?.sequence)
    }

    @Test
    fun `missing cards and delayed outputs for another chat or project are ignored`() {
        assertEquals(state, state.withCardFocus(request.copy(cardId = "missing"), workspace, 1))
        assertEquals(state, state.withCardFocus(request.copy(paneId = 1), workspace, 1))
        val previousChat = request.copy(place = request.place.copy(chat = native.copy(nativeId = "old")))
        assertEquals(state, state.withCardFocus(previousChat, workspace, 1))
        val previousProject = request.copy(place = request.place.copy(workspace = WorkspaceRef("old")))
        assertEquals(state, state.withCardFocus(previousProject, workspace, 1))
    }

    @Test
    fun `pending request survives an unchanged hidden pane but is discarded when that pane changes chats`() {
        val focused = state.withCardFocus(request, workspace, 1)
        assertEquals(focused.cardFocusRequests, focused.reflectMachine(machine).cardFocusRequests)
        val switched = machine.copy(panes = listOf(StudioPane(0, sessionId = "other")))
        assertTrue(focused.reflectMachine(switched).cardFocusRequests.isEmpty())
        assertTrue(focused.reflectMachine(switched).reflectMachine(machine).cardFocusRequests.isEmpty())
    }
}
