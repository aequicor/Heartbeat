package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioLearningPromptsTest {
    @Test
    fun `remember asks the agent for its tool only while learning is on`() = runTest {
        val enabled = StudioLearningPrompts(LearningToggle(isEnabled = true), TestDispatchers)
        val sent = enabled.prompt("chat", "/remember Use UTF-8")
        assertTrue("Call the remember tool exactly once" in sent)
        assertFalse(sent.startsWith("/"))
        assertEquals("/remember Use UTF-8", stripHostDirectives(sent))
        assertEquals("Fix the build", enabled.prompt("chat", "Fix the build"))

        val disabled = StudioLearningPrompts(LearningToggle(isEnabled = false), TestDispatchers)
        assertEquals("/remember Use UTF-8", disabled.prompt("chat", "/remember Use UTF-8"))
    }

    @Test
    fun `frequent problems in command output of a finished turn become one hint in the next prompt`() = runTest {
        val learning = StudioLearningPrompts(LearningToggle(isEnabled = true), TestDispatchers)
        val items = listOf(SessionItem.Message(info(0), MessageRole.User, listOf(ContentPart.Text("old")))) +
            command(1, "a", "'grep' is not recognized") +
            SessionItem.Message(info(3), MessageRole.User, listOf(ContentPart.Text("Run the script"))) +
            command(4, "b", "UnicodeDecodeError: x")
        learning.observeTurn("chat") { items }

        assertEquals("Continue", learning.prompt("other", "Continue"))
        val hinted = learning.prompt("chat", "Continue")
        assertTrue("text encoding" in hinted)
        assertFalse("wrong shell" in hinted)
        assertEquals("Continue", stripHostDirectives(hinted))
        assertEquals("Continue", learning.prompt("chat", "Continue"))

        // The same problem is hinted once per chat.
        learning.observeTurn("chat") { items }
        assertEquals("Continue", learning.prompt("chat", "Continue"))
    }

    @Test
    fun `file contents, arguments and learning results are not environment errors`() = runTest {
        val learning = StudioLearningPrompts(LearningToggle(isEnabled = true), TestDispatchers)
        val items = listOf(
            SessionItem.Message(info(0), MessageRole.User, listOf(ContentPart.Text("Read it"))),
            SessionItem.ToolCall(info(1), ToolCallId("r"), "Read", "{}", ToolCallStatus.Succeeded),
            SessionItem.ToolResult(info(2), ToolCallId("r"), listOf(ContentPart.Text("except UnicodeDecodeError:"))),
            SessionItem.ToolCall(info(3), ToolCallId("e"), "edit_file", "UnicodeDecodeError", ToolCallStatus.Failed),
            SessionItem.ToolCall(info(4), ToolCallId("s"), "load_learned_skill", "{}", ToolCallStatus.Failed),
            SessionItem.ToolResult(
                info(5),
                ToolCallId("s"),
                listOf(ContentPart.Text("No skill; available: Fix UnicodeEncodeError")),
                failure = EngineFailure.Unknown(),
            ),
        )
        learning.observeTurn("chat") { items }
        assertEquals("Continue", learning.prompt("chat", "Continue"))
    }

    @Test
    fun `a hint waits while the user sends a CLI command and nothing is collected while learning is off`() = runTest {
        val learning = StudioLearningPrompts(LearningToggle(isEnabled = true), TestDispatchers)
        learning.observeTurn("chat") {
            listOf(SessionItem.Message(info(0), MessageRole.User, listOf(ContentPart.Text("Run")))) +
                command(1, "a", "UnicodeDecodeError")
        }
        assertEquals("/compact", learning.prompt("chat", "/compact"))
        assertFalse("text encoding" in learning.prompt("chat", "/remember Use UTF-8"))
        assertTrue("text encoding" in learning.prompt("chat", "Continue"))

        val disabled = StudioLearningPrompts(LearningToggle(isEnabled = false), TestDispatchers)
        disabled.observeTurn("chat") { error("not read while learning is off") }
        assertEquals("Continue", disabled.prompt("chat", "Continue"))
    }

    @Test
    fun `a failed read of the finished turn does not fail it`() = runTest {
        val learning = StudioLearningPrompts(LearningToggle(isEnabled = true), TestDispatchers)
        learning.observeTurn("chat") { error("history unavailable") }
        assertEquals("Continue", learning.prompt("chat", "Continue"))
    }

    private fun command(position: Long, call: String, output: String): List<SessionItem> = listOf(
        SessionItem.ToolCall(info(position), ToolCallId(call), "run_command", "{}", ToolCallStatus.Succeeded),
        SessionItem.ToolResult(info(position + 1), ToolCallId(call), listOf(ContentPart.Text(output))),
    )

    private fun info(position: Long) = ItemInfo(ItemId("i$position"), position, 0)

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class LearningToggle(private val isEnabled: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The fake answers only the learning flag.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)

        @Suppress("UNCHECKED_CAST") // The fake answers only the learning flag.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
            check(toggle == AgentLearningEnabled)
            return isEnabled as T
        }
    }
}
