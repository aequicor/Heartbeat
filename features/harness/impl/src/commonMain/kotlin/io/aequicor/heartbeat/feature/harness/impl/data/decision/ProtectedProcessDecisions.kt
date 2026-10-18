package io.aequicor.heartbeat.feature.harness.impl.data.decision

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionLedger
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One encrypted, atomically replaced profile book. It has a separate secret key from every ScriptData namespace.
 * Schema one starts empty; absent is distinct from unreadable or unsupported data, which is never overwritten.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProtectedProcessDecisions(private val secrets: SecretStore) : ProcessDecisionStorage {
    private val mutex = Mutex()
    private val log = Log.tag("ProcessDecisions")

    override suspend fun load(): ProcessDecisionLedger = mutex.withLock { privateAccess { read() } }

    override suspend fun save(ledger: ProcessDecisionLedger, expectedRevision: Long) = mutex.withLock {
        privateAccess {
            val current = read()
            if (current != ledger) {
                check(current.revision == expectedRevision && ledger.revision == expectedRevision + 1) {
                    "Process decision revision conflict"
                }
                val encoded = Json.encodeToString(Envelope(ledger = ledger))
                check(encoded.encodeToByteArray().size <= MAX_BYTES) { "Process decision storage is full" }
                val chars = encoded.toCharArray()
                try {
                    Secret(chars).use { secrets.write(KEY, it) }
                } finally {
                    chars.fill('\u0000')
                }
            }
        }
    }

    private suspend fun read(): ProcessDecisionLedger {
        val envelope = secrets.read(KEY)?.use { secret ->
            secret.reveal { chars -> Json.decodeFromString<Envelope>(chars.concatToString()) }
        } ?: return ProcessDecisionLedger()
        check(envelope.version == 1) { "Unsupported process decision version" }
        return envelope.ledger
    }

    private suspend fun <T> privateAccess(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = IllegalStateException("Process decision storage is unavailable")
        log.w(failure) { "Process decision storage failed (${error::class.simpleName.orEmpty()})" }
        throw failure
    }

    @Serializable
    private data class Envelope(val version: Int = 1, val ledger: ProcessDecisionLedger) {
        override fun toString(): String = "ProcessDecisionEnvelope(***)"
    }

    internal companion object {
        val KEY = SecretKey("harness_process_decisions")
        private const val MAX_BYTES = 1024 * 1024
    }
}
