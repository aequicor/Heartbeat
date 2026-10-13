package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewEncoder
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent as StudioIntent

class StudioViewSyncTest {
    private fun typed(text: String) = StudioIntent.Suggestions.DraftChanged(paneId = 0, text = text)

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

    @Test
    fun `a recreated view does not replay old actions and retains interrupted drafts`() {
        val sync = StudioViewSync()
        val old = StudioViewEncoder(generation = "old")
        sync.receive(checkNotNull(old.frame(AiStudioScreenState(), 0)))
        sync.add(typed("unsent text"))
        sync.add(StudioIntent.Submit(0))
        sync.sent(2)
        sync.restart()
        assertEquals(emptyList(), sync.unsent(64))

        val fresh = StudioViewEncoder(generation = "new")
        sync.receive(checkNotNull(fresh.frame(AiStudioScreenState(), 0)))

        assertEquals(emptyList(), sync.unsent(64))
        assertEquals("unsent text", sync.interruptedScreen?.draft(0))
        sync.add(typed("new input"))
        assertEquals(listOf(1L), sync.unsent(64).map { it.seq })
    }

    @Test
    fun `intents wait for a snapshot and numbering continues after its acknowledgement`() {
        val sync = StudioViewSync()
        sync.add(typed("before snapshot"))
        assertEquals(emptyList(), sync.unsent(64))
        val app = StudioViewEncoder()
        sync.receive(checkNotNull(app.frame(AiStudioScreenState(), 10)))
        sync.add(typed("after snapshot"))
        assertEquals(listOf(11L), sync.unsent(64).map { it.seq })
    }
}
