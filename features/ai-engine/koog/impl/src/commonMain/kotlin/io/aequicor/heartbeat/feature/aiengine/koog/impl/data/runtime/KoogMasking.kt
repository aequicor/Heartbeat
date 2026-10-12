package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CancellationException

private val log = Log.tag("KoogMasking")

/** The first prompt shaping a profile provider offers for [session]; a failing provider leaves it verbatim. */
internal suspend fun KoogAccess.contextPolicy(session: SessionRef): ContextPolicy? = try {
    contextPolicies.value.firstNotNullOfOrNull { it.policy(session) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e) { "Context policy unavailable; the prompt stays verbatim" }
    null
}

/** A shortened output of a Koog tool when a profile feature offloads it; a failure keeps the output. */
internal suspend fun KoogAccess.offloaded(session: SessionRef, tool: String, text: String): String? = try {
    toolOffloads.value.firstNotNullOfOrNull { it.offload(session, tool, text) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log.w(e) { "Tool output offload failed; the output stays whole" }
    null
}

/** [offloaded] for the tools of [session]. */
internal fun KoogAccess.offloader(session: SessionRef): suspend (String, String) -> String? =
    { tool, text -> offloaded(session, tool, text) }

/**
 * Observation masking of the visible context: tool results of turns older than the newest
 * [ContextPolicy.keepRecentTurns] keep only their beginning and a note, in steps of [ContextPolicy.maskStepTurns]
 * turns. Calls stay, so the model still sees what it did; the transcript itself is unchanged.
 */
internal fun List<SessionItem>.masked(policy: ContextPolicy?): List<SessionItem> {
    if (policy == null) return this
    val turns = mapNotNull { it.info.turn }.distinct()
    val maskable = turns.size - policy.keepRecentTurns
    val masked = maskable / policy.maskStepTurns * policy.maskStepTurns
    if (masked <= 0) return this
    val older = turns.take(masked).toSet()
    val calls = filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
    return map { item ->
        if (item is SessionItem.ToolResult && item.info.turn in older) {
            item.stub(policy.toolStubChars, calls[item.call])
        } else {
            item
        }
    }
}

private fun SessionItem.ToolResult.stub(chars: Int, call: SessionItem.ToolCall?): SessionItem.ToolResult {
    val text = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    val hasImages = parts.any { it !is ContentPart.Text }
    if (text.length <= chars + STUB_SLACK && !hasImages) return this
    val omitted = text.length - chars.coerceAtMost(text.length)
    val note = "[$omitted more characters of this earlier ${call?.name ?: "tool"} result were removed from the " +
        "context${if (hasImages) ", with its images" else ""}; run the tool again if you need them]"
    return copy(parts = listOf(ContentPart.Text(text.take(chars) + "\n" + note)))
}

/** Results only slightly longer than the stub are kept: the note would not save anything. */
private const val STUB_SLACK = 200
