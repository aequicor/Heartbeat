package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioLearningPromptsTest {
    @Test
    fun `remember asks the agent for its tool only while learning is on`() = runTest {
        val enabled = StudioLearningPrompts(LearningToggle(isEnabled = true))
        val sent = enabled.prompt("/remember Use UTF-8")
        assertTrue("Call the remember tool exactly once" in sent)
        assertEquals("/remember Use UTF-8", stripHostDirectives(sent))
        assertEquals("Fix the build", enabled.prompt("Fix the build"))

        val disabled = StudioLearningPrompts(LearningToggle(isEnabled = false))
        assertEquals("/remember Use UTF-8", disabled.prompt("/remember Use UTF-8"))
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
