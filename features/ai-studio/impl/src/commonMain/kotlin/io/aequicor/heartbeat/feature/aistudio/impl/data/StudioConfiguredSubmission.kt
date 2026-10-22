package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import io.aequicor.heartbeat.feature.aistudio.impl.domain.browserMentionDirective
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.effectiveEffort
import kotlinx.coroutines.CancellationException

/** Resolves saved configuration and crosses the native submission barrier with the shared profile mode. */
@Inject
internal class StudioConfiguredSubmission(
    private val controller: StudioConfigurationController,
    private val learning: StudioLearningPrompts,
    private val efforts: EffortChoicesView,
    private val origins: StudioRequestOrigins,
    private val security: StudioSecurityPolicy,
    private val assists: ComposerAssists,
    private val rollovers: StudioRolloverObserver = StudioRolloverObserver { _, _ -> },
    private val planning: StudioPlanning = StudioPlanning.None,
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
        val isTrustSupported = models().isTrustSupported(target)
        val supported = models().reasoningEfforts(target)
        val effort = turnEffort(stored, record.helper, supported) {
            efforts.state.value.effectiveEffort(target, supported)
        }
        log.i { "Submitting prompt length=${request.prompt.length}" }
        // The plan and its read-only rule follow the chat into the session before anything of the turn changes.
        // Binding fails closed: a planning chat whose session stays unbound, or plans still loading, fail the turn
        // unsent.
        planning.bind(id, active.ref)
        carryLedger(record, active)
        val carried = record.carriedDirectives(request)
        val seeded = if (carried.isEmpty()) request else request.copy(directives = carried + request.directives)
        val constrained = if (record.helper == null) {
            seeded
        } else {
            seeded.copy(directives = seeded.directives + HELPER_DIRECTIVE)
        }
        // A handoff retires configuration observation because its session is never reflected again.
        if (request.isHandoff) controller.retire(id, active)
        val reviewTarget = record.helper?.model?.reviewTarget
        val submitted = send(access, active, constrained, effort, reviewTarget) {
            turnPolicy(isTrustSupported)
        }
        consumeCarriedContext(access, record, request, carried)
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

    /** Every kind of turn snapshots the same saved mode; tool execution also observes live changes. */
    private suspend fun turnPolicy(isTrustSupported: Boolean): StudioPromptPolicy {
        val level = security.level()
        val trust = when (level) {
            StudioSecurityLevel.Ask -> TrustLevel.Ask
            StudioSecurityLevel.Automatic -> TrustLevel.AutoEdits
            StudioSecurityLevel.Trust -> TrustLevel.Full
        }.takeIf { isTrustSupported }
        val mode = if (level == StudioSecurityLevel.Automatic) {
            AgentToolDecisionMode.AutomaticSafety
        } else {
            AgentToolDecisionMode.Interactive
        }
        return StudioPromptPolicy(mode, trust)
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
        access: StudioConfigurationAccess,
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
            val prompt = preparePrompt(access, request, active.route.workspace)
            val finalPolicy = if (helper != null) {
                var latest: StudioPromptPolicy? = null
                val trust = helper.begin { policy().also { latest = it }.trust }
                checkNotNull(latest).copy(trust = trust)
            } else {
                request.submission?.begin()
                policy()
            }
            val native: suspend () -> TurnId = {
                val authority = access.captureUserInstructions(request.id, request.request, request.userInstruction)
                active.submitStudioPrompt(
                    prompt,
                    reasoningEffort,
                    finalPolicy.trust,
                    request.attachments,
                    request.request,
                    request.compaction,
                    request.delivery,
                    decisionMode = finalPolicy.mode,
                    reviewTarget = reviewTarget,
                    trustedUserInstructions = authority,
                )
            }
            // A helper receipt must not stay Submitting when the engine refused the request before acceptance.
            val turn = if (helper != null) helper.send(native) else native()
            SubmittedPrompt(turn, finalPolicy.trust)
        }
        return if (helper == null) submit() else helper.withPreparation(active.ref, active.route.workspace, submit)
    }

    private suspend fun carryLedger(record: StudioChatRecord, active: ActiveSession) {
        if (record.pendingSeed != null || record.pendingEngineContext != null) {
            record.previousRefs.lastOrNull()?.let { rollovers.rolledOver(it, active.ref) }
        }
    }

    private suspend fun consumeCarriedContext(
        access: StudioConfigurationAccess,
        record: StudioChatRecord,
        request: StudioTurnRequest,
        carried: List<String>,
    ) {
        if (carried.isNotEmpty() || (record.pendingEngineContext != null && request.compaction == null)) {
            consumeSeed(access, request.id)
        }
    }

    private suspend fun preparePrompt(
        access: StudioConfigurationAccess,
        request: StudioTurnRequest,
        workspace: WorkspaceRef?,
    ): String {
        if (request.compaction != null) return ""
        val skills = skillDirectives(request.prompt, workspace)
        val browser = request.userInstruction?.takeIf { request.isUserPrompt }?.let {
            browserMentionDirective(it, "external_browser" in request.environment?.preselected.orEmpty())
        }
        val prepared = learning.prompt(request.id, request.prompt, request.directives + skills + listOfNotNull(browser))
        val context = access.engineSwitchContext(request.id, prepared + "\n\n" + hostDirective(""))
        return if (context == null) prepared else prepared + "\n\n" + hostDirective(context)
    }

    /**
     * A linked project skill shows only its label in the composer; its body travels as a host directive, so a model
     * without file tools follows the same instruction while the transcript keeps what the user typed.
     */
    private suspend fun skillDirectives(prompt: String, workspace: WorkspaceRef?): List<String> {
        val skills = try {
            assists.referencedSkills(prompt, workspace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Linked project skills unavailable; the prompt keeps only their links" }
            emptyList()
        }
        if (skills.isNotEmpty()) log.i { "Linked project skills delivered: ${skills.size}" }
        return skills.map {
            "The user invoked project skill ${it.label} (${it.relativePath}). Follow its instructions:\n\n${it.content}"
        }
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
