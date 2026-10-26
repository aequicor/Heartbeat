package io.aequicor.heartbeat.feature.checklist.screenui

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
    fun `answered draft adopts another editor's value equal to an older own edit`() {
        var draft = ChecklistTextDraft("").edited("h").edited("he").edited("hel")
        draft = draft.published("hel")
        draft = draft.published("he")
        assertEquals("he", draft.text)
        draft = draft.published("")
        assertEquals("", draft.text)
    }

    @Test
    fun `edit returning to the published text needs no answer`() {
        var draft = ChecklistTextDraft("a").edited("ab").edited("a")
        assertEquals(emptyList(), draft.pending)
        // The owner may still publish the undone edit in order; it does not resurrect it.
        draft = draft.published("ab")
        assertEquals("a", draft.text)
        draft = draft.published("a")
        assertEquals("a", draft.text)
        // Nothing is in flight any more, so another editor's change is adopted.
        draft = draft.published("abc")
        assertEquals("abc", draft.text)
    }

    @Test
    fun `conflated publication acknowledges every older edit`() {
        var draft = ChecklistTextDraft("").edited("a").edited("ab").edited("abc")
        draft = draft.published("abc")
        assertEquals("abc", draft.text)
        assertEquals(emptyList(), draft.pending)
        assertEquals(emptyList(), draft.echoes)
    }

    @Test
    fun `equal edits keep the values between them while the owner may still publish them`() {
        var draft = ChecklistTextDraft("").edited("a").edited("ab").edited("a")
        // The owner answered the first "a"; "ab" and the second "a" follow in order.
        draft = draft.published("a")
        assertEquals(emptyList(), draft.pending)
        draft = draft.published("ab")
        assertEquals("a", draft.text)
        draft = draft.published("a")
        assertEquals("a", draft.text)
        assertEquals(emptyList(), draft.echoes)
    }

    @Test
    fun `edit applied before reconciliation survives it`() {
        val typed = ChecklistTextDraft("").edited("a").edited("ab")
        // The editor's effect records an undo before the field reconciles the owner's late answer.
        val draft = typed.edited("a").published("ab")
        assertEquals("a", draft.text)
        assertEquals(listOf("a"), draft.pending)
    }
}
