package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningSignal
import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_DIRECTIVE
import io.aequicor.heartbeat.feature.aistudio.impl.domain.detectLearningSignals
import io.aequicor.heartbeat.feature.aistudio.impl.domain.learningHint
import io.aequicor.heartbeat.feature.aistudio.impl.domain.rememberText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Self-learning additions to a submitted prompt. A `/remember` command gains the host directive asking the agent to
 * call the remember tool; frequent environment problems noticed in a finished turn's tool output become a hint in
 * the next prompt of the same chat. Hints live in memory only and are consumed by that prompt. While learning is
 * off the prompt is sent as typed and nothing is collected.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class StudioLearningPrompts(private val toggles: FeatureToggles) {
    private val log = Log.tag("StudioLearningPrompts")
    private val lock = Mutex()
    private val signals = mutableMapOf<String, Set<LearningSignal>>()

    /** The prompt the engine receives for [prompt] typed by the user in chat [id]. */
    suspend fun prompt(id: String, prompt: String): String {
        val pending = lock.withLock { signals.remove(id).orEmpty() }
        if (!toggles.get(AgentLearningEnabled)) return prompt
        val isRemember = rememberText(prompt) != null
        val hint = pending.takeIf { it.isNotEmpty() }?.let(::learningHint)
        if (isRemember || hint != null) {
            log.i { "Learning directives sent: remember=$isRemember, signals ${pending.size}" }
        }
        val text = withHostDirectives(prompt, listOfNotNull(hint))
        // The /remember directive leads: a CLI never reads the prompt as its own slash command.
        return if (isRemember) hostDirective(REMEMBER_DIRECTIVE) + "\n\n" + text else text
    }

    /**
     * Collects problem signatures from the tool output of the last turn of chat [id] read by [items]. A failed read
     * only loses the hint: it never fails the finished turn.
     */
    suspend fun observeTurn(id: String, items: suspend () -> List<SessionItem>) {
        try {
            if (!toggles.get(AgentLearningEnabled)) return
            val found = items().lastTurn().flatMap { detectLearningSignals(it.output()) }.toSet()
            if (found.isEmpty()) return
            log.i { "Frequent tool problems noticed: $found" }
            lock.withLock { signals[id] = signals[id].orEmpty() + found }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Tool output of the finished turn was not checked for learning hints" }
        }
    }
}

/** Items after the last user message: the tool calls and results of the turn that just finished. */
private fun List<SessionItem>.lastTurn(): List<SessionItem> {
    val start = indexOfLast { it is SessionItem.Message && it.role == MessageRole.User }
    return drop(start + 1).filter { it is SessionItem.ToolResult || it is SessionItem.ToolCall }
}

private fun SessionItem.output(): String =
    (this as? SessionItem.ToolResult)?.parts?.filterIsInstance<ContentPart.Text>()?.joinToString("\n") { it.text }
        ?: (this as? SessionItem.ToolCall)?.takeIf { it.status == ToolCallStatus.Failed }?.arguments.orEmpty()
