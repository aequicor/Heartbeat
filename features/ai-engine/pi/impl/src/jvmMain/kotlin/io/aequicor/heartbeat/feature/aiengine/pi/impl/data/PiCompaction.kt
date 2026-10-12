package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pi summarizes on `compact` with optional custom instructions. */
internal object PiCompactsContext : CompactsContext {
    override val isInstructionsSupported: Boolean = true
}

/** RPC `compact` (`docs/rpc-commands.md`); instructions are user data and never logged. */
internal const val PI_COMPACT_COMMAND = "compact"

/** Fields of the `compact` command for [compaction]. */
internal fun piCompactionFields(compaction: ContextCompaction): JsonObject = JsonObject(
    buildMap { compaction.instructions?.let { put("customInstructions", JsonPrimitive(it)) } },
)

/**
 * Outcome of a `compaction_end` event (`docs/json.md`): a result means success, `aborted` a confirmed cancellation,
 * and anything else a failure whose `errorMessage` stays out of the outcome (it may quote the conversation).
 */
internal fun piCompactionOutcome(record: JsonObject): TurnOutcome = when {
    record["result"] is JsonObject -> TurnOutcome.Completed
    record.string("aborted") == "true" -> TurnOutcome.Cancelled
    else -> TurnOutcome.Failed(EngineFailure.Engine(EngineFailureReason.Crashed))
}

/**
 * The compaction turn of one session, confined to the session's main dispatcher ([scope]). Pi answers `compact`
 * only after summarizing, which may outlast the command timeout, so the turn settles on `compaction_end`; a refused
 * command settles it as failed, or as cancelled while the turn is being interrupted. Automatic compactions inside
 * a prompt turn never settle that turn.
 */
internal class PiCompactionTurns(
    private val scope: CoroutineScope,
    private val current: () -> TurnId?,
    private val state: () -> ActiveSessionState,
    private val finish: suspend (TurnOutcome) -> Unit,
) {
    private val log = Log.tag("PiCompaction")
    private var active: TurnId? = null

    /** Sends `compact` for the current turn, which [PiPromptResources.submit] has just admitted. */
    fun start(origin: PiConnection, compaction: ContextCompaction) {
        val turn = checkNotNull(current()) { "A compaction is admitted for the current turn" }
        log.i { "Pi compaction started: instructions=${compaction.instructions?.length ?: 0}" }
        active = turn
        scope.launch {
            try {
                origin.command(PI_COMPACT_COMMAND, piCompactionFields(compaction))
            } catch (error: EngineException) {
                // A timeout leaves the summary running: compaction_end settles it.
                if (error.failure is EngineFailure.Transport) return@launch log.w(error) { "Pi compaction pending" }
                log.w(error) { "Pi compaction refused" }
                refused(turn, error.failure)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Pi compaction failed" }
                refused(turn, EngineFailure.Engine(EngineFailureReason.Crashed))
            }
        }
    }

    private suspend fun refused(turn: TurnId, failure: EngineFailure) {
        if (!claim(turn)) return
        finish(if (state() is ActiveSessionState.Interrupting) TurnOutcome.Cancelled else TurnOutcome.Failed(failure))
    }

    suspend fun ended(record: JsonObject, turn: TurnId?) {
        if (turn == null || !claim(turn)) return
        val outcome = piCompactionOutcome(record)
        log.i { "Pi compaction ended: ${outcome::class.simpleName.orEmpty()}" }
        finish(outcome)
    }

    private fun claim(turn: TurnId): Boolean {
        if (active != turn || current() != turn) return false
        active = null
        return true
    }
}
