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
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipNativeIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

/**
 * Protected profile-lifetime ownership journal, inaccessible to ScriptData. Activated bindings and native
 * intents never expire, including completed intents: forgetting one could repeat native dispatch. Quota
 * exhaustion fails closed. Host identity and exact proposals survive restart; unreadable storage never creates a
 * fresh host. A never-admitted provisional enrollment may be discarded only after observing a foreign owner.
 * Only explicit host enrollment/import may call [bind], after consent and coordinator verification.
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

    /** Includes recipient bindings persisted before Claim, even before a local ProcessRecord exists. */
    suspend fun bindings(harness: HarnessId? = null, pendingOnly: Boolean = false): List<OwnershipLocalBinding> =
        guarded {
            mutex.withLock {
                val current = load()
                current?.bindings?.filter {
                    (harness == null || it.local.harness == harness) &&
                        (!pendingOnly || it.local in current.pendingEnrollments)
                }.orEmpty()
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

    /** Provisional protection before conditional registration; native admission remains closed. */
    suspend fun beginEnrollment(binding: OwnershipLocalBinding): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load())
            val previous = current.bindings.singleOrNull { it.local == binding.local }
            val isReplay = previous == binding && binding.local in current.pendingEnrollments
            if (previous != null) return@withLock isReplay
            check(binding.fence.host == current.host && binding.fence.epoch == 1L)
            persist(
                current.copy(
                    bindings = current.bindings + binding,
                    pendingEnrollments = current.pendingEnrollments + binding.local,
                ),
            )
            true
        }
    }

    /** Marker is durable and registration positively observed before this method opens admission. */
    suspend fun promoteEnrollment(binding: OwnershipLocalBinding): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load())
            if (current.bindings.singleOrNull { it.local == binding.local } != binding) return@withLock false
            if (binding.local in current.pendingEnrollments) {
                persist(current.copy(pendingEnrollments = current.pendingEnrollments - binding.local))
            }
            true
        }
    }

    /**
     * Only a never-admitted provisional registration may be discarded. Caller holds the process writer barrier
     * and verifies its local record is still fresh and unmarked. An absent/unknown remote result is insufficient.
     */
    suspend fun discardEnrollment(binding: OwnershipLocalBinding, foreign: ProcessOwnership): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load())
            val isDiscardable = foreign.fence.key == binding.fence.key && foreign.fence.host != current.host &&
                binding.local in current.pendingEnrollments && current.intents.none { it.local == binding.local } &&
                current.bindings.singleOrNull { it.local == binding.local } == binding
            if (isDiscardable) {
                persist(
                    current.copy(
                        bindings = current.bindings - binding,
                        pendingEnrollments = current.pendingEnrollments - binding.local,
                    ),
                )
            }
            isDiscardable
        }
    }

    /** Protected recovery inventory, including proposals whose CAS response was lost. */
    suspend fun intents(local: OwnershipLocalProcess): List<OwnershipNativeIntent> = guarded {
        mutex.withLock { load()?.intents?.filter { it.local == local }.orEmpty() }
    }

    suspend fun intent(local: OwnershipLocalProcess, id: String): OwnershipNativeIntent? = guarded {
        mutex.withLock { load()?.intents?.singleOrNull { it.local == local && it.permit.id == id } }
    }

    /** Exact isReplay succeeds without rewriting. A different proposal for the same correlation is refused. */
    suspend fun reserve(intent: OwnershipNativeIntent): Boolean = guarded {
        mutex.withLock {
            val current = checkNotNull(load()) { "Ownership host has not been initialized" }
            val binding = current.bindings.singleOrNull { it.local == intent.local }
            check(
                binding?.fence == intent.permit.fence && intent.local !in current.pendingEnrollments,
            ) { "Ownership intent has no current binding" }
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
    val pendingEnrollments: List<OwnershipLocalProcess> = emptyList(),
) {
    init {
        require(version == 1 && bindings.size <= MAX_BINDINGS && intents.size <= MAX_INTENTS)
        require(bindings.map { it.local }.distinct().size == bindings.size)
        require(bindings.map { it.fence.key }.distinct().size == bindings.size)
        require(
            bindings.all {
                it.fence.host == host &&
                    it.fence.key.value == it.identity.canonical.encodeUtf8().sha256().hex()
            },
        )
        require(pendingEnrollments.distinct().size == pendingEnrollments.size)
        require(pendingEnrollments.all { pending -> bindings.any { it.local == pending && it.fence.epoch == 1L } })
        require(intents.none { it.local in pendingEnrollments })
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
    identity.canonical == next.identity.canonical && fence.key == next.fence.key &&
        (next.fence.epoch > fence.epoch || (next.fence == fence && preservesEvidence(next)))

private fun OwnershipLocalBinding.preservesEvidence(next: OwnershipLocalBinding): Boolean =
    previousOwner == next.previousOwner && (imported == null || imported == next.imported) &&
        (!isSourceReconciled || next.isSourceReconciled)

internal suspend fun ProtectedOwnershipJournal.pendingEnrollment(key: OwnershipKey): OwnershipLocalBinding? =
    bindings(pendingOnly = true).singleOrNull { it.fence.key == key }
