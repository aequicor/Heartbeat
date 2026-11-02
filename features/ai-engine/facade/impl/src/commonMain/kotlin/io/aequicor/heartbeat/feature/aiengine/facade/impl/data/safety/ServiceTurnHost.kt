package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Shared facade, durable service authority and cleanup barrier; the journal itself never depends on this host. */
@SingleIn(ProfileScope::class)
@Inject
internal class ServiceTurnHost(
    val facade: Lazy<EngineFacade>,
    val isolation: AgentSafetyIsolation,
    val journal: ServiceTurnJournal,
    private val control: SessionExecutionControl,
) {
    private val log = Log.tag("ServiceTurnHost")

    suspend fun retire(attempt: ServiceAttempt) {
        control.fence(attempt.binding) { journal.retire(attempt.binding) }
        control.awaitAdmissions(attempt.binding)
    }

    /** Recovery never resumes a session or replays text. Unknown openings/unsupported Stops remain unresolved. */
    suspend fun recover() {
        val pending = try {
            journal.orphaned()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Service recovery journal is unavailable" }
            return
        }
        pending.forEach { attempt ->
            try {
                val isStopped = withTimeoutOrNull(10.seconds) { stopCold(attempt) } == true
                if (!isStopped) log.w { "isolated native work remains unconfirmed after restart" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "isolated native cleanup failed" }
            }
        }
    }

    private suspend fun stopCold(attempt: ServiceAttempt): Boolean {
        retire(attempt)
        var current = journal.get(attempt.binding)
        val refs = control.references(attempt.binding) + listOfNotNull(current.session)
        val ref = refs.singleOrNull() ?: return false
        if (current.session == null) current = journal.opened(attempt.binding, ref)
        return if (current.stage in setOf(ServiceStage.Opened, ServiceStage.Prepared)) {
            journal.settled(attempt.binding)
            isolation.release(ref)
            true
        } else {
            stopEntered(current)
        }
    }

    private suspend fun stopEntered(current: ServiceAttempt): Boolean {
        val ref = checkNotNull(current.session)
        val stop = facade.value.engines.features(ref.engine).resolve(StopsOwnedTurns) as? FeatureAccess.Available
        val result = stop?.feature?.stop(ref, current.request, OwnedTurnAccess(current.target))
            as? OwnedTurnStop.Confirmed ?: return false
        journal.stopped(current.binding, result)
        isolation.release(ref)
        return true
    }
}

/** Starts best-effort cold cleanup; durable policy reads protect old sessions before this coroutine runs. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ServiceTurnRecovery(
    private val host: ServiceTurnHost,
    @ForScope(ProfileScope::class) private val scope: CoroutineScope,
) : ProfileStartup {
    override fun start() {
        scope.launch { host.recover() }
    }
}
