package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SessionUnavailableException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Priority of the fallback: any host that owns a session wins over it. */
internal const val FACADE_HOST_PRIORITY: Int = 0

/**
 * Fallback host for sessions no chat host owns: resumes the native session through the engine facade on the route
 * recorded with the wake and submits the prompt. The handle is released after acceptance; the accepted turn belongs
 * to the profile runtime and is not cancelled by that.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class FacadeSessionHost(
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) : ScheduledSessionHost {
    private val log = Log.tag("FacadeSessionHost")

    override val priority: Int = FACADE_HOST_PRIORITY

    override suspend fun owns(session: SessionRef): Boolean = true

    override suspend fun wake(request: WakeRequest, prompt: WakePrompt) {
        val target = request.target ?: throw SessionUnavailableException("The wake has no recorded engine route")
        val resumes = facade.sessions.get(request.session).features.resolve(ResumesSessions).orThrow()
        log.i { "resuming session on ${target.engine.value} for a wake" }
        submit(resumes.resume(ResumeSessionRequest(target, request.workspace, areDetachedToolsEnabled = true)), prompt)
    }

    override suspend fun spawn(request: SpawnRequest): SessionRef {
        val creates = facade.engines.features(request.target.engine).resolve(CreatesSessions).orThrow()
        log.i { "creating helper session on ${request.target.engine.value}" }
        val session = creates.create(
            CreateSessionRequest(request.target, request.workspace, areDetachedToolsEnabled = true),
        )
        submit(session, request.prompt)
        return session.ref
    }

    private suspend fun submit(session: ActiveSession, prompt: WakePrompt) {
        try {
            val text = withHostDirectives(prompt.visible, listOf(prompt.directive))
            val sends = session.features.resolve(SendsPrompts).orThrow()
            sends.send(PromptRequest(prompt.request, listOf(ContentPart.Text(text))))
            log.i { "prompt accepted by ${session.ref.engine.value}" }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}

/** Unsupported and temporarily blocked capabilities are domain failures, never silent fallbacks. */
private fun <F : EngineFeature> FeatureAccess<F>.orThrow(): F = when (this) {
    is FeatureAccess.Available -> feature
    is FeatureAccess.Unavailable -> throw EngineException(reason)
    FeatureAccess.Unsupported -> throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}

/**
 * Default of the optional `EngineFacade` parameter until an application bundle installs an engine runtime. Metro
 * treats `EngineFacade?` as a separate key, so a nullable parameter would never receive the real binding.
 */
private object MissingEngineFacade : EngineFacade {
    override val engines: EngineCatalog get() = unavailable()
    override val bindings: EngineBindings get() = unavailable()
    override val providerUsage: ProviderUsageCatalog get() = unavailable()
    override val models: ModelCatalog get() = unavailable()
    override val sessions: SessionCatalog get() = unavailable()

    private fun unavailable(): Nothing = throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
}
