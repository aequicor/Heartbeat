package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One pure record transaction, committed under the repository's existing profile write lock. */
internal data class StudioConversationUpdate<T>(val records: List<StudioChatRecord>, val value: T)

/** Shares the ordinary chat writer; logical control does not introduce a second conversation registry. */
internal interface StudioConversationRecords {
    suspend fun <T> editConversations(change: (List<StudioChatRecord>) -> StudioConversationUpdate<T>): T
}

/** Existing repository operations share this exact store and mutex with logical conversation control. */
internal interface StudioChatRecords {
    val store: KeyValueStore
    val lock: Mutex
}

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioConversationRecords>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioChatRecords>())
@Inject
internal class StudioConversationRecordStore(
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) profile: ScopeHandle,
) : StudioConversationRecords,
    StudioChatRecords {
    private val log = Log.tag("StudioConversationRecordStore")
    override val store = stores.keyValue(ChatSpec)
    override val lock = Mutex()

    init {
        profile.coroutineScope.launch {
            try {
                editConversations { StudioConversationUpdate(it, Unit) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error.redactedFailure()) { "Logical session metadata initialization is unavailable" }
            }
        }
    }

    override suspend fun <T> editConversations(change: (List<StudioChatRecord>) -> StudioConversationUpdate<T>): T =
        lock.withLock {
            log.v { "Read and update canonical conversation records" }
            val saved = store.get(ChatsKey).orEmpty()
            val updated = change(resolvedStudioIdentities(saved))
            val resolved = resolvedStudioIdentities(updated.records)
            if (resolved != saved) store.set(ChatsKey, resolved)
            updated.value
        }
}

/** Canonical identity read; callers serialize compound changes with the shared store lock. */
internal suspend fun StudioChatRecords.record(id: String): StudioChatRecord =
    resolvedStudioIdentities(store.get(ChatsKey).orEmpty()).singleOrNull { it.id == id }
        ?: error("Unknown studio conversation")

/** Read-modify-write under the existing lock; transcript contraction already holds it. */
internal suspend fun StudioChatRecords.updateRecord(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
    val records = resolvedStudioIdentities(store.get(ChatsKey).orEmpty())
    store.set(ChatsKey, resolvedStudioIdentities(records.map { if (it.id == id) it.change() else it }))
}
