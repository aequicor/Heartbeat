package io.aequicor.heartbeat.core.statemachine

/**
 * Checks one transition of the spec without a runtime — for `features/<x>/api/src/commonTest`:
 *
 * ```
 * @Test
 * fun `open from Idle loads the chat`() {
 *     ChatMachineSpec.assertTransition(
 *         from = ChatState.Idle,
 *         intent = ChatIntent.Public.Open("c1"),
 *         to = ChatState.Loading("c1"),
 *         effects = listOf(ChatEffect.Load("c1")),
 *     )
 * }
 * ```
 *
 * @return the resolution, for further checks.
 * @throws AssertionError if the intent is ignored or the result differs.
 */
public fun <S, I, E, O> MachineSpec<S, I, E, O>.assertTransition(
    from: S,
    intent: I,
    to: S,
    effects: List<E> = emptyList(),
    outputs: List<O> = emptyList(),
): Resolution<S, I, E, O>
    where S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput {
    val resolution = resolve(from, intent)
        ?: throw AssertionError("$name: ${intent::class.label} is ignored in $from, expected transition to $to")
    fun <T : Any> expect(what: String, expected: T, actual: T) {
        if (expected != actual) {
            throw AssertionError(
                "$name: ${resolution.transition.describe()} — $what: expected $expected, actual $actual",
            )
        }
    }
    expect("state", to, resolution.to)
    expect("effects", effects, resolution.effects)
    expect("outputs", outputs, resolution.outputs)
    return resolution
}

/** Checks that [intent] is ignored in [state]. @throws AssertionError if a transition handles it. */
public fun <S : MachineState, I : MachineIntent> MachineSpec<S, I, *, *>.assertIgnored(state: S, intent: I) {
    val resolution = resolve(state, intent) ?: return
    throw AssertionError(
        "$name: ${intent::class.label} in $state is handled by ${resolution.transition.describe()}",
    )
}
