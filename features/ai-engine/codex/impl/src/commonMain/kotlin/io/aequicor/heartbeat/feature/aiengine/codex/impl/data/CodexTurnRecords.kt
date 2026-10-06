package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Native correlation is absent until a response or event establishes acceptance of this exact request. */
@Serializable
internal data class CodexTurnRecord(
    val turn: Turn,
    val nativeId: String?,
    val trust: TrustLevel,
    /** Absent on legacy records or when the OS cannot establish exact process identity. */
    val processOwner: CodexExecutionOwner? = null,
) {
    init {
        requireNotNull(turn.request)
        require(nativeId == null || nativeId.isNotBlank())
    }
}

/** Latest boundaries of one owned native thread; neither prompt text nor native account details are stored. */
@Serializable
internal data class CodexTurnSnapshot(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val ownership: String?,
    val active: CodexTurnRecord? = null,
    val last: CodexTurnRecord? = null,
) {
    init {
        require(active?.turn?.outcome == null)
        require(last == null || last.turn.outcome != null)
        require(active == null || active.turn.id != last?.turn?.id)
        require(ref.engine == route.engine)
    }

    override fun toString(): String = "CodexTurnSnapshot(***)"
}

/** Atomic read–modify–write; transformers contain no IO and cannot wait for the native process. */
internal interface CodexTurnRecords {
    suspend fun get(ref: SessionRef): CodexTurnSnapshot?
    suspend fun update(ref: SessionRef, transform: (CodexTurnSnapshot?) -> CodexTurnSnapshot): CodexTurnSnapshot
}

internal class MemoryCodexTurnRecords : CodexTurnRecords {
    private val values = mutableMapOf<SessionRef, CodexTurnSnapshot>()
    override suspend fun get(ref: SessionRef): CodexTurnSnapshot? = values[ref]
    override suspend fun update(
        ref: SessionRef,
        transform: (CodexTurnSnapshot?) -> CodexTurnSnapshot,
    ): CodexTurnSnapshot = transform(values[ref]).also { values[ref] = it }
}

internal val CodexTurnsSpec = KeyValueSpec("ai_codex_turns")

/** Permanent profile records; a required marker fails closed if the corresponding record is lost. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredCodexTurnRecords(
    @ForScope(ProfileScope::class) stores: DataStores,
) : CodexTurnRecords {
    private val store = stores.keyValue(CodexTurnsSpec)
    private val mutex = Mutex()

    override suspend fun get(ref: SessionRef): CodexTurnSnapshot? = mutex.withLock { read(ref) }

    override suspend fun update(
        ref: SessionRef,
        transform: (CodexTurnSnapshot?) -> CodexTurnSnapshot,
    ): CodexTurnSnapshot = mutex.withLock {
        val next = transform(read(ref))
        check(next.ref == ref)
        val key = codexTurnKey(ref)
        store.set(booleanKey("required_$key"), true)
        store.set(stringKey("turn_$key"), Json.encodeToString(next))
        next
    }

    private suspend fun read(ref: SessionRef): CodexTurnSnapshot? {
        val key = codexTurnKey(ref)
        val raw = store.get(stringKey("turn_$key"))
        if (raw == null) {
            check(store.get(booleanKey("required_$key")) != true) { "Required Codex turn record is missing" }
            return null
        }
        // Raw text decoding deliberately propagates corruption; jsonKey would collapse it to an absent record.
        return Json.decodeFromString<CodexTurnSnapshot>(raw).also { check(it.ref == ref) }
    }
}

internal fun codexTurnKey(ref: SessionRef): String = Json.encodeToString(ref).encodeUtf8().sha256().hex()

internal fun codexOwnership(home: String, account: List<String?>): String =
    Json.encodeToString(listOf(home) + account).encodeUtf8().sha256().hex()
