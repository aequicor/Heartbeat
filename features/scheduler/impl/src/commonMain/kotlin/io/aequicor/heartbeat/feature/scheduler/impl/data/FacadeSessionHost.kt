package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SessionUnavailableException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Priority of the fallback: any host that owns a session wins over it. */
internal const val FACADE_HOST_PRIORITY: Int = 0

/**
 * Fallback host for sessions no chat host owns. Only a wake requested by a feature, which owns the session's
 * lifecycle, is delivered: the native session is resumed through the engine facade on the recorded route without
 * hosted tools (nothing here shows their calls or answers permissions) and the prompt is submitted. A wake of an
 * agent whose chat is gone is dropped as unavailable instead of running an unattended turn. The handle is released
 * after acceptance; the accepted turn belongs to the profile runtime. It creates no helper sessions: nothing would
 * report the end of their turns, so their result could never be published.
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
        if (request.origin is WakeOrigin.Agent) {
            throw SessionUnavailableException("No chat shows this session; an agent wake is not resumed unattended")
        }
        val target = request.target ?: throw SessionUnavailableException("The wake has no recorded engine route")
        val resumes = facade.sessions.get(request.session).features.resolve(ResumesSessions).orThrow()
        log.i { "resuming session on ${target.engine.value} for a wake" }
        submit(resumes.resume(ResumeSessionRequest(target, request.workspace, areDetachedToolsEnabled = false)), prompt)
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
