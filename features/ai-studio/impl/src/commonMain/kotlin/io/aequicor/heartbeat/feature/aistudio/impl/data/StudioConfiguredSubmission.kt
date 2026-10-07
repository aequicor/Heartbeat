package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
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
        val effort = if (stored != null) {
            stored.reasoningEffort
        } else {
            efforts.state.value.effectiveEffort(target, models().reasoningEfforts(target))
        }
        log.i { "Submitting prompt length=${request.prompt.length} trust=${trust ?: "default"}" }
        val constrained = if (record.helper == null) {
            request
        } else {
            request.copy(directives = request.directives + HELPER_DIRECTIVE)
        }
        val submitted = send(active, constrained, effort) {
            helperPolicy.trust(record, trust, configurations)
        }
        confirmConfiguration(
            access,
            id,
            active,
            target,
            SessionConfiguration(target.model, effort, submitted.trust),
        )
        return submitted.turn
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
            controller.observe(access, id, active, target)
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
        trust: suspend () -> TrustLevel?,
    ): SubmittedPrompt {
        log.i { "Send the reserved native request" }
        val helper = request.submission as? StudioHelperSubmission
        val submit: suspend () -> SubmittedPrompt = {
            val prompt = learning.prompt(request.id, request.prompt, request.directives)
            val finalTrust = if (helper != null) {
                helper.begin(trust)
            } else {
                request.submission?.begin()
                trust()
            }
            val turn = active.submitStudioPrompt(
                prompt,
                reasoningEffort,
                finalTrust,
                request.attachments,
                request.request,
            )
            SubmittedPrompt(turn, finalTrust)
        }
        return if (helper == null) submit() else helper.withPreparation(active.ref, active.route.workspace, submit)
    }

    private data class SubmittedPrompt(val turn: TurnId, val trust: TrustLevel?)
}
