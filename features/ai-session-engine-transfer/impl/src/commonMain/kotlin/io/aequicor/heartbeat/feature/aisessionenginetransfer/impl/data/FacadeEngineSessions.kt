package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.EngineSessions
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.SeedSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Creates the target session through the exact requested route; the facade rechecks toggles and ownership.
 *
 * Every capability the handoff needs is resolved before the handle is returned. If the created session
 * cannot accept prompts (or resolution fails in any other way, including cancellation), the native
 * session is released under [NonCancellable] so it is never orphaned.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeEngineSessions(
    // Optional until an application bundle installs the AI engine facade.
    private val facade: EngineFacade = MissingEngineFacade,
) : EngineSessions {

    private val log = Log.tag("FacadeEngineSessions")

    override suspend fun create(target: EngineTarget, workspace: WorkspaceRef?): SeedSession {
        val creates = facade.engines.features(target.engine).resolve(CreatesSessions).orThrow()
        log.i { "creating session on ${target.engine.value}" }
        val session = creates.create(CreateSessionRequest(target, workspace))
        var handle: SeedSession? = null
        try {
            handle = ActiveSeedSession(session, session.features.resolve(SendsPrompts).orThrow(), log)
            return handle
        } finally {
            if (handle == null) {
                log.w { "session on ${target.engine.value} is unusable for handoff; releasing it" }
                withContext(NonCancellable) { session.close() }
                log.d { "released unusable session on ${target.engine.value}" }
            }
        }
    }
}

private class ActiveSeedSession(
    private val session: ActiveSession,
    private val sends: SendsPrompts,
    private val log: Log,
) : SeedSession {
    override val ref: SessionRef get() = session.ref

    override suspend fun send(prompt: PromptRequest) {
        log.i { "submitting handoff to ${ref.engine.value}" }
        sends.send(prompt)
        log.i { "handoff accepted by ${ref.engine.value}" }
    }

    override suspend fun close() {
        session.close()
        log.d { "released handoff handle on ${ref.engine.value}" }
    }
}
