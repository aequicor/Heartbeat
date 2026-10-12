package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Largest rendered ledger; older done items and facts are dropped first to fit it. */
public const val LEDGER_MAX_CHARS: Int = 3_000

/** Longest single ledger entry. */
public const val LEDGER_ENTRY_CHARS: Int = 400

/**
 * Progress ledger of a long-running session: what the agent is doing ([goal]), what is finished ([done]), what comes
 * next ([next]) and facts it will need later ([facts]). It survives compaction and rollover and is delivered to the
 * agent again after the context changes, so a loop such as "fix every open issue" knows where it stopped. Written
 * only by the agent (the `context_checkpoint` tool). Texts are user data and never logged.
 */
@Serializable
public data class ContextLedger(
    val goal: String? = null,
    val done: List<String> = emptyList(),
    val next: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
    val updatedAt: Instant,
    val version: Long = 0,
) {
    override fun toString(): String =
        "ContextLedger(done=${done.size}, next=${next.size}, facts=${facts.size}, version=$version)"
}

/** One checkpoint: [goal] replaces the goal, [done] and [facts] are appended, [next] replaces the plan when given. */
public data class LedgerUpdate(
    val goal: String? = null,
    val done: List<String> = emptyList(),
    val next: List<String>? = null,
    val facts: List<String> = emptyList(),
) {
    override fun toString(): String = "LedgerUpdate(done=${done.size}, next=${next?.size}, facts=${facts.size})"
}

/**
 * The ledger after [update] at [at]: entries are trimmed to [LEDGER_ENTRY_CHARS], blank ones dropped, repeated done
 * items and facts kept once, and the oldest done items, then the oldest facts, dropped until it fits
 * [LEDGER_MAX_CHARS].
 */
public fun ContextLedger?.updated(update: LedgerUpdate, at: Instant): ContextLedger {
    val current = this ?: ContextLedger(updatedAt = at, version = -1)
    var next = ContextLedger(
        goal = update.goal?.entry() ?: current.goal,
        done = (current.done + update.done.entries()).distinct(),
        next = update.next?.entries() ?: current.next,
        facts = (current.facts + update.facts.entries()).distinct(),
        updatedAt = at,
        version = current.version + 1,
    )
    while (next.render().length > LEDGER_MAX_CHARS && (next.done.isNotEmpty() || next.facts.isNotEmpty())) {
        next = next.withoutOldest()
    }
    return next
}

private fun ContextLedger.withoutOldest(): ContextLedger =
    if (done.isNotEmpty()) copy(done = done.drop(1)) else copy(facts = facts.drop(1))

/** The ledger as the agent reads it. */
public fun ContextLedger.render(): String = buildString {
    append("Progress ledger of this session (kept across context compaction; update it with context_checkpoint):")
    goal?.let { append("\nGoal: ").append(it) }
    section("Done", done)
    section("Next", next)
    section("Facts", facts)
}

private fun StringBuilder.section(title: String, entries: List<String>) {
    if (entries.isEmpty()) return
    append("\n").append(title).append(":")
    entries.forEach { append("\n- ").append(it) }
}

private fun List<String>.entries(): List<String> = mapNotNull { it.entry() }

private fun String.entry(): String? = trim().replace('\n', ' ').take(LEDGER_ENTRY_CHARS).takeIf { it.isNotEmpty() }
