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
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StudioPreferencesTest {
    private val selected = DefaultRunSettings.copy(
        modelId = EngineTarget(EngineId("engine"), EngineBindingId("connection"), ModelId("model")).studioModelId(),
        approval = ApprovalMode.AutoApprove,
    )
    private val version = StudioSettingsVersion("screen", 1)

    @Test
    fun `explicit choices survive service recreation without creating a conversation`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        KeyValueStudioPreferences(stores, profile).save(selected, version)

        val restored = KeyValueStudioPreferences(stores, profile)
        assertEquals(selected, restored.load(DefaultRunSettings))
    }

    @Test
    fun `profiles have independent defaults and an empty profile uses the supplied fallback`() = runTest {
        val profile = PreferenceProfile(backgroundScope)
        val first = KeyValueStudioPreferences(PreferenceStores("first"), profile)
        val second = KeyValueStudioPreferences(PreferenceStores("second"), profile)
        first.save(selected, version)

        assertEquals(selected, first.load(DefaultRunSettings))
        assertEquals(DefaultRunSettings, second.load(DefaultRunSettings))
    }

    @Test
    fun `late writes cannot replace a newer choice and a new screen can start at revision one`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val preferences = KeyValueStudioPreferences(stores, profile)
        preferences.save(selected, version.copy(revision = 2))
        preferences.save(DefaultRunSettings, version)
        assertEquals(selected, KeyValueStudioPreferences(stores, profile).load(DefaultRunSettings))

        preferences.save(DefaultRunSettings, version.copy(writer = "reopened"))
        assertEquals(DefaultRunSettings, KeyValueStudioPreferences(stores, profile).load(selected))
    }

    @Test
    fun `closing the screen leaves its accepted write running and reopening waits for it`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val preferences = KeyValueStudioPreferences(stores, profile)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.values.beforeWrite = {
            writing.complete(Unit)
            release.await()
        }
        val screen = launch { preferences.save(selected, version) }
        writing.await()
        screen.cancelAndJoin()
        val loading = async { preferences.load(DefaultRunSettings) }
        runCurrent()
        assertFalse(loading.isCompleted)

        release.complete(Unit)
        assertEquals(selected, loading.await())
        assertEquals(selected, KeyValueStudioPreferences(stores, profile).load(DefaultRunSettings))
    }

    @Test
    fun `overlapping choices are written in order and the last one survives recreation`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val preferences = KeyValueStudioPreferences(stores, profile)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.values.beforeWrite = {
            writing.complete(Unit)
            release.await()
        }
        val first = launch { preferences.save(DefaultRunSettings, version) }
        writing.await()
        val last = launch { preferences.save(selected, version.copy(revision = 2)) }
        runCurrent()
        release.complete(Unit)
        first.join()
        last.join()

        assertEquals(selected, KeyValueStudioPreferences(stores, profile).load(DefaultRunSettings))
    }

    @Test
    fun `a failed write retains the latest choice in memory and a subsequent choice retries persistence`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val preferences = KeyValueStudioPreferences(stores, profile)
        stores.values.beforeWrite = { error("disk unavailable") }
        assertFailsWith<IllegalStateException> { preferences.save(selected, version.copy(revision = 2)) }
        preferences.save(DefaultRunSettings, version)
        assertEquals(selected, preferences.load(DefaultRunSettings))

        stores.values.beforeWrite = {}
        preferences.save(selected, version.copy(revision = 3))
        assertEquals(selected, KeyValueStudioPreferences(stores, profile).load(DefaultRunSettings))
    }

    @Test
    fun `a read failure falls back without erasing stored settings and can be retried`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        KeyValueStudioPreferences(stores, profile).save(selected, version)
        val preferences = KeyValueStudioPreferences(stores, profile)
        stores.values.isReadFailing = true
        assertEquals(DefaultRunSettings, preferences.load(DefaultRunSettings))

        stores.values.isReadFailing = false
        assertEquals(selected, preferences.load(DefaultRunSettings))
    }
}

private class PreferenceProfile(parent: CoroutineScope) : ScopeHandle {
    override val coroutineScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    override val name = "preferences-test-profile"
    override val savedState: ScopeSavedState get() = error("unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

private class PreferenceStores(id: String = "profile") : DataStores {
    override fun filesDirectory(name: String): String = error("File storage is not used by this fake")
    val values = PreferenceValues()
    override val owner = StorageOwner.Profile(ProfileId(id))
    override fun keyValue(spec: KeyValueSpec): KeyValueStore {
        assertEquals(values.spec, spec)
        return values
    }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("unused")
    override suspend fun fire(event: DataEvent) = error("unused")
}

/** Serializes values so recreating a repository also exercises its persistent representation. */
private class PreferenceValues : KeyValueStore {
    override val spec = KeyValueSpec("ai_studio_preferences")
    private val values = MutableStateFlow(emptyMap<String, String>())
    var beforeWrite: suspend () -> Unit = {}
    var isReadFailing = false

    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { decode(key, it[key.name]) }
    override suspend fun <T : Any> get(key: StoreKey<T>): T? {
        check(!isReadFailing) { "disk unavailable" }
        return decode(key, values.value[key.name])
    }
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        beforeWrite()
        val serializer = (key.type as StoreValueType.Json<T>).serializer
        values.value += key.name to Json.encodeToString(serializer, value)
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }
    private fun <T : Any> decode(key: StoreKey<T>, value: String?): T? =
        value?.let { Json.decodeFromString((key.type as StoreValueType.Json<T>).serializer, it) }
}
