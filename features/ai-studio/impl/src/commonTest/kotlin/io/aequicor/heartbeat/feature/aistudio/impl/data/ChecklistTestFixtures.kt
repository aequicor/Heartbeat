package io.aequicor.heartbeat.feature.aistudio.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.StoreValueType
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal class ChecklistTestStores : DataStores {
    val stores = mutableMapOf<KeyValueSpec, ChecklistTestStore>()
    override val owner = StorageOwner.App
    override fun filesDirectory(name: String): String = error("unused")
    override fun keyValue(spec: KeyValueSpec): ChecklistTestStore = stores.getOrPut(spec) { ChecklistTestStore(spec) }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
    override suspend fun fire(event: DataEvent) = Unit
}

internal class ChecklistTestStore(override val spec: KeyValueSpec) : KeyValueStore {
    val values = MutableStateFlow(emptyMap<String, String>())
    var failWrites = false
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { decode(key, it[key.name]) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = decode(key, values.value[key.name])
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        check(!failWrites) { "disk unavailable" }
        val json = key.type as? StoreValueType.Json<T>
        values.value += key.name to if (json == null) value as String else Json.encodeToString(json.serializer, value)
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }

    @Suppress("UNCHECKED_CAST") // The fake serializes JSON and supports the string keys used by the journal.
    private fun <T : Any> decode(key: StoreKey<T>, value: String?): T? {
        if (value == null) return null
        val json = key.type as? StoreValueType.Json<T>
        return if (json == null) value as T else Json.decodeFromString(json.serializer, value)
    }
}

internal class ChecklistTestToggles : FeatureToggles {
    val enabled = MutableStateFlow(true)
    var scheduler = true

    @Suppress("UNCHECKED_CAST") // Tests use boolean flags only.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
        (if (toggle == ChecklistEnabled) enabled else flowOf(scheduler)) as Flow<T>
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

internal class ChecklistTestBus : SchedulerBus {
    override val events = MutableSharedFlow<BusEvent>(extraBufferCapacity = 64)
    val published = mutableListOf<BusEvent>()
    var beforePublish: (EventKey) -> Unit = {}
    override suspend fun publish(key: EventKey, origin: EventOrigin, payload: String?): BusEvent {
        beforePublish(key)
        val event = BusEvent(key, origin, Clock.System.now(), payload)
        published += event
        events.emit(event)
        return event
    }
}
