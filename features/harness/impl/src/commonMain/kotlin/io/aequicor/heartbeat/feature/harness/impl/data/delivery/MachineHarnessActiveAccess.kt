package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyUnavailable
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Registry reads do not start engines or the library and therefore cannot recurse into tool contributions.
 * A cold start waits a bounded time for committed evidence. Once that evidence proved unavailable, later reads
 * only probe the current snapshot instead of waiting again, until a read succeeds. Only committed chat connections
 * activate a harness; the caller's workspace does not. Unavailability is reported once per transition: a WARN when
 * it starts, VERBOSE for repeats, and consumers receive [HarnessActivationUnavailable], which they must not report
 * again.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessActiveAccess(private val machines: MachineRegistry, private val toggles: FeatureToggles) :
    HarnessActiveAccess {
    private val log = Log.tag("HarnessActive")
    private val unavailability = MutableStateFlow(false)

    override suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness> =
        resolve { select(session) }

    override suspend fun applicable(workspace: WorkspaceRef?, session: SessionRef?): List<Harness> =
        resolve { applicable(session) }

    override suspend fun opening(harnesses: Set<HarnessId>): List<Harness> =
        if (harnesses.isEmpty()) emptyList() else resolve { opening(harnesses) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun resolve(selection: HarnessActiveSnapshot.() -> List<Harness>?): List<Harness> {
        if (!toggles.get(HarnessEnabled)) return emptyList()
        val wait = if (unavailability.value) UNAVAILABLE_PROBE else COLD_START_WAIT
        val selected = try {
            withTimeoutOrNull(wait) {
                combine(
                    toggles.observe(HarnessEnabled),
                    machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                ) { enabled, library ->
                    HarnessActiveSnapshot(enabled, library).selection()
                }.filterNotNull().first()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        } ?: fail(HarnessActivationUnavailable())
        if (unavailability.compareAndSet(expect = true, update = false)) {
            log.i { "harness activation is available again" }
        }
        return selected
    }

    /** Reports the transition once and throws the already reported marker instead of the original error. */
    private fun fail(error: Exception): Nothing {
        if (unavailability.compareAndSet(expect = false, update = true)) {
            log.w(error) { "Harness activation unavailable; harness tools and content are closed" }
        } else {
            log.v { "harness activation still unavailable" }
        }
        throw error as? HarnessActivationUnavailable ?: HarnessActivationUnavailable()
    }
}

/**
 * Already reported by the access that threw it; consumers close harness output and log only VERBOSE. As a
 * [ToolPolicyUnavailable] it also keeps the facade's fail-closed policy resolver from reporting it again.
 */
internal class HarnessActivationUnavailable : ToolPolicyUnavailable("Harness activation is unavailable")

/** True for unavailability the access has already reported; any other failure is the consumer's to report. */
internal fun Throwable.isReportedUnavailability(): Boolean = this is HarnessActivationUnavailable

private val COLD_START_WAIT = 1_000.milliseconds

/** Enough for hot flows to emit their current combination; never a wait for new evidence. */
private val UNAVAILABLE_PROBE = 50.milliseconds
