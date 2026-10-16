package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.effectiveEffort
import kotlinx.coroutines.CancellationException

/** Resolves saved configuration, applies helper restrictions and crosses the final native submission barrier. */
@Inject
internal class StudioConfiguredSubmission(
    private val controller: StudioConfigurationController,
    private val learning: StudioLearningPrompts,
    private val helperPolicy: StudioHelperPolicy,
    private val efforts: EffortChoicesView,
    private val origins: StudioRequestOrigins,
    private val security: StudioSecurityPolicy,
    private val rollovers: StudioRolloverObserver = StudioRolloverObserver { _, _ -> },
) {
    private val log = Log.tag("StudioConfiguredSubmission")

    /** Initial defaults are used once; accepted execution keeps the session's confirmed values. */
    suspend fun submit(
        access: StudioConfigurationAccess,
        active: ActiveSession,
        target: EngineTarget,
        request: StudioTurnRequest,
        models: () -> List<StudioModel>,
        configurations: () -> Map<String, StudioSessionConfiguration>,
    ): TurnId {
        val id = request.id
        val record = access.configurationRecord(id)
        val stored = configurations()[id]?.applied ?: record.configuration
        val approval = stored?.approval ?: request.settings.approval
        val requested = if (record.helper == null) {
            approval.trustFor(models(), target)
        } else {
            approval.toTrust()
        }
        val trust = helperPolicy.trust(record, requested, configurations)
        if (record.helper != null) active.features.requireFeature(AppliesTrustLevels)
        val supported = models().reasoningEfforts(target)
        val effort = turnEffort(stored, record.helper, supported) {
            efforts.state.value.effectiveEffort(target, supported)
        }
        log.i { "Submitting prompt length=${request.prompt.length} trust=${trust ?: "default"}" }
        val seed = record.pendingSeed.takeIf { request.compaction == null }
        // The ledger and marks follow the chat before its first turn in the new session prepares its prompt.
        if (seed != null) record.previousRefs.lastOrNull()?.let { rollovers.rolledOver(it, active.ref) }
        val carried = record.carriedDirectives(request)
        val seeded = if (carried.isEmpty()) request else request.copy(directives = carried + request.directives)
        val constrained = if (record.helper == null) {
            seeded
        } else {
            seeded.copy(directives = seeded.directives + HELPER_DIRECTIVE)
        }
        // The handoff turn's narrowed trust is not the chat's configuration: its session is never reflected again.
        if (request.isHandoff) controller.retire(id, active)
        val reviewTarget = record.helper?.model?.reviewTarget
        val submitted = send(active, constrained, effort, reviewTarget) {
            turnPolicy(request, record, trust, configurations)
        }
        if (carried.isNotEmpty()) consumeSeed(access, id)
        if (request.isHandoff) return submitted.turn
        confirmConfiguration(
            access,
            id,
            active,
            target,
            SessionConfiguration(target.model, effort, submitted.trust),
        )
        return submitted.turn
    }

    /** Captures one policy at submission; host jobs and restricted helper sessions never gain profile trust. */
    private suspend fun turnPolicy(
        request: StudioTurnRequest,
        record: StudioChatRecord,
        trust: TrustLevel?,
        configurations: () -> Map<String, StudioSessionConfiguration>,
    ): StudioPromptPolicy {
        val isUserChat = request.isUserPrompt && record.helper == null
        val level = if (isUserChat && request.compaction == null && !request.isHandoff) {
            security.level()
        } else {
            StudioSecurityLevel.Automatic
        }
        // A non-null trust would be rejected by engines that do not advertise AppliesTrustLevels.
        val requested = when (level) {
            StudioSecurityLevel.Ask -> trust?.let { TrustLevel.Ask }
            StudioSecurityLevel.Automatic -> trust
            StudioSecurityLevel.Trust -> trust?.let { TrustLevel.Full }
        }
        // Engines without native tool hooks must also ask during a handoff summary.
        val effective = if (request.isHandoff) {
            requested?.let { TrustLevel.Ask }
        } else {
            helperPolicy.trust(record, requested, configurations)
        }
        val mode = if (level == StudioSecurityLevel.Automatic) {
            AgentToolDecisionMode.AutomaticSafety
        } else {
            AgentToolDecisionMode.Interactive
        }
        return StudioPromptPolicy(mode, effective)
    }

    /** Native acceptance owns the turn: a seed that cannot be marked delivered may only reach the next turn again. */
    private suspend fun consumeSeed(access: StudioConfigurationAccess, id: String) {
        try {
            access.consumeSeed(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Carried context of an accepted turn could not be marked delivered; keep native ownership" }
        }
    }

    private suspend fun confirmConfiguration(
        access: StudioConfigurationAccess,
        id: String,
        active: ActiveSession,
        target: EngineTarget,
        fallback: SessionConfiguration,
    ) {
        log.v { "Reflect accepted native configuration" }
        try {
            val capability = active.features.resolve(ChangesSessionConfiguration) as? FeatureAccess.Available
            val confirmed = capability?.feature?.configuration?.value ?: fallback
            access.configurationState(id, StudioSessionConfiguration(confirmed.studio(target)))
            try {
                access.saveConfiguration(id, confirmed.studio(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e(e) { "Accepted turn configuration could not be saved; continue native observation" }
            }
            controller.observe(access, id, active, target, isTurnAccepted = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Native acceptance owns the turn even when configuration reflection is unavailable.
            log.e(e) { "Accepted turn configuration could not be reflected; continue native observation" }
        }
    }

    private suspend fun send(
        active: ActiveSession,
        request: StudioTurnRequest,
        reasoningEffort: String?,
        reviewTarget: EngineTarget?,
        policy: suspend () -> StudioPromptPolicy,
    ): SubmittedPrompt {
        log.i { "Send the reserved native request" }
        val helper = request.submission as? StudioHelperSubmission
        val submit: suspend () -> SubmittedPrompt = {
            origins.record(active.ref, request.request, request.causes)
            val compaction = request.compaction
            val prompt = if (compaction == null) learning.prompt(request.id, request.prompt, request.directives) else ""
            val finalPolicy = if (helper != null) {
                StudioPromptPolicy(AgentToolDecisionMode.AutomaticSafety, helper.begin { policy().trust })
            } else {
                request.submission?.begin()
                policy()
            }
            val native: suspend () -> TurnId = {
                active.submitStudioPrompt(
                    prompt,
                    reasoningEffort,
                    finalPolicy.trust,
                    request.attachments,
                    request.request,
                    compaction,
                    request.delivery,
                    decisionMode = finalPolicy.mode,
                    reviewTarget = reviewTarget,
                )
            }
            // A helper receipt must not stay Submitting when the engine refused the request before acceptance.
            val turn = if (helper != null) helper.send(native) else native()
            SubmittedPrompt(turn, finalPolicy.trust)
        }
        return if (helper == null) submit() else helper.withPreparation(active.ref, active.route.workspace, submit)
    }

    private data class StudioPromptPolicy(val mode: AgentToolDecisionMode, val trust: TrustLevel?)

    private data class SubmittedPrompt(val turn: TurnId, val trust: TrustLevel?)
}

/**
 * Effort of a turn: the chat's stored settings win; a helper's creator may fix its effort while the model still
 * advertises it ([supported]); otherwise the [profile] choice applies.
 */
internal fun turnEffort(
    stored: StudioSessionSettings?,
    helper: StudioHelperIdentity?,
    supported: List<String>,
    profile: () -> String?,
): String? = if (stored != null) {
    stored.reasoningEffort
} else {
    helper?.model?.reasoningEffort?.takeIf { it in supported } ?: profile()
}
