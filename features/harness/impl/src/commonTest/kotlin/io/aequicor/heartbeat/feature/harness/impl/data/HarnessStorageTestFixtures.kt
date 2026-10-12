package io.aequicor.heartbeat.feature.harness.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant

internal val storageNow = Instant.fromEpochMilliseconds(1_000)
internal val storageClock = object : Clock {
    override fun now() = storageNow
}
internal val storageSession = SessionRef(EngineId("engine"), SessionSourceId("source"), "session")
internal val storageHarness = Harness(
    HarnessId("id with non-key characters / привет"), HarnessName("example"), "Example", "", HarnessScope.Attached,
    true, listOf(HarnessItem.Skill(ItemId("skill"), ItemName("skill"), "", "private text")), ToolPolicySpec(),
    storageSession, 0, storageNow, storageNow,
)
internal val storageReceipt = HarnessReceipt(RequestId("request"), storageHarness.id, 0, 1)

internal class HarnessTestStores : DataStores {
    override val owner = StorageOwner.App
    val stores = mutableMapOf<String, HarnessTestKeyValue>()
    override fun keyValue(spec: KeyValueSpec): HarnessTestKeyValue = stores.getOrPut(
        spec.name,
    ) { HarnessTestKeyValue(spec) }
    override fun filesDirectory(name: String): String = error("No files")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("No database")
    override suspend fun fire(event: DataEvent) = Unit
    fun library() = KeyValueHarnessLibrary(this, storageClock)
    val libraryValues get() = keyValue(HarnessLibrarySpec)
}

internal class HarnessTestKeyValue(override val spec: KeyValueSpec, private val clock: Clock = storageClock) :
    KeyValueStore {
    val values = linkedMapOf<String, String>()
    val expiries = mutableMapOf<String, Instant?>()
    var before: suspend (String, String, String?) -> Unit = { _, _, _ -> }
    var after: suspend (String, String, String?) -> Unit = { _, _, _ -> }
    fun crash(): Nothing {
        before = { _, _, _ -> throw HarnessTestCrash() }
        throw HarnessTestCrash()
    }
    fun restart() {
        before = { _, _, _ -> }
        after = { _, _, _ -> }
    }
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = flow { emit(get(key)) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        before("get", key.name, null)
        @Suppress("UNCHECKED_CAST") // The feature stores raw strings behind the generic fixture interface.
        return values[key.name].takeUnless {
            expiries[key.name]?.let { it <= clock.now() } == true
        } as T?
    }
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        val text = value as String
        before("set", key.name, text)
        values[key.name] = text
        expiries[key.name] = (retention.expiry as? Expiry.At)?.instant
        after("set", key.name, text)
    }
    override suspend fun remove(key: StoreKey<*>) {
        before("remove", key.name, null)
        values.remove(key.name)
        expiries.remove(key.name)
        after("remove", key.name, null)
    }
    override suspend fun clear() {
        values.clear()
        expiries.clear()
    }
}

/** Simulates process death: unlike an ordinary IO exception it must bypass in-process rollback. */
internal class HarnessTestCrash : Error("Simulated process death")
