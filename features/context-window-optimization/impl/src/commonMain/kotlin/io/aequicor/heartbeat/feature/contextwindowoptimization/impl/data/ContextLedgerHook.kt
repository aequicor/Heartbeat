package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptReceipt
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.render
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Delivers the progress ledger to sessions whose engines keep their context natively. A ledger version reaches the
 * agent once per context revision: after a compaction or a new process the revision changes and the ledger is sent
 * again with the next prompt. Without a provable revision it accompanies every prompt. Engines that rebuild their
 * instructions every turn read the ledger from the hosted instructions instead ([ContextAgentTools]), so it never
 * accumulates in their replayed history. Deliveries are remembered in memory only: after a restart the ledger is
 * sent once more.
 */
internal class ContextLedgerHook(
    private val view: ContextOptimizationView,
    private val isEnabled: () -> Boolean,
    private val isRefreshed: (SessionRef) -> Boolean,
) : SessionHook {
    private val log = Log.tag("ContextLedgerHook")
    private val delivered = MutableStateFlow(emptyMap<SessionRef, Delivery>())

    override val isIntercepting: Boolean get() = isEnabled()

    @HighFrequency
    override suspend fun preparePrompt(
        context: SessionHookContext,
        text: String,
        contextRevision: String?,
    ): SessionPromptAddition? {
        if (!isEnabled() || text.startsWith('/') || isRefreshed(context.session)) return null
        val ledgers = (view.state.value as? ContextOptimizationState.Ready)?.ledgers.orEmpty()
        val ledger = ledgers[context.session] ?: return null
        val delivery = Delivery(contextRevision, ledger.version)
        if (contextRevision != null && delivered.value[context.session] == delivery) return null
        log.v { "deliver the progress ledger: version=${ledger.version} revision=${contextRevision != null}" }
        return SessionPromptAddition(ledger.render(), Receipt(context.session, ledger.version))
    }

    private inner class Receipt(private val session: SessionRef, private val version: Long) : SessionPromptReceipt {
        override fun accepted(context: SessionHookContext, contextRevision: String?) {
            delivered.update { it + (session to Delivery(contextRevision, version)) }
        }
    }

    private data class Delivery(val revision: String?, val version: Long)
}
