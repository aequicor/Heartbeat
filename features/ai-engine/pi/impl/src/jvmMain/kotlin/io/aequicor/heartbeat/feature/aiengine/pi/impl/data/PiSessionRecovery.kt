package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val log = Log.tag("PiSessionRecovery")

/** Main-confined transcript location and proof that the native session has never received a prompt. */
internal class PiSessionContinuation {
    private var nativeId: String? = null
    private var sessionFile: String? = null
    private var isEmptyRestartAllowed = false
    private var persistedTranscript: suspend (String) -> String? = { null }

    fun start(snapshot: JsonObject, isNew: Boolean, persisted: suspend (String) -> String?) {
        nativeId = snapshot.string("sessionId")
        capture(snapshot)
        isEmptyRestartAllowed = isNew
        persistedTranscript = persisted
    }

    /** Same-id empty recreation can allocate a different path; future recovery must follow that path. */
    fun capture(snapshot: JsonObject) {
        sessionFile = snapshot.string("sessionFile")
    }

    /** Delivery can fail after Pi has started; never reconstruct an empty session after this boundary. */
    fun submitted() {
        isEmptyRestartAllowed = false
    }

    suspend fun refresh(tools: PiToolPlans, launched: Set<String>, instructions: String?): Boolean {
        // get_state may advertise an allocated path before Pi writes its first assistant message.
        // Existing history requires a stored transcript; an empty session can be recreated with its exact id.
        val refresh = tools.refresh(launched, instructions, isEmptyRestartAllowed, ::persisted) ?: return false
        refresh.transcript?.let { sessionFile = it }
        return true
    }

    suspend fun file(): String? = if (isEmptyRestartAllowed) {
        persisted()
    } else {
        sessionFile ?: piFailure(EngineFailure.Session(SessionFailureReason.NotResumable))
    }

    private suspend fun persisted(): String? = nativeId?.let { persistedTranscript(it) }
}

/** A null file is only for a never-submitted session reopened with its explicit id after the old process exited. */
internal suspend fun PiSessionConfiguration.reconnect(
    file: String?,
    nativeId: String?,
    open: suspend () -> PiConnection,
    ensureOpen: suspend () -> Unit,
    discarded: () -> Unit,
): Pair<PiConnection, JsonObject> {
    ensureOpen()
    val fresh = open()
    var isConfigured = false
    try {
        ensureOpen()
        file?.let { fresh.reattach(it) }
        // switch_session can create a new session if the original file was never persisted.
        if (fresh.command("get_state").string("sessionId") != nativeId) {
            piFailure(EngineFailure.Session(SessionFailureReason.Changed))
        }
        val snapshot = restore(fresh)
        ensureOpen()
        isConfigured = true
        return fresh to snapshot
    } catch (error: EngineException) {
        log.w(error) { "Pi session recovery could not reattach the transcript" }
        throw error
    } finally {
        if (!isConfigured) {
            discarded()
            fresh.close()
        }
    }
}

/** Switches this connection to the native transcript [file]; Pi reports a refused switch as `cancelled`. */
internal suspend fun PiConnection.reattach(file: String): PiConnection = apply {
    val switched = command("switch_session", JsonObject(mapOf("sessionPath" to JsonPrimitive(file))))
    if ((switched["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
        piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    }
}

/** Native startup result before live history or state is published. */
internal data class PiStartedSession(val nativeId: String, val snapshot: JsonObject, val branch: PiStoredBranch?)

internal suspend fun PiConnection.startSession(
    configuration: PiSessionConfiguration,
    transcript: PiTranscript?,
): PiStartedSession {
    val stored = transcript?.let { reattach(it.file).storedConversation() }
    command("set_model", configuration.modelFields(configuration.target.model))
    val snapshot = command("get_state")
    val id = snapshot.string("sessionId")
        ?: piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
    if (transcript != null && transcript.ref.nativeId != id) {
        piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    }
    return PiStartedSession(id, snapshot, stored)
}

/** Only an owned live process or a confirmed replacement may use this idle projection as termination evidence. */
internal fun piReconciliation(
    snapshot: JsonObject,
    turn: Turn?,
    permissions: Collection<PermissionRequest>,
): ActiveSessionIntent.Internal.Synchronized {
    val isBusy = (snapshot["isStreaming"] as? JsonPrimitive)?.booleanOrNull == true ||
        (snapshot["isCompacting"] as? JsonPrimitive)?.booleanOrNull == true
    if (isBusy && turn == null) piFailure(EngineFailure.Session(SessionFailureReason.Changed))
    val completed = if (!isBusy && turn != null) {
        ActiveSessionIntent.Internal.Finished(turn.id, TurnOutcome.Unknown)
    } else {
        null
    }
    return ActiveSessionIntent.Internal.Synchronized(
        if (isBusy) turn else null,
        if (isBusy) permissions.filter { it.turn == turn?.id } else emptyList(),
        completed,
    )
}

/** Assistant messages report a provisional result; only agent_settled makes it terminal. */
internal fun piMessageOutcome(record: JsonObject): TurnOutcome? {
    val message = record["message"] as? JsonObject ?: return null
    if (message.string("role") != "assistant") return null
    return when (message.string("stopReason")) {
        "aborted" -> TurnOutcome.Cancelled
        "error" -> TurnOutcome.Failed(piProviderFailure(message.string("errorMessage"), message.string("provider")))
        else -> TurnOutcome.Completed
    }
}
