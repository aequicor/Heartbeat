package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptPreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptReceipt
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whole acknowledged blocks take precedence over best-effort legacy notes within one shared character budget. */
internal fun composeSessionPrompt(
    additions: List<SessionPromptAddition?>,
    limit: Int = 8192,
): SessionPromptPreparation? {
    val text = StringBuilder()
    val receipts = mutableListOf<SessionPromptReceipt>()
    for (addition in additions.filterNotNull().sortedBy { it.receipt == null }) {
        val receipt = addition.receipt?.let(::OncePromptReceipt)
        val separator = if (text.isEmpty()) "" else "\n\n"
        val remaining = (limit - text.length - separator.length).coerceAtLeast(0)
        val isIncomplete = receipt != null && addition.text.length > remaining
        if (addition.text.isBlank() || remaining == 0 || isIncomplete) {
            receipt?.discarded()
            continue
        }
        text.append(separator).append(addition.text.take(remaining))
        if (receipt != null) receipts += receipt
    }
    return text.toString().takeIf { it.isNotBlank() }?.let { SessionPromptPreparation(it, receipts) }
}

/** A duplicate native Accepted must not look like a distinct superseded delivery to its owner. */
@OptIn(ExperimentalAtomicApi::class)
private class OncePromptReceipt(private val delegate: SessionPromptReceipt) : SessionPromptReceipt {
    private val finished = AtomicBoolean(false)
    private val log = Log.tag("SessionHooks")

    override fun accepted(context: SessionHookContext, contextRevision: String?) = finish {
        delegate.accepted(context, contextRevision)
    }

    override fun discarded() = finish { delegate.discarded() }

    private inline fun finish(block: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(PromptReceiptFailure(error::class.simpleName.orEmpty())) { "Prompt receipt callback failed" }
        }
    }
}

private class PromptReceiptFailure(type: String) : Exception(type)
