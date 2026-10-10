package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptAddition
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPromptReceipt
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Serializes host preparation and synchronous acceptance enqueues in profile lifetime. Begin is durable before
 * a block escapes; an uncertain send leaves no accepted hash. Receipt callbacks contain no script code or IO.
 * Native revision is part of the digest, so reopening or compaction conservatively requests fresh delivery.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HarnessContextDelivery(
    private val storage: HarnessDeliveryStorage,
    scope: CoroutineScope,
    private val digest: (String) -> String,
) {
    private val log = Log.tag("HarnessDelivery")
    private val commands = Channel<DeliveryCommand>(Channel.UNLIMITED)
    private val dirty = mutableSetOf<SessionRef>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (command in commands) process(command)
        }.also { job ->
            job.invokeOnCompletion { cause ->
                val failure = cause ?: CancellationException("Harness delivery stopped")
                commands.close(failure)
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    if (command is DeliveryCommand.Prepare) command.reply.completeExceptionally(failure)
                }
            }
        }
    }

    suspend fun prepare(
        context: SessionHookContext,
        revision: String?,
        content: HarnessContent,
        instructions: String = "",
    ): SessionPromptAddition? {
        val reply = CompletableDeferred<SessionPromptAddition?>()
        commands.send(DeliveryCommand.Prepare(context, revision, content, instructions, reply))
        return reply.await()
    }

    private suspend fun process(command: DeliveryCommand) {
        try {
            when (command) {
                is DeliveryCommand.Prepare -> command.reply.complete(compose(command))
                is DeliveryCommand.Accepted -> accepted(command)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(DeliveryFailure(error::class.simpleName.orEmpty())) { "Harness delivery journal unavailable" }
        } finally {
            // A failed/cancelled preparation may have invalidated storage but must never expose its block.
            if (command is DeliveryCommand.Prepare) command.reply.complete(null)
        }
    }

    private suspend fun compose(command: DeliveryCommand.Prepare): SessionPromptAddition? {
        val context = command.context
        if (context.session in dirty) {
            storage.reset(context.session, isDeliveryPending = true)
            dirty.remove(context.session)
        }
        val snapshot = storage.snapshot(context.session)
        val content = command.content
        val active = content.markers.map { it.harness }.toSet()
        val disabled = snapshot.pendingDisabled + snapshot.markers.filter { it.harness !in active }.map { it.name }
        val hash = digest(content.canonical(command.revision, command.instructions))
        val isAccepted = command.revision != null && snapshot.activeSetSha == hash
        val isEmpty = active.isEmpty() && !snapshot.isDeliveryPending
        if (disabled.isEmpty() && (isEmpty || isAccepted)) return null
        val prefix = listOf(HARNESS_CONTEXT_PROTOCOL) + disabled.sortedBy { it.value }.map {
            "Харнесс ${it.value} отключён; его прежние инструкции больше не действуют."
        }
        val emptyNotice = if (active.isEmpty()) listOf("Все харнессы отключены.") else emptyList()
        val block = content.block(prefix + emptyNotice, command.instructions)
        return storage.begin(context.session, snapshot.generation)?.let { begun ->
            val token = DeliveryToken(context, command.revision, begun.generation, hash, content.markers, disabled)
            SessionPromptAddition(block.text, receipt(token))
        }
    }

    private fun receipt(token: DeliveryToken): SessionPromptReceipt = object : SessionPromptReceipt {
        private val finished = AtomicBoolean(false)
        override fun accepted(context: SessionHookContext, contextRevision: String?) {
            if (context == token.context && finished.compareAndSet(false, true)) {
                commands.trySend(DeliveryCommand.Accepted(token, contextRevision))
            }
        }
        override fun discarded() {
            finished.compareAndSet(false, true)
        }
    }

    private suspend fun accepted(command: DeliveryCommand.Accepted) {
        val token = command.token
        dirty.add(token.context.session)
        if (token.revision == null || token.revision != command.revision) {
            storage.reset(token.context.session, isDeliveryPending = true)
            dirty.remove(token.context.session)
            return
        }
        val isConfirmed = storage.accepted(
            token.context.session,
            token.generation,
            token.digest,
            token.markers,
            token.disabled,
        )
        // A distinct superseded native acceptance can have changed the actual context; an old hash cannot survive it.
        if (!isConfirmed) storage.reset(token.context.session, isDeliveryPending = true)
        dirty.remove(token.context.session)
    }
}

private sealed interface DeliveryCommand {
    data class Prepare(
        val context: SessionHookContext,
        val revision: String?,
        val content: HarnessContent,
        val instructions: String,
        val reply: CompletableDeferred<SessionPromptAddition?>,
    ) : DeliveryCommand {
        override fun toString(): String = "Prepare(***)"
    }
    data class Accepted(val token: DeliveryToken, val revision: String?) : DeliveryCommand {
        override fun toString(): String = "Accepted(***)"
    }
}

private data class DeliveryToken(
    val context: SessionHookContext,
    val revision: String?,
    val generation: String,
    val digest: String,
    val markers: List<HarnessDeliveryMarker>,
    val disabled: Set<HarnessName>,
) {
    override fun toString(): String = "DeliveryToken(***)"
}

private class DeliveryFailure(type: String) : Exception(type)

internal const val HARNESS_CONTEXT_PROTOCOL =
    "Этот блок заменяет все прежние инструкции харнесса. Действуют только перечисленные ниже харнессы."
