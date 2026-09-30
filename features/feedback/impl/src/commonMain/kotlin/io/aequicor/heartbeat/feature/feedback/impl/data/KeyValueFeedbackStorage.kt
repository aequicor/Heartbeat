package io.aequicor.heartbeat.feature.feedback.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.impl.domain.FeedbackStorage
import kotlinx.serialization.builtins.ListSerializer

private val FeedbackSpec = KeyValueSpec("feedback")
private val RecordsKey = jsonKey("records", ListSerializer(FeedbackRecord.serializer()))

/** Profile history containing only typed configuration facts; record contents are never logged. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class KeyValueFeedbackStorage(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : FeedbackStorage {
    private val log = Log.tag("FeedbackStorage")
    private val store by lazy { stores.keyValue(FeedbackSpec) }

    override suspend fun load(): List<FeedbackRecord> {
        log.d { "Read feedback history" }
        return store.get(RecordsKey).orEmpty()
    }

    override suspend fun save(records: List<FeedbackRecord>) {
        log.d { "Write feedback history count=${records.size}" }
        if (records.isEmpty()) store.remove(RecordsKey) else store.set(RecordsKey, records)
    }
}
