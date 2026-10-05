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
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SessionUnavailableException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Priority of the fallback: any host that owns a session wins over it. */
internal const val FACADE_HOST_PRIORITY: Int = 0

/**
 * Fallback host for sessions no chat host owns: resumes the native session through the engine facade on the route
 * recorded with the wake and submits the prompt. The handle is released after acceptance; the accepted turn belongs
 * to the profile runtime and is not cancelled by that. It creates no helper sessions: nothing would report the end of
 * their turns, so their result could never be published.
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
