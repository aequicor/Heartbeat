package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioDefaults
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * UI effects only wait for profile work; their cancellation never claims native cancellation.
 * User decisions (stop, permission answers) are logged with request/option ids only, never content.
 */
class EngineStudioEffects(
    private val repository: StudioRepository,
    private val runtime: StudioRuntime,
    private val availability: StudioAvailability,
    private val projects: StudioProjects? = null,
    private val planning: StudioPlanning = StudioPlanning.None,
    private val plans: StudioPlanRuntime = StudioPlanRuntime.None,
) : EffectHandler<AiStudioEffect, AiStudioIntent> {
    private val log = Log.tag("EngineStudioEffects")

    /** Identity of this screen's usage targets; another screen of the profile keeps its own. */
    private val usageOwner = Any()

    override suspend fun handle(effect: AiStudioEffect, machine: EffectScope<AiStudioIntent>) {
        when (effect) {
            AiStudioEffect.Load -> machine.send(
                AiStudioIntent.Internal.Loaded(
                    availability.isEnabled(),
                    StudioDefaults(null, runtime.defaults(), StudioSettingsVersion(Uuid.random().toString())),
                ),
            )

            is AiStudioEffect.Configuration -> configure(effect)

            AiStudioEffect.ObserveAvailability -> availability.observe().collect {
                machine.send(
                    AiStudioIntent.Internal.AvailabilityChanged(it),
                )
            }

            AiStudioEffect.ObserveRuntime -> observeRuntime(machine)

            AiStudioEffect.ObserveModels -> repository.observeModels()
                .map { models -> models.map { it.id } }
                .distinctUntilChanged()
                .collect { machine.send(AiStudioIntent.Internal.ModelsChanged(it)) }

            AiStudioEffect.ObserveProjects -> observeProjects(machine)

            is AiStudioEffect.Usage -> usage(effect)

            is AiStudioEffect.ChooseProject -> machine.send(
                AiStudioIntent.Internal.ProjectChosen(effect.paneId, checkNotNull(projects).choose()),
            )

            is AiStudioEffect.CreateSession -> createSession(effect, machine)

            is AiStudioEffect.Turn -> turn(effect, machine)

            is AiStudioEffect.Cancel -> {
                log.i { "User requested stop" }
                runtime.cancel(effect.sessionId)
            }

            is AiStudioEffect.RespondPermission -> {
                log.i { "User answered permission request=${effect.requestId} option=${effect.optionId}" }
                runtime.respond(effect.sessionId, effect.requestId, effect.optionId, effect.answer)
            }

            is AiStudioEffect.Apply -> repository.edit(effect.sessionId, effect.edit)
        }
    }

    private suspend fun createSession(effect: AiStudioEffect.CreateSession, machine: EffectScope<AiStudioIntent>) {
        requireBrowserCreationSupport(effect)
        val session = repository.createSession(
            effect.projectId,
            titleOf(effect.prompt),
            effect.isWorktree,
            effect.organism(),
            isSandbox = effect.isSandbox,
        )
        // The saved conversation stays an idle sidebar entry when planning cannot start.
        if (effect.isPlanning) check(planning.enter(session.id)) { "Planning mode could not start" }
        machine.send(
            AiStudioIntent.Internal.SessionCreated(
                effect.paneId,
                session.id,
                effect.prompt,
                effect.settings,
                effect.requestId,
                effect.attachments,
                effect.submissionId,
                effect.environment,
            ),
        )
    }

    private suspend fun turn(effect: AiStudioEffect.Turn, machine: EffectScope<AiStudioIntent>) {
        when (effect) {
            is AiStudioEffect.Run -> runEffect(effect, machine)

            is AiStudioEffect.ImplementPlan -> {
                val outcome = plans.implementPlan(effect.sessionId, effect.implementation, effect.settings)
                machine.send(AiStudioIntent.Internal.RunFinished(effect.sessionId, outcome))
            }
        }
    }

    private suspend fun runEffect(effect: AiStudioEffect.Run, machine: EffectScope<AiStudioIntent>) {
        var isAccepted = false
        val outcome = runtime.run(
            effect.sessionId,
            effect.prompt,
            effect.settings,
            effect.attachments,
            effect.submissionId,
            effect.environment,
        ) {
            isAccepted = true
            if (effect.submissionId.isNotEmpty()) {
                machine.send(
                    AiStudioIntent.Internal.RunAccepted(effect.paneId, effect.submissionId, effect.sessionId),
                )
            }
        }
        if (!isAccepted && effect.submissionId.isNotEmpty()) {
            machine.send(
                AiStudioIntent.Internal.RunRejected(effect.paneId, effect.submissionId, effect.sessionId),
            )
        }
        machine.send(AiStudioIntent.Internal.RunFinished(effect.sessionId, outcome))
    }

    private suspend fun configure(effect: AiStudioEffect.Configuration) {
        when (effect) {
            is AiStudioEffect.SaveSettings -> runtime.saveDefaults(effect.settings, effect.version)
            is AiStudioEffect.ChangeSessionSetting -> runtime.configure(effect.sessionId, effect.change)
        }
    }

    private suspend fun observeProjects(machine: EffectScope<AiStudioIntent>) {
        combine(
            projects?.availability ?: flowOf(false),
            projects?.worktreeAvailability ?: flowOf(false),
            projects?.repositoryProjects ?: flowOf(emptySet()),
            projects?.sandboxAvailability ?: flowOf(false),
        ) { folders, worktree, repositories, sandbox ->
            AiStudioIntent.Internal.ProjectAvailabilityChanged(folders, worktree, repositories, sandbox)
        }.collect {
            machine.send(it)
        }
    }

    private suspend fun observeRuntime(machine: EffectScope<AiStudioIntent>) {
        try {
            runtime.state.collect { machine.send(AiStudioIntent.Internal.RuntimeChanged(it)) }
        } finally {
            // Scope destruction may stop the machine before the store can send its detach intent.
            withContext(NonCancellable) { runtime.observeUsageTargets(usageOwner, emptySet()) }
        }
    }

    private suspend fun usage(effect: AiStudioEffect.Usage) {
        when (effect) {
            is AiStudioEffect.ObserveUsageTargets -> runtime.observeUsageTargets(usageOwner, effect.modelIds)
            is AiStudioEffect.RefreshUsage -> runtime.refreshUsage(effect.modelId)
        }
    }
}

/** Outside turns cannot route independently created organism child sessions. */
private fun requireBrowserCreationSupport(effect: AiStudioEffect.CreateSession) {
    check(effect.environment == null || !effect.isOrganism) { "Browser-hosted organisms are unavailable" }
}
