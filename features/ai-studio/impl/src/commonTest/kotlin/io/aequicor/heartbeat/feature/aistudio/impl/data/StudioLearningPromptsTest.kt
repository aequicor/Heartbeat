package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
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
        val enabled = StudioLearningPrompts(LearningToggle(isEnabled = true))
        val sent = enabled.prompt("chat", "/remember Use UTF-8")
        assertTrue("Call the remember tool exactly once" in sent)
        assertFalse(sent.startsWith("/"))
        assertEquals("/remember Use UTF-8", stripHostDirectives(sent))
        assertEquals("Fix the build", enabled.prompt("chat", "Fix the build"))

        val disabled = StudioLearningPrompts(LearningToggle(isEnabled = false))
        assertEquals("/remember Use UTF-8", disabled.prompt("chat", "/remember Use UTF-8"))
    }

    @Test
    fun `frequent tool problems of a finished turn become one hint in the next prompt`() = runTest {
        val learning = StudioLearningPrompts(LearningToggle(isEnabled = true))
        val items = listOf(
            SessionItem.Message(info(0), MessageRole.User, listOf(ContentPart.Text("old"))),
            SessionItem.ToolResult(info(1), ToolCallId("a"), listOf(ContentPart.Text("'grep' is not recognized"))),
            SessionItem.Message(info(2), MessageRole.User, listOf(ContentPart.Text("Run the script"))),
            SessionItem.ToolResult(info(3), ToolCallId("b"), listOf(ContentPart.Text("UnicodeDecodeError: x"))),
        )
        learning.observeTurn("chat") { items }

        assertEquals("Continue", learning.prompt("other", "Continue"))
        val hinted = learning.prompt("chat", "Continue")
        assertTrue("text encoding" in hinted)
        assertFalse("wrong shell" in hinted)
        assertEquals("Continue", stripHostDirectives(hinted))
        assertEquals("Continue", learning.prompt("chat", "Continue"))
    }

    private fun info(position: Long) = ItemInfo(ItemId("i$position"), position, 0)

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
