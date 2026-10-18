package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/** Exact local lifetime, never a source of the shared task identity. */
@Serializable
internal data class OwnershipLocalProcess(val harness: HarnessId, val process: ProcessId, val incarnation: String) {
    override fun toString(): String = "OwnershipLocalProcess(***)"
}

/** Host-approved enrollment; a script-owned ProcessRecord cannot create or remove this binding. */
@Serializable
internal data class OwnershipLocalBinding(
    val local: OwnershipLocalProcess,
    val identity: GitHubTaskIdentity,
    val fence: OwnershipFence,
) {
    init {
        require(fence.key.value == identity.canonical.encodeUtf8().sha256().hex())
    }
    override fun toString(): String = "OwnershipLocalBinding(***)"
}

/** Fixed proposal persisted BEFORE coordinator admission; its sequence can never be replaced on retry. */
@Serializable
internal data class OwnershipNativeIntent(val local: OwnershipLocalProcess, val permit: OwnershipPermit) {
    override fun toString(): String = "OwnershipNativeIntent(***)"
}

/**
 * Protected profile-lifetime ownership journal, inaccessible to ScriptData. It never evicts bindings or native
 * intents, including completed intents: forgetting one could turn a retry into a second native dispatch. Quota
 * exhaustion fails closed. Host identity and exact proposals survive restart; unreadable storage never creates a
 * fresh host. Only explicit host enrollment/import may call [bind], after consent and coordinator verification.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class ProtectedOwnershipJournal(private val secrets: SecretStore) {
    private val mutex = Mutex()
    private val log = Log.tag("OwnershipJournal")

    suspend fun host(): ExecutorHostId = guarded {
        mutex.withLock {
            val stored = load()
            if (stored != null) {
                stored.host
            } else {
                val created = OwnershipJournalEnvelope(ExecutorHostId(Uuid.random().toHexString()))
                persist(created)
                created.host
            }
        }
    }

    suspend fun binding(local: OwnershipLocalProcess): OwnershipLocalBinding? = guarded {
        mutex.withLock { load()?.bindings?.singleOrNull { it.local == local } }
    }

    /** A bound harness's raw process envelope must remain protected even when its feature switch is off. */
    suspend fun hasBindings(harness: HarnessId): Boolean = guarded {
        mutex.withLock { load()?.bindings?.any { it.local.harness == harness } == true }
    }

    /**
     * Compare exact previous binding during initial enrollment, Claim or Cancel recovery. Never infer approval
     * from a script marker. Updating the fence does not discard earlier permits needed for old native receipts.
     */
    suspend fun bind(expected: OwnershipLocalBinding?, next: OwnershipLocalBinding): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load()) { "Ownership host has not been initialized" }
            val previous = current.bindings.singleOrNull { it.local == next.local }
            check(next.fence.host == current.host) { "Ownership binding belongs to another host" }
            if (previous == next) return@withLock true
            if (previous != expected) return@withLock false
            check(previous == null || previous.isSuccessor(next)) { "Ownership binding cannot be reassigned" }
            val entries = current.bindings.filterNot { it.local == next.local } + next
            persist(current.copy(bindings = entries))
            true
        }
    }

    suspend fun intent(local: OwnershipLocalProcess, id: String): OwnershipNativeIntent? = guarded {
        mutex.withLock { load()?.intents?.singleOrNull { it.local == local && it.permit.id == id } }
    }

    /** Exact replay succeeds without rewriting. A different proposal for the same correlation is refused. */
    suspend fun reserve(intent: OwnershipNativeIntent): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load()) { "Ownership host has not been initialized" }
            val binding = current.bindings.singleOrNull { it.local == intent.local }
            check(binding?.fence == intent.permit.fence) { "Ownership intent has no current binding" }
            val previous = current.intents.singleOrNull {
                it.local == intent.local && it.permit.id == intent.permit.id
            }
            if (previous != null) return@withLock previous == intent
            persist(current.copy(intents = current.intents + intent))
            true
        }
    }

    private suspend fun load(): OwnershipJournalEnvelope? = secrets.read(KEY)?.use { secret ->
        secret.reveal { chars ->
            check(chars.size <= MAX_JOURNAL_CHARS) { "Ownership journal exceeds limit" }
            Json.decodeFromString<OwnershipJournalEnvelope>(chars.concatToString())
        }
    }

    private suspend fun persist(value: OwnershipJournalEnvelope) {
        val chars = Json.encodeToString(value).toCharArray()
        check(chars.size <= MAX_JOURNAL_CHARS) { "Ownership journal exceeds limit" }
        try {
            withContext(NonCancellable) { Secret(chars).use { secrets.write(KEY, it) } }
            log.v { "ownership journal committed" }
        } finally {
            chars.fill('\u0000')
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Ownership journal is unavailable")
        log.w(failure) { "Ownership journal rejected (${error::class.simpleName.orEmpty()})" }
        throw failure
    }
}

@Serializable
private data class OwnershipJournalEnvelope(
    val host: ExecutorHostId,
    val bindings: List<OwnershipLocalBinding> = emptyList(),
    val intents: List<OwnershipNativeIntent> = emptyList(),
    val version: Int = 1,
) {
    init {
        require(version == 1 && bindings.size <= MAX_BINDINGS && intents.size <= MAX_INTENTS)
        require(bindings.map { it.local }.distinct().size == bindings.size)
        require(bindings.map { it.fence.key }.distinct().size == bindings.size)
        require(bindings.all { it.fence.host == host })
        require(intents.map { it.local to it.permit.id }.distinct().size == intents.size)
        require(
            intents.all { intent ->
                intent.permit.fence.host == host && bindings.any {
                    it.local == intent.local && it.fence.key == intent.permit.fence.key &&
                        intent.permit.fence.epoch <= it.fence.epoch
                }
            },
        )
    }
    override fun toString(): String = "OwnershipJournalEnvelope(***)"
}

private val KEY = SecretKey("harness_ownership_v1")
private const val MAX_BINDINGS = 64
private const val MAX_INTENTS = 512
private const val MAX_JOURNAL_CHARS = 1024 * 1024

private fun OwnershipLocalBinding.isSuccessor(next: OwnershipLocalBinding): Boolean =
    identity.canonical == next.identity.canonical && fence.key == next.fence.key && next.fence.epoch >= fence.epoch
