package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.ConversationId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.LogicalConversation
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.ConversationJournal

/** Profile-owned conversations, one JSON record per conversation. Session references are never logged. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueConversationJournal(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ConversationJournal {

    private val log = Log.tag("ConversationJournal")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun get(id: ConversationId): LogicalConversation? {
        val conversation = store.get(key(id))
        log.d { "read conversation: ${conversation?.segments?.size?.let { "$it segments" } ?: "absent"}" }
        return conversation
    }

    override suspend fun put(conversation: LogicalConversation) {
        log.d { "write conversation: ${conversation.segments.size} segments" }
        store.set(key(conversation.id), conversation)
    }

    override suspend fun remove(id: ConversationId) {
        log.d { "remove conversation" }
        store.remove(key(id))
    }

    /** Conversation ids are at most 128 safe characters, which is also the key name limit. */
    private fun key(id: ConversationId) = jsonKey(id.value, LogicalConversation.serializer())

    private companion object {
        val SPEC = KeyValueSpec("ai_session_transfer_conversations")
    }
}
