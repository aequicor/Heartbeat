package io.aequicor.heartbeat.feature.harness.api

/** Allowlisted host validation failures safe to expose in activation status without private script values. */
public enum class HarnessValidationCode(public val message: String) {
    /** Item, UI method, tool and custom event slugs use the same lowercase grammar. */
    InvalidItemName(
        "ItemName must match [a-z][a-z0-9_]{0,31}: use lowercase snake_case, not camelCase, " +
            "for item, UI method, tool and custom event names.",
    ),
}

/**
 * Known host argument validation. Neither the rejected value nor author-controlled text is retained.
 * The runtime exposes only [code]; arbitrary script exception messages remain private.
 */
public class HarnessValidationException(public val code: HarnessValidationCode) : IllegalArgumentException(code.message)
