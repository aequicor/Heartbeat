package io.aequicor.heartbeat.core.statemachine

/**
 * Mermaid `stateDiagram-v2` of the spec: every state, every `goto` (guarded ones are marked `[guard]`) and
 * `stay` updates as self-loops. `any` transitions are drawn from every state. Paste into docs or a PR.
 */
public fun MachineSpec<*, *, *, *>.toMermaid(): String = buildString {
    appendLine("stateDiagram-v2")
    appendLine("    [*] --> ${initial::class.label}")
    states.filter { state -> transitions.none { it.source == state } && transitions.none { it.source == null } }
        .forEach { appendLine("    ${it.label}") }
    transitions.forEach { transition ->
        val sources = transition.source?.let(::listOf) ?: states
        val guard = if (transition.isGuarded) " [guard]" else ""
        val stay = if (transition.target == null) " (stay)" else ""
        sources.forEach { source ->
            val target = transition.target ?: source
            appendLine("    ${source.label} --> ${target.label} : ${transition.intent.label}$guard$stay")
        }
    }
}
