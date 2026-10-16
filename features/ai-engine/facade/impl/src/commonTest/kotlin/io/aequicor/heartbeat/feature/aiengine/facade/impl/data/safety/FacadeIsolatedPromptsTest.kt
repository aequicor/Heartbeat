package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FacadeIsolatedPromptsTest {
    @Test
    fun `a prompt runs once in a fresh tool-less session and returns the final answer`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { answer = """{"choice":"c2"}""" }
        val answer = prompts(f).ask(IsolatedPrompt(f.target, "Pick one candidate"))
        assertEquals(IsolatedAnswer.Answered("""{"choice":"c2"}"""), answer)
        val request = f.creations.single()
        assertNull(request.workspace)
        assertFalse(request.areDetachedToolsEnabled)
        assertTrue(request.areSessionHooksEnabled)
        assertTrue(f.sawOffBeforeSend)
        val prompt = f.prompts.single()
        assertTrue(prompt.id.value.startsWith(ISOLATED_REQUEST_PREFIX))
        assertEquals("Pick one candidate", (prompt.parts.single() as ContentPart.Text).text)
        assertTrue(f.closed)
        assertTrue(f.archived)
        assertNull(f.policy.policy(ToolPolicyScope(session = f.ref)))
    }

    @Test
    fun `the answer is cut to the requested budget`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { answer = "a".repeat(100) }
        val answer = prompts(f).ask(IsolatedPrompt(f.target, "Summarise", maxAnswerChars = 50))
        val text = (answer as IsolatedAnswer.Answered).text
        assertEquals(50, text.length)
        assertTrue(text.startsWith("aaaa"))
    }

    @Test
    fun `a permission request is cancelled and never answered`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { asks = true }
        val answer = prompts(f).ask(IsolatedPrompt(f.target, "Pick"))
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Incomplete), answer)
        assertEquals(1, f.cancels)
        assertTrue(f.closed)
    }

    @Test
    fun `a provider failure is a value and frees the slot for the next prompt`() = runTest {
        val f = ReviewFixture(backgroundScope).apply { failsOnCreate = true }
        val prompts = prompts(f)
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Failed),
            prompts.ask(IsolatedPrompt(f.target, "Pick")),
        )
        f.failsOnCreate = false
        assertTrue(prompts.ask(IsolatedPrompt(f.target, "Pick")) is IsolatedAnswer.Answered)
    }

    @Test
    fun `a turn that never ends times out and keeps its restrictions until it settles`() = runTest {
        val f = ReviewFixture(backgroundScope).apply {
            outcome = null
            ignoresCancel = true
        }
        val answer = prompts(f).ask(IsolatedPrompt(f.target, "Pick", timeout = 5.seconds))
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout), answer)
        assertTrue(f.policy.policy(ToolPolicyScope(session = f.ref)) != null)
    }

    private fun TestScope.prompts(f: ReviewFixture) = FacadeIsolatedPrompts(
        lazyOf(f.facade),
        f.policy,
        backgroundScope,
        AgentSafetyReviewSlots(capacity = 1, queueWait = 10.seconds),
        1.minutes,
    )
}
