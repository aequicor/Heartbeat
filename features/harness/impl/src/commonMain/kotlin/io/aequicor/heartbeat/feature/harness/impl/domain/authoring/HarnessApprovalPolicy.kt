package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec

/**
 * Agent edits follow the profile level: Ask always asks, ByTrust asks unless the turn has Full trust (AutoEdits
 * still asks), AcceptAll never asks. Code and enabling native tools always ask, whatever the level.
 */
internal fun HarnessApproval.requiresDecision(trust: TrustLevel, isAlwaysAsked: Boolean): Boolean =
    isAlwaysAsked || when (this) {
        HarnessApproval.Ask -> true
        HarnessApproval.ByTrust -> trust != TrustLevel.Full
        HarnessApproval.AcceptAll -> false
    }

/** Wire name of the level inside opaque approval bindings. */
internal val HarnessApproval.key: String
    get() = when (this) {
        HarnessApproval.Ask -> "ask"
        HarnessApproval.ByTrust -> "by_trust"
        HarnessApproval.AcceptAll -> "accept_all"
    }

/** Native tools switched On by [next] that [current] did not switch On. */
internal fun newlyEnabled(current: ToolPolicySpec, next: ToolPolicySpec): Map<String, Set<String>> =
    next.native.mapValues { (engine, tools) ->
        tools.filterValues { it == ToolSwitch.On }.keys.filter { current.native[engine]?.get(it) != ToolSwitch.On }
            .sorted().toSet()
    }.filterValues { it.isNotEmpty() }

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
