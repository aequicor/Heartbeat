package io.aequicor.heartbeat.feature.autocomplete.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ComposerApplyTest {
    private val heartbeat = ComposerAssistOrigin.Heartbeat
    private val pi = ComposerAssistOrigin.Engine(EngineId("pi"))

    @Test
    fun `command suggestion replaces the token and moves the caret`() {
        val draft = applyComposerSuggestion(
            "/rem",
            composerTrigger("/rem", 4)!!,
            ComposerSuggestion.Command("remember", "/remember", "", heartbeat, "/remember "),
        )
        assertEquals("/remember ", draft.text)
        assertEquals(10, draft.caret)
        assertNull(draft.attach)
    }

    @Test
    fun `skill suggestion inserts the reference text`() {
        val draft = applyComposerSuggestion(
            "use @ver",
            composerTrigger("use @ver", 8)!!,
            ComposerSuggestion.Skill("verify", "verify", "checks", heartbeat, "verify "),
        )
        assertEquals("use verify ", draft.text)
        assertEquals(11, draft.caret)
    }

    @Test
    fun `engine command keeps the engine insertion`() {
        val draft = applyComposerSuggestion(
            "/pro",
            composerTrigger("/pro", 4)!!,
            ComposerSuggestion.Command("prompt", "prompt", "", pi, "/prompt "),
        )
        assertEquals("/prompt ", draft.text)
    }

    @Test
    fun `file suggestion completes the path and asks for an attachment`() {
        val draft = applyComposerSuggestion(
            "read @src/comm",
            composerTrigger("read @src/comm", 14)!!,
            ComposerSuggestion.File(
                id = "file",
                label = "src/common/Main.kt",
                description = "12 kB",
                origin = heartbeat,
                relativePath = "src/common/Main.kt",
                location = "C:\\project\\src\\common\\Main.kt",
                sizeBytes = 12_288,
                mediaType = "text/kotlin",
            ),
        )
        assertEquals("read @src/common/Main.kt ", draft.text)
        assertEquals(25, draft.caret)
        val attach = assertIs<ComposerSuggestion.File>(draft.attach)
        assertEquals("src/common/Main.kt", attach.relativePath)
    }

    @Test
    fun `text after the replaced token is preserved`() {
        val draft = applyComposerSuggestion(
            "@ver and more",
            composerTrigger("@ver and more", 4)!!,
            ComposerSuggestion.Skill("verify", "verify", "", heartbeat, "verify "),
        )
        assertEquals("verify  and more", draft.text)
    }
}
