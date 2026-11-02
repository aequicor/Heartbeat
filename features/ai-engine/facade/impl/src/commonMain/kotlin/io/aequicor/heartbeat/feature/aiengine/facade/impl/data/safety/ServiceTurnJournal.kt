package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Strict canonical service attempts. Only calls started by this process are live; restart never grants send rights. */
internal class ServiceTurnJournal(private val store: KeyValueStore) : SessionExecutionAuthority {
    private val log = Log.tag("ServiceTurnJournal")
    private val mutex = Mutex()
    private val live = mutableSetOf<SessionExecutionBinding>()
    private val snapshot = MutableStateFlow<ServiceRecords?>(null)
    val hasIsolation: Boolean get() = snapshot.value?.attempts?.any { it.stage != ServiceStage.Settled } ?: true
    override val provider: String = SERVICE_PROVIDER

    init {
        require(store.spec.readPolicy == KeyValueReadPolicy.Strict) { "Service authority requires strict storage" }
    }

    suspend fun begin(turn: ServiceTurn, nativeTools: List<String>): ServiceAttempt = mutex.withLock {
        val records = read()
        val binding = SessionExecutionBinding(SessionExecutionInstance(provider, turn.request.value))
        check(
            records.attempts.none { it.binding == binding || it.request == turn.request },
        ) { "Service attempt exists" }
        val attempt = ServiceAttempt(
            binding,
            turn.request,
            turn.target,
            PromptRequest(turn.request, listOf(ContentPart.Text(turn.text)), trust = TrustLevel.Ask),
            nativeTools,
        )
        val next = records.copy(attempts = records.attempts + attempt)
        store.set(ServiceRecordsKey, next)
        snapshot.value = next
        live += binding
        log.d { "isolated service attempt recorded before creation" }
        attempt
    }

    suspend fun get(binding: SessionExecutionBinding): ServiceAttempt = mutex.withLock { find(read(), binding) }

    suspend fun attempts(): List<ServiceAttempt> = mutex.withLock { read().attempts }

    suspend fun orphaned(): List<ServiceAttempt> = mutex.withLock {
        read().attempts.filter { it.binding !in live && it.stage != ServiceStage.Settled }
    }

    suspend fun isolation(ref: SessionRef): List<String>? = mutex.withLock {
        val records = read().attempts.filter { it.session == ref && it.stage != ServiceStage.Settled }
        check(records.size <= 1) { "Ambiguous service isolation" }
        records.singleOrNull()?.nativeTools
    }

    suspend fun opened(binding: SessionExecutionBinding, ref: SessionRef): ServiceAttempt = change(binding) {
        check(ref.engine == it.target.engine && (it.session == null || it.session == ref)) { "Service ref changed" }
        check(it.stage == ServiceStage.Opening || it.session == ref) { "Service opening already consumed" }
        if (it.stage == ServiceStage.Opening) it.copy(stage = ServiceStage.Opened, session = ref) else it
    }

    suspend fun prepare(binding: SessionExecutionBinding): PromptRequest = mutex.withLock {
        val records = read()
        val attempt = find(records, binding)
        check(isLive(attempt) && attempt.stage == ServiceStage.Opened) { "Service send is unavailable" }
        write(records, attempt.copy(stage = ServiceStage.Prepared))
        checkNotNull(attempt.prompt)
    }

    suspend fun accepted(binding: SessionExecutionBinding, turn: TurnId): ServiceAttempt = change(binding) {
        check(it.stage == ServiceStage.Sending || it.turn == turn) { "Service request did not enter native send" }
        check(it.turn == null || it.turn == turn) { "Service turn changed" }
        if (it.stage == ServiceStage.Settled) it else it.copy(stage = ServiceStage.Accepted, turn = turn)
    }

    suspend fun retire(binding: SessionExecutionBinding): ServiceAttempt = mutex.withLock {
        live -= binding
        val records = read()
        val attempt = find(records, binding).copy(isRetired = true)
        write(records, attempt)
        attempt
    }

    /** Known native terminal or a drained pre-entry stage, never timeout/Closed/Unknown. */
    suspend fun settled(binding: SessionExecutionBinding, turn: Turn? = null): ServiceAttempt = change(binding) {
        if (turn == null) {
            check(it.stage in setOf(ServiceStage.Opening, ServiceStage.Opened, ServiceStage.Prepared)) {
                "Service request may have entered native send"
            }
        } else {
            check(
                turn.request == it.request && turn.target == it.target &&
                    turn.outcome != null && turn.outcome != TurnOutcome.Unknown,
            ) { "Service terminal evidence mismatches" }
        }
        it.copy(stage = ServiceStage.Settled, prompt = null, turn = it.turn ?: turn?.id)
    }

    /** Explicit native Stop may prove quiescence even when the execution result is Unknown. */
    suspend fun stopped(binding: SessionExecutionBinding, proof: OwnedTurnStop.Confirmed): ServiceAttempt = change(
        binding,
    ) {
        check(proof.turn.request == it.request && proof.turn.target == it.target) { "Service Stop mismatches" }
        it.copy(stage = ServiceStage.Settled, prompt = null, turn = it.turn ?: proof.turn.id)
    }

    override suspend fun permits(binding: SessionExecutionBinding): Boolean = mutex.withLock {
        read().attempts.singleOrNull { it.binding == binding }?.let(::isLive) == true
    }

    override suspend fun permitsOperation(
        binding: SessionExecutionBinding,
        operation: SessionExecutionOperation,
    ): Boolean = mutex.withLock {
        val attempt = read().attempts.singleOrNull { it.binding == binding } ?: return@withLock false
        isLive(attempt) && when (operation) {
            SessionExecutionOperation.Open -> attempt.stage == ServiceStage.Opening

            SessionExecutionOperation.Submit -> attempt.stage == ServiceStage.Prepared

            SessionExecutionOperation.Steer, SessionExecutionOperation.Configure, SessionExecutionOperation.Respond ->
                false
        }
    }

    override suspend fun permitsPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        target: EngineTarget,
        request: PromptRequest,
    ): Boolean = mutex.withLock {
        read().attempts.singleOrNull { it.binding == binding }?.let {
            isLive(it) && it.stage == ServiceStage.Prepared && it.session == session &&
                it.target == target && it.prompt == request
        } == true
    }

    override suspend fun enterPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        request: RequestId,
    ): Boolean = mutex.withLock {
        val records = read()
        val attempt = records.attempts.singleOrNull { it.binding == binding } ?: return@withLock false
        val isMatching = attempt.session == session && attempt.request == request
        if (!isLive(attempt) || attempt.stage != ServiceStage.Prepared || !isMatching) {
            return@withLock false
        }
        write(records, attempt.copy(stage = ServiceStage.Sending))
        true
    }

    private fun isLive(attempt: ServiceAttempt): Boolean = attempt.binding in live && !attempt.isRetired &&
        attempt.stage != ServiceStage.Settled

    private suspend fun change(
        binding: SessionExecutionBinding,
        update: (ServiceAttempt) -> ServiceAttempt,
    ): ServiceAttempt = mutex.withLock {
        val records = read()
        val next = update(find(records, binding))
        write(records, next)
        next
    }

    private fun find(records: ServiceRecords, binding: SessionExecutionBinding): ServiceAttempt =
        checkNotNull(records.attempts.singleOrNull { it.binding == binding }) { "Service attempt is unavailable" }

    private suspend fun write(records: ServiceRecords, attempt: ServiceAttempt) {
        val next = records.copy(
            attempts = records.attempts.map {
                if (it.binding == attempt.binding) attempt else it
            },
        )
        store.set(ServiceRecordsKey, next)
        snapshot.value = next
        log.v { "service attempt advanced" }
    }

    private suspend fun read(): ServiceRecords = (store.get(ServiceRecordsKey) ?: ServiceRecords()).also {
        check(it.version == 1) { "Unsupported service journal version" }
        check(it.attempts.map { a -> a.binding }.distinct().size == it.attempts.size) { "Ambiguous service identity" }
        check(it.attempts.map { a -> a.request }.distinct().size == it.attempts.size) { "Ambiguous service request" }
        snapshot.value = it
        log.v { "service journal snapshot loaded" }
    }
}

internal const val SERVICE_PROVIDER = "isolated-service"
internal val ServiceTurnSpec = KeyValueSpec("engine_service_turns", readPolicy = KeyValueReadPolicy.Strict)
private val ServiceRecordsKey = jsonKey("attempts", ServiceRecords.serializer())

@Serializable
internal data class ServiceAttempt(
    val binding: SessionExecutionBinding,
    val request: RequestId,
    val target: EngineTarget,
    val prompt: PromptRequest?,
    val nativeTools: List<String>,
    val stage: ServiceStage = ServiceStage.Opening,
    val session: SessionRef? = null,
    val turn: TurnId? = null,
    val isRetired: Boolean = false,
) {
    init {
        require(binding.instance.provider == SERVICE_PROVIDER && binding.logicalSession == null)
        require(binding.instance.id == request.value && (prompt == null || prompt.id == request))
        require(session == null || session.engine == target.engine)
        require(stage == ServiceStage.Opening || session != null)
        require(stage != ServiceStage.Accepted || turn != null)
    }
    override fun toString(): String = "ServiceAttempt(stage=$stage retired=$isRetired)"
}

@Serializable
internal enum class ServiceStage { Opening, Opened, Prepared, Sending, Accepted, Settled }

@Serializable
private data class ServiceRecords(val version: Int = 1, val attempts: List<ServiceAttempt> = emptyList())
