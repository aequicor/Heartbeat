package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction

/** System frame the CLI emits when it replaces the conversation with its summary, on request or automatically. */
internal const val CLAUDE_COMPACT_BOUNDARY = "compact_boundary"

/** Text of the compaction notice for consumers that do not know its kind. */
internal const val CLAUDE_COMPACTED_NOTICE = "Context compacted"

/** The CLI compacts through its built-in `/compact` command, which takes optional focus instructions. */
internal object ClaudeCompactsContext : CompactsContext {
    override val isInstructionsSupported: Boolean = true
}

/** The prompt that runs the CLI's `/compact`; prompt hooks never augment it (see the facade contract). */
internal fun claudeCompactPrompt(compaction: ContextCompaction): String =
    listOfNotNull("/compact", compaction.instructions?.replace('\n', ' ')?.trim()).joinToString(" ")
