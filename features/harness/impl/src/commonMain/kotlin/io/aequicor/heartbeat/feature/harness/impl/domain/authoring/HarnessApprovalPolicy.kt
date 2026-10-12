package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec

/**
 * Full turn trust approves ordinary agent edits even when the profile defaults to Ask. Otherwise Ask and ByTrust
 * require a decision (including AutoEdits), while AcceptAll approves them. Code, anything that can make a tool
 * usable again and membership changes of a harness with an enabled script always ask, whatever the level:
 * see [reEnabled], [isWiderThan],
 * [hasEnabledScript] and the callers' membership checks.
 */
internal fun HarnessApproval.requiresDecision(trust: TrustLevel, isAlwaysAsked: Boolean): Boolean =
    isAlwaysAsked || (trust != TrustLevel.Full && this != HarnessApproval.AcceptAll)

/** Wire name of the level inside opaque approval bindings. */
internal val HarnessApproval.key: String
    get() = when (this) {
        HarnessApproval.Ask -> "ask"
        HarnessApproval.ByTrust -> "by_trust"
        HarnessApproval.AcceptAll -> "accept_all"
    }

/**
 * Tools that [next] may make usable again compared with [current]: native switches turned On, native switches
 * released from Off (the default may be on, and Off of this harness may be all that hides another harness's On) and
 * Heartbeat tools removed from hosted_off. Entries are `engine/tool` for native tools and plain hosted names.
 */
internal fun reEnabled(current: ToolPolicySpec, next: ToolPolicySpec): List<String> = buildList {
    (current.hostedOff - next.hostedOff).sorted().forEach(::add)
    (current.native.keys + next.native.keys).sorted().forEach { engine ->
        val before = current.native[engine].orEmpty()
        val after = next.native[engine].orEmpty()
        (before.keys + after.keys).sorted().forEach { tool ->
            val isSwitchedOn = after[tool] == ToolSwitch.On && before[tool] != ToolSwitch.On
            val isReleased = before[tool] == ToolSwitch.Off && after[tool] != ToolSwitch.Off
            if (isSwitchedOn || isReleased) add("$engine/$tool")
        }
    }
}

/**
 * An enabled script runs its hooks, events and tools with access to every chat where the harness applies: wider
 * membership spreads that access like a native On switch, and narrower membership drops its Deny/Ask hooks like
 * a restriction.
 */
internal val Harness.hasEnabledScript: Boolean get() = items.any { it is HarnessItem.Script && it.isEnabled }

/** The policy switches a native tool On: wider membership spreads it to more chats. */
internal val ToolPolicySpec.hasNativeOn: Boolean get() = native.values.any { ToolSwitch.On in it.values }

/** The policy hides tools: losing membership in a chat gives them back there. */
internal val ToolPolicySpec.hasRestrictions: Boolean
    get() = hostedOff.isNotEmpty() || native.values.any { ToolSwitch.Off in it.values }

/**
 * Whether this scope covers a chat that [other] does not. Connected chats are covered by every scope, so
 * Attached is never wider; Profile covers everything; projects compare as sets.
 */
internal fun HarnessScope.isWiderThan(other: HarnessScope): Boolean = when (this) {
    HarnessScope.Attached -> false

    HarnessScope.Profile -> other != HarnessScope.Profile

    is HarnessScope.Projects -> when (other) {
        HarnessScope.Profile -> false
        HarnessScope.Attached -> true
        is HarnessScope.Projects -> !other.projects.containsAll(projects)
    }
}

/**
 * Human-readable difference shown in the question, one line per change: hosted tools turned off or back on, then
 * native switches by engine. Defaults are spelled out so that removing a switch is visible.
 */
internal fun policyDiff(current: ToolPolicySpec, next: ToolPolicySpec): List<String> = buildList {
    (next.hostedOff - current.hostedOff).sorted().forEach { add("Heartbeat tool $it: off") }
    (current.hostedOff - next.hostedOff).sorted().forEach { add("Heartbeat tool $it: default") }
    (current.native.keys + next.native.keys).sorted().forEach { engine ->
        val before = current.native[engine].orEmpty()
        val after = next.native[engine].orEmpty()
        (before.keys + after.keys).sorted().forEach { tool ->
            val old = before[tool]
            val new = after[tool]
            if (old != new) add("$engine tool $tool: ${new.label()} (was ${old.label()})")
        }
    }
}

private fun ToolSwitch?.label(): String = when (this) {
    ToolSwitch.On -> "on"
    ToolSwitch.Off -> "off"
    null -> "default"
}
