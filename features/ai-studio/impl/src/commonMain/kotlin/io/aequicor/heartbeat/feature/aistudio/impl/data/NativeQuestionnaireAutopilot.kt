package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext

/**
 * Keeps the profile lifecycle entry point while native input protocols cannot carry automatic provenance.
 * Structured questions may be approvals, so none are eligible for model calls or automatic delivery.
 * Manual questions remain owned by [StudioQuestionBridge]; this service does not acquire model dependencies.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class NativeQuestionnaireAutopilot {
    fun run() = Unit
}

/**
 * Host-only provenance retained to reject stale automatic answers at the native delivery boundary.
 * Neither callback grants human authorization or makes an origin-blind native protocol safe for auto-answering.
 */
internal class NativeAutopilotAnswerContext(
    val permission: StudioPermission,
    val generation: String,
    val isAuthorityCurrent: suspend () -> Boolean,
    val isCurrent: suspend () -> Boolean,
) : QuestionnaireAnswerContext
