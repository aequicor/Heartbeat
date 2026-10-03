package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.LearningTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningSignal
import io.aequicor.heartbeat.feature.aistudio.impl.domain.REMEMBER_DIRECTIVE
import io.aequicor.heartbeat.feature.aistudio.impl.domain.detectLearningSignals
import io.aequicor.heartbeat.feature.aistudio.impl.domain.learningHint
import io.aequicor.heartbeat.feature.aistudio.impl.domain.rememberText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Self-learning additions to a submitted prompt. A `/remember` command gains the host directive asking the agent to
 * call the remember tool; frequent environment problems noticed in the command output of a finished turn become a
 * hint in the next prompt of the same chat. Hints live in memory only, are consumed by that prompt and are given
 * once per problem and chat. While learning is off the prompt is sent as typed (directive tags escaped) and nothing
 * is collected.
 */
@Inject
@SingleIn(ProfileScope::class)
internal class StudioLearningPrompts(
    private val toggles: FeatureToggles,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("StudioLearningPrompts")
    private val lock = Mutex()
    private val signals = mutableMapOf<String, Set<LearningSignal>>()
    private val hinted = mutableMapOf<String, Set<LearningSignal>>()

    /** The prompt the engine receives for [prompt] typed by the user in chat [id]. */
    suspend fun prompt(id: String, prompt: String): String {
        if (!toggles.get(AgentLearningEnabled)) {
            lock.withLock {
                signals.remove(id)
                hinted.remove(id)
            }
            return withHostDirectives(prompt, emptyList())
        }
        val isRemember = rememberText(prompt) != null
        // A hint would turn into arguments of the user's own CLI command, and a /remember turn does no other work:
        // the hint waits for the next prompt. A delivered hint is not given again in this chat.
        val isDeferred = isRemember || prompt.trimStart().startsWith("/")
        val pending = if (isDeferred) {
            emptySet()
        } else {
            lock.withLock {
                signals.remove(id).orEmpty().also { if (it.isNotEmpty()) hinted[id] = hinted[id].orEmpty() + it }
            }
        }
        val hint = pending.takeIf { it.isNotEmpty() }?.let(::learningHint)
        if (isRemember || hint != null) {
            log.i { "Learning directives sent: remember=$isRemember, signals ${pending.size}" }
        }
        return withHostDirectives(
            prompt,
            listOfNotNull(hint),
            leading = listOfNotNull(REMEMBER_DIRECTIVE.takeIf { isRemember }),
        )
    }

    /**
     * Collects problem signatures from the command output of the last turn of chat [id] read by [items]. A failed
     * read only loses the hint: it never fails the finished turn.
     */
    suspend fun observeTurn(id: String, items: suspend () -> List<SessionItem>) {
        try {
            if (!toggles.get(AgentLearningEnabled)) return
            val turn = items()
            val found = withContext(dispatchers.default) {
                turn.lastTurn().commandOutputs().flatMap(::detectLearningSignals).toSet()
            }
            val fresh = lock.withLock {
                val new = found - hinted[id].orEmpty() - signals[id].orEmpty()
                if (new.isNotEmpty()) signals[id] = signals[id].orEmpty() + new
                new
            }
            if (fresh.isNotEmpty()) log.i { "Frequent tool problems noticed: $fresh" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Tool output of the finished turn was not checked for learning hints" }
        }
    }
}

/** Command tools whose output tells about the environment; other tools read files, search or save lessons. */
private val COMMAND_TOOLS = setOf("run_command", "bash", "shell", "powershell")

private val LEARNING_TOOLS = setOf(LearningTools.REMEMBER, LearningTools.LOAD_SKILL)

/** Longest tail of one output that is checked; the signatures are error lines near the end. */
private const val MAX_CHECKED_OUTPUT = 64_000

/** Items after the last user message: the tool calls and results of the turn that just finished. */
private fun List<SessionItem>.lastTurn(): List<SessionItem> {
    val start = indexOfLast { it is SessionItem.Message && it.role == MessageRole.User }
    return if (start < 0) emptyList() else drop(start + 1)
}

/**
 * Text of failed tool results and of command results. Arguments, file contents and the learning tools' own results
 * are not environment errors: a lesson about encodings must not trigger the hint again.
 */
private fun List<SessionItem>.commandOutputs(): List<String> {
    val names: Map<ToolCallId, String> = filterIsInstance<SessionItem.ToolCall>()
        .associate { it.call to hostedToolName(it.name).lowercase() }
    return asSequence()
        .filterIsInstance<SessionItem.ToolResult>()
        .filter { result ->
            val name = names[result.call]
            name !in LEARNING_TOOLS && (result.failure != null || name in COMMAND_TOOLS)
        }
        .map { result ->
            result.parts.filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
                .takeLast(MAX_CHECKED_OUTPUT)
        }
        .toList()
}
