package io.aequicor.heartbeat.feature.checklist.screenmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class ChecklistTextDraftTest {
    @Test
    fun `late acknowledgements keep newer typing`() {
        var draft = ChecklistTextDraft("")
        "the quick".forEachIndexed { index, _ -> draft = draft.edited("the quick".take(index + 1)) }
        draft = draft.published("th")
        assertEquals("the quick", draft.text)
        draft = draft.published("the q")
        assertEquals("the quick", draft.text)
        draft = draft.published("the quick")
        assertEquals("the quick", draft.text)
        assertEquals(emptyList(), draft.pending)
    }

    @Test
    fun `stale echoes of acknowledged drafts are ignored`() {
        var draft = ChecklistTextDraft("").edited("e").edited("e2").edited("e2e")
        draft = draft.published("e2e")
        draft = draft.published("e")
        assertEquals("e2e", draft.text)
        draft = draft.published("")
        assertEquals("e2e", draft.text)
    }

    @Test
    fun `owner change replaces the text only when no edit is in flight`() {
        var draft = ChecklistTextDraft("old").edited("old!")
        draft = draft.published("restored")
        assertEquals("old!", draft.text)
        draft = draft.published("old!")
        assertEquals("old!", draft.text)
        draft = draft.published("restored elsewhere")
        assertEquals("restored elsewhere", draft.text)
    }

    @Test
    fun `conflated publication acknowledges every older edit`() {
        var draft = ChecklistTextDraft("").edited("a").edited("ab").edited("a")
        draft = draft.published("ab")
        assertEquals(listOf("a"), draft.pending)
        draft = draft.published("a")
        assertEquals("a", draft.text)
        assertEquals(emptyList(), draft.pending)
    }
}
