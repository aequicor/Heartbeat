package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioViewSyncTest {
    private fun typed(text: String) = AiStudioScreenIntent.Suggestions.DraftChanged(paneId = 0, text = text)

    @Test
    fun `typed text stays on screen until a frame acknowledges it`() {
        val app = StudioViewEncoder()
        val sync = StudioViewSync()
        assertNull(sync.screen)
        sync.receive(checkNotNull(app.frame(AiStudioScreenState(), ack = 0)))

        sync.add(typed("he"))
        sync.add(typed("hel"))
        assertEquals("hel", sync.screen?.draft(0))
        assertEquals(listOf(1L, 2L), sync.unsent(limit = 64).map { it.seq })
        sync.sent(2)

        // The app has applied only the first keystroke so far: the second stays predicted.
        sync.receive(checkNotNull(app.frame(AiStudioScreenState().withDraft(0, "he"), ack = 1)))
        assertEquals("hel", sync.screen?.draft(0))
        sync.receive(checkNotNull(app.frame(AiStudioScreenState().withDraft(0, "hel"), ack = 2)))
        assertEquals("hel", sync.screen?.draft(0))
        assertEquals(emptyList(), sync.unsent(limit = 64))
    }

    @Test
    fun `a failed send and a snapshot send again what the app has not acknowledged`() {
        val app = StudioViewEncoder()
        val sync = StudioViewSync()
        sync.receive(checkNotNull(app.frame(AiStudioScreenState(), ack = 0)))
        sync.add(typed("a"))
        sync.add(typed("ab"))
        sync.add(typed("abc"))
        assertEquals(listOf(1L, 2L), sync.unsent(limit = 2).map { it.seq })
        sync.sent(3)
        assertEquals(emptyList(), sync.unsent(limit = 64))

        sync.resend()
        assertEquals(listOf(1L, 2L, 3L), sync.unsent(limit = 64).map { it.seq })
        sync.sent(3)

        // A new attachment starts with a snapshot that has applied the first intent only.
        app.reset()
        sync.restart()
        sync.receive(checkNotNull(app.frame(AiStudioScreenState().withDraft(0, "a"), ack = 1)))
        assertEquals(listOf(2L, 3L), sync.unsent(limit = 64).map { it.seq })
        assertEquals("abc", sync.screen?.draft(0))
    }
}
