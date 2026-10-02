package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_DIRECTIVE
import io.aequicor.heartbeat.feature.aistudio.impl.domain.rememberText

/**
 * Self-learning additions to a submitted prompt: a `/remember` command gains the host directive asking the agent to
 * call the remember tool. While learning is off the prompt is sent as typed.
 */
@Inject
internal class StudioLearningPrompts(private val toggles: FeatureToggles) {
    private val log = Log.tag("StudioLearningPrompts")

    /** The prompt the engine receives for [prompt] typed by the user. */
    suspend fun prompt(prompt: String): String {
        if (!toggles.get(AgentLearningEnabled)) return prompt
        val directives = listOfNotNull(REMEMBER_DIRECTIVE.takeIf { rememberText(prompt) != null })
        if (directives.isNotEmpty()) log.i { "Remember command sent with its directive" }
        return withHostDirectives(prompt, directives)
    }
}
