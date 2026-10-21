package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructionBounds
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import kotlinx.coroutines.flow.MutableStateFlow

/** Guarded by the dispatcher's invocation mutex. No transcript role or adapter-provided text enters this ledger. */
internal class BoundUserInstructions {
    private val log = Log.tag("BoundUserInstructions")
    private val histories = mutableMapOf<SessionRef, TrustedUserInstructions>()
    private val snapshots = mutableMapOf<Pair<SessionRef, RequestId>, String?>()
    private val bound = mutableMapOf<Pair<SessionRef, RequestId>, TrustedUserInstructions>()
    private val revisions = MutableStateFlow<Map<SessionRef, Long>>(emptyMap())

    fun inherit(session: SessionRef, request: RequestId) {
        val key = session to request
        if (key !in snapshots) snapshots[key] = histories[session].render()
    }

    /** Explicit host input for callers that own a fresh in-memory conversation. Durable hosts bind full snapshots. */
    fun bind(session: SessionRef, request: RequestId, instruction: String) {
        val previous = histories[session]
        if (previous == TrustedUserInstructions.Incomplete) {
            bind(session, request, TrustedUserInstructions.Incomplete)
            return
        }
        val messages = (previous as? TrustedUserInstructions.Complete)?.messages.orEmpty()
        val existing = messages.firstOrNull { it.request == request }
        check(existing == null || existing.text == instruction) { "Human instruction binding changed" }
        if (existing != null) return
        val next = messages + TrustedUserInstruction(request, instruction)
        bind(session, request, TrustedUserInstructions.Complete(next))
    }

    /** Captures immutable originals and refuses a later snapshot that drops an already known restriction. */
    fun bind(session: SessionRef, request: RequestId, instructions: TrustedUserInstructions) {
        val key = session to request
        val captured = instructions.capture()
        val previousBinding = bound[key]
        check(previousBinding == null || previousBinding == captured) { "Human authority snapshot binding changed" }
        if (previousBinding != null) return
        val previous = histories[session]
        val complete = captured as? TrustedUserInstructions.Complete
        val previousMessages = (previous as? TrustedUserInstructions.Complete)?.messages.orEmpty()
        val next = when {
            previous == TrustedUserInstructions.Incomplete -> TrustedUserInstructions.Incomplete

            complete != null && complete.messages.take(previousMessages.size) != previousMessages ->
                TrustedUserInstructions.Incomplete

            else -> captured
        }
        histories[session] = next
        bound[key] = captured
        snapshots[key] = next.render()
    }

    fun get(session: SessionRef, request: RequestId?): String? = request?.let { snapshots[session to it] }

    /** Thread-safe non-suspending check also covers unbound native calls awaiting a manual decision. */
    fun revision(session: SessionRef): Long = revisions.value[session] ?: 0L

    /** Revokes active snapshots as well as future inheritance; a later "continue" cannot restore completeness. */
    fun invalidate(session: SessionRef) {
        histories[session] = TrustedUserInstructions.Incomplete
        snapshots.keys.filter { it.first == session }.forEach { snapshots[it] = null }
        revisions.value += session to (revision(session) + 1L)
        log.v { "Invalidated human authority and advanced its execution revision" }
    }

    fun release(session: SessionRef, request: RequestId?) {
        request?.let {
            snapshots.remove(session to it)
            bound.remove(session to it)
        }
    }
}

private fun TrustedUserInstructions.capture(): TrustedUserInstructions = when (this) {
    TrustedUserInstructions.Incomplete -> this

    is TrustedUserInstructions.Complete -> {
        val captured = messages.toList()
        if (captured.size > TrustedUserInstructionBounds.MAX_MESSAGES ||
            captured.sumOf { it.text.length.toLong() } > TrustedUserInstructionBounds.MAX_TEXT_CHARACTERS ||
            captured.map { it.request }.distinct().size != captured.size
        ) {
            TrustedUserInstructions.Incomplete
        } else {
            TrustedUserInstructions.Complete(captured)
        }
    }
}

private fun TrustedUserInstructions?.render(): String? =
    (this as? TrustedUserInstructions.Complete)?.messages?.takeIf { it.isNotEmpty() }
        ?.joinToString("\n\n--- Human message ---\n\n") { it.text }
