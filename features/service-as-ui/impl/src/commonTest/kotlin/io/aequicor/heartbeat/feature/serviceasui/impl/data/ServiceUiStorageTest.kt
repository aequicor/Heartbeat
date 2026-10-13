package io.aequicor.heartbeat.feature.serviceasui.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEffect
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteResult
import io.aequicor.heartbeat.feature.serviceasui.api.apply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ServiceUiStorageTest {
    private val stores = MemoryStores()
    private val storage = KeyValueUiLibrary(stores)

    @Test
    fun `an empty profile loads an empty library`() = runTest {
        assertEquals(UiLibrary(), storage.load())
        assertEquals("service_ui_library", stores.store.spec.name)
    }

    @Test
    fun `saved snapshots load back and stale revisions never overwrite newer ones`() = runTest {
        val first = library("first")
        val second = (first.apply(put("second")) as UiWriteResult.Applied).library

        storage.save(second)
        storage.save(first)

        assertEquals(second, KeyValueUiLibrary(stores).load())
    }

    @Test
    fun `an unreadable library fails without quoting it`() = runTest {
        for (raw in listOf("{not json", """{"surfaces":[{"content":{"spec":"private-spec"}}],"revision":1}""")) {
            stores.store.values["library"] = raw
            val failure = assertFailsWith<IllegalStateException> { storage.load() }
            assertFalse("private-spec" in failure.toString(), failure.toString())
            assertNull(failure.cause)
        }
    }

    @Test
    fun `effects load, save and pause before another read`() = runTest {
        val machine = RecordingScope()
        val effects = ServiceUiEffects(storage)
        val library = library("map")

        effects.handle(ServiceUiEffect.Persist("w1", library), machine)
        effects.handle(ServiceUiEffect.Load, machine)
        val start = currentTime
        effects.handle(ServiceUiEffect.RetryLoad(2), machine)

        assertEquals<List<ServiceUiIntent>>(
            listOf(
                ServiceUiIntent.Internal.Persisted("w1"),
                ServiceUiIntent.Internal.Loaded(library),
                ServiceUiIntent.Internal.RetryLoad,
            ),
            machine.sent,
        )
        assertEquals(2.seconds.inWholeMilliseconds, currentTime - start)
        assertEquals(listOf(1, 2, 4, 32, 32), listOf(1, 2, 3, 6, 9).map { retryDelay(it).inWholeSeconds.toInt() })
    }

    private fun library(name: String): UiLibrary = (UiLibrary().apply(put(name)) as UiWriteResult.Applied).library

    private fun put(name: String) = UiWriteOp.Put(
        UiSurfaceContent(UiOwner("harness", "h_1"), UiSurfaceName(name), UiPlacement.Start, "Title", spec = "{}"),
        expectedRevision = 0,
        author = null,
        at = Instant.fromEpochSeconds(1_700_000_000),
    )

    private class RecordingScope : EffectScope<ServiceUiIntent> {
        val sent = mutableListOf<ServiceUiIntent>()

        override suspend fun send(intent: ServiceUiIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class MemoryStores : DataStores {
        val store = MemoryStore()
        override val owner: StorageOwner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("unused")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = store.also { it.spec = spec }
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
        override suspend fun fire(event: DataEvent) = Unit
    }

    private class MemoryStore : KeyValueStore {
        override lateinit var spec: KeyValueSpec
        val values = mutableMapOf<String, Any>()

        override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = emptyFlow()

        @Suppress("UNCHECKED_CAST") // Test fake: one value type per key.
        override suspend fun <T : Any> get(key: StoreKey<T>): T? = values[key.name] as T?

        override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
            values[key.name] = value
        }

        override suspend fun remove(key: StoreKey<*>) {
            values.remove(key.name)
        }

        override suspend fun clear() = values.clear()
    }
}
