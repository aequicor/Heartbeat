package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Best-effort, bounded session-wide limit that does not depend on call carriers: consecutive accepted turns whose
 * request carries harness ancestry (script sends, wakes and their relays) without an intervening turn of any other
 * origin, normally the user's. Author code that escaped its carrier loses its chain, but it cannot make a harness
 * turn look like a user turn. Once [HarnessLimits.SEND_CHAIN] such turns were counted, further sends and wakes are
 * refused and pending harness-chain wakes are dropped until another turn starts. It is not an exact cap: turns
 * observed without request identity or readable ancestry are not counted, a wake whose native submission
 * already started is not revoked, and the counter lives in memory (a restart forgives one streak; above
 * [MAX_TRACKED_SESSIONS] the least recently counted session is forgotten).
 */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessSessionTurnStreaks {
    private val streaks = MutableStateFlow<Map<SessionRef, Int>>(emptyMap())
    private val log = Log.tag("HarnessServices")

    fun isExhausted(session: SessionRef): Boolean = streaks.value.isExhausted(session)

    /** Current state immediately, then each change, so live admissions follow the session's streak. */
    fun exhausted(session: SessionRef): Flow<Boolean> = streaks.map { it.isExhausted(session) }.distinctUntilChanged()

    @HighFrequency
    fun harnessTurnStarted(session: SessionRef) {
        log.v { "count harness-scheduled session turn" }
        streaks.update { current ->
            val count = minOf((current[session] ?: 0) + 1, HarnessLimits.SEND_CHAIN)
            val next = current - session + (session to count)
            if (next.size > MAX_TRACKED_SESSIONS) next - next.keys.first() else next
        }
    }

    @HighFrequency
    fun otherTurnStarted(session: SessionRef) {
        log.v { "reset harness session turn streak" }
        streaks.update { it - session }
    }
}

/** Observes accepted turns only to keep [HarnessSessionTurnStreaks]; it never intercepts or grants anything. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessSessionTurnObserver(
    private val gate: HarnessEventGate,
    private val ancestry: Lazy<HarnessRequestAncestry>,
    private val streaks: HarnessSessionTurnStreaks,
) : SessionHook {
    private val log = Log.tag("HarnessServices")
    override val isObserving: Boolean get() = gate.isEnabled

    /** A turn without a request identity or with unreadable ancestry neither counts nor resets (fail closed). */
    @HighFrequency
    override suspend fun observe(event: SessionLifecycle) {
        if (event !is SessionLifecycle.TurnStarted) return
        val session = event.context.session
        val request = event.context.request ?: return
        val origin = try {
            ancestry.value.lookup(session, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Turn ancestry is unavailable; the harness turn streak is kept" }
            return
        }
        if (origin?.isHarnessChain == true) streaks.harnessTurnStarted(session) else streaks.otherTurnStarted(session)
    }
}

/** A turn caused by harness code (a hook relay or a script send/wake chain) rather than by the user. */
internal val HarnessCallOrigin.isHarnessChain: Boolean get() = isHookRestricted || sendChain.isNotEmpty()

private fun Map<SessionRef, Int>.isExhausted(session: SessionRef): Boolean =
    (this[session] ?: 0) >= HarnessLimits.SEND_CHAIN

private const val MAX_TRACKED_SESSIONS = 256
