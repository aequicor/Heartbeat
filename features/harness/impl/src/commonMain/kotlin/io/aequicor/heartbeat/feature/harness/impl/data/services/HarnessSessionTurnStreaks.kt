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
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-wide bound that does not depend on call carriers: consecutive accepted turns whose request carries harness
 * ancestry (script sends, wakes and their relays) without an intervening turn of any other origin, normally the
 * user's. Author code that escaped its carrier loses its chain, but it cannot make a harness turn look like a user
 * turn, so at most [HarnessLimits.SEND_CHAIN] such turns follow each other; further sends and wakes are refused.
 * Memory only (a restart forgives one streak); above [MAX_TRACKED_SESSIONS] the least recently counted session is
 * forgotten.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessSessionTurnStreaks {
    private val streaks = MutableStateFlow<Map<SessionRef, Int>>(emptyMap())
    private val log = Log.tag("HarnessServices")

    fun isExhausted(session: SessionRef): Boolean = (streaks.value[session] ?: 0) >= HarnessLimits.SEND_CHAIN

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
        val isHarnessTurn = origin != null && (origin.isHookRestricted || origin.sendChain.isNotEmpty())
        if (isHarnessTurn) streaks.harnessTurnStarted(session) else streaks.otherTurnStarted(session)
    }
}

private const val MAX_TRACKED_SESSIONS = 256
