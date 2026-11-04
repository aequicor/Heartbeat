package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibilityProblem

/**
 * Admission of the connection's exact saved route, as far as the connector could prove it. Only [Allowed] may
 * advance the wizard to models or finish; [Refused] and [Unknown] carry typed, non-secret diagnostics and are
 * never treated as success.
 */
public sealed interface WizardAdmission {
    /** The restricted assessment is running; nothing is allowed yet. */
    public data object Checking : WizardAdmission

    /** The connector proved the baseline for this exact route. */
    public data object Allowed : WizardAdmission

    /** The route was observed and positively refused. */
    public data class Refused(public val problems: Set<CompatibilityProblem>) : WizardAdmission

    /** No policy, no restricted candidate or incomplete evidence; this is not a permit. */
    public data class Unknown(public val problems: Set<CompatibilityProblem> = emptySet()) : WizardAdmission
}
