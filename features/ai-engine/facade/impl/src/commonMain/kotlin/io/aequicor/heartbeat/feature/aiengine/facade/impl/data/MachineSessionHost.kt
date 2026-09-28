package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionAssembler
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionEffects
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionHost
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionHandleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ActiveSessionHost] giving each handle a child scope of the profile, in which its machine runs (registered in
 * MachineRegistry under ActiveSessionMachineKey). The scope closes once the handle is Closed, or with the profile.
 */
@Inject
@ContributesBinding(ProfileScope::class)
class MachineSessionHost(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val launcher: MachineLauncher,
    private val assembler: ActiveSessionAssembler,
    private val dispatchers: DispatcherProvider,
    private val context: FacadeContext,
) : ActiveSessionHost {
    private val log = Log.tag("MachineSessionHost")

    // NonCancellable: once assembled, the handle is registered and must reach the caller to be closed later.
    override suspend fun open(native: ActiveSession, route: ExecutionRoute, model: ModelId): ActiveSession =
        withContext(dispatchers.main + NonCancellable) {
            val id = context.token("h_")
            log.i { "open handle id=$id engine=${route.engine.value}" }
            val owned = scopes.child(profile, "aiengine-$id")
            try {
                assembler.assemble(native, route, model, OwnedHandle(id, owned))
            } catch (e: CancellationException) {
                owned.close()
                throw e
            } catch (e: Exception) {
                log.w(e) { "handle assembly failed id=$id" }
                owned.close()
                throw e
            }
        }

    private inner class OwnedHandle(override val id: String, private val owned: OwnedScope) : SessionHandleScope {
        override val scope: CoroutineScope get() = owned.coroutineScope

        override fun launch(
            spec: MachineSpec<ActiveSessionState, ActiveSessionIntent, ActiveSessionEffect, ActiveSessionOutput>,
            effects: ActiveSessionEffects,
        ): Machine<ActiveSessionState, ActiveSessionIntent, ActiveSessionOutput> = launcher.launch(spec, owned, effects)

        override fun close() {
            log.i { "close handle scope id=$id" }
            profile.coroutineScope.launch { owned.close() }
        }
    }
}
