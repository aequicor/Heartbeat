package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.RelaysPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** Borrows exactly one matching open handle and awaits its native acknowledgement; never opens or resumes. */
internal class BorrowedPermissionRelay(private val ref: SessionRef, private val handles: () -> List<ActiveSession>) :
    RelaysPermissions {
    override suspend fun respond(relay: PermissionRelay, decision: PermissionDecision) = withTimeout(30.seconds) {
        check(relay.session == ref && relay.accepts(decision))
        val handle = checkNotNull(
            handles().singleOrNull {
                val pending = it.state.value as? ActiveSessionState.AwaitingUserAction
                it.ref == ref && pending?.turn?.request == relay.request && pending.turn.target == relay.target &&
                    relay.permission in pending.requests
            },
        ) { "Exact permission handle is unavailable or ambiguous" }
        coroutineScope {
            val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
                handle.state.mapNotNull { state ->
                    val turn = state.activeTurn() ?: state.lastTurn()
                    when {
                        turn?.id != decision.turn || turn.request != relay.request || turn.target != relay.target ->
                            false

                        decision.request in turn.resolvedPermissions -> true

                        turn.outcome != null || state is ActiveSessionState.Unavailable -> false

                        else -> null
                    }
                }.first()
            }
            try {
                handle.features.resolve(RelaysPermissions).orFail().respond(relay, decision)
                check(acknowledgement.await()) { "Permission relay was not acknowledged" }
            } finally {
                acknowledgement.cancel()
            }
        }
    }
}
