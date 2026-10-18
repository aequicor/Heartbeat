package io.aequicor.heartbeat.feature.planningmode.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Planning mode of studio chats: the agent and the user develop a plan that lives in the profile, stays in the
 * agent's context and is implemented in the same chat. While a chat plans, its agent only reads.
 */
public val PlanningModeEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "planning_mode.enabled",
    "Режим планирования: план задачи в профиле, только чтение до реализации",
    default = false,
)

/** Names of the hosted tools of the planning agent. */
public object PlanningTools {
    /** Re-reads the plan from the profile directory, including edits made outside the studio. */
    public const val READ: String = "plan_read"

    /** Replaces the plan or one of its sections against a known revision. */
    public const val WRITE: String = "plan_write"

    /** Shows the plan card so the user can approve, refine or implement a revision. */
    public const val PROPOSE: String = "plan_propose"

    /** Every planning tool; they stay available while the chat is read-only. */
    public val ALL: Set<String> = setOf(READ, WRITE, PROPOSE)
}
