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
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioEffortOverride
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingsVersion
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `observers see accepted choices before disk IO and keep them when persistence fails`() = runTest {
        val stores = PreferenceStores()
        val preferences = KeyValueStudioPreferences(stores, PreferenceProfile(backgroundScope))
        val seen = mutableListOf<RunSettings>()
        backgroundScope.launch { preferences.observe(DefaultRunSettings).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(DefaultRunSettings), seen)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.values.beforeWrite = {
            writing.complete(Unit)
            release.await()
            error("disk unavailable")
        }
        val saving = launch {
            assertFailsWith<IllegalStateException> { preferences.save(selected, version.copy(revision = 2)) }
        }
        writing.await()
        runCurrent()
        assertEquals(listOf(DefaultRunSettings, selected), seen)
        release.complete(Unit)
        saving.join()
        preferences.save(DefaultRunSettings, version)
        runCurrent()
        assertEquals(listOf(DefaultRunSettings, selected), seen)

        stores.values.beforeWrite = {}
        val next = selected.copy(approval = ApprovalMode.Ask)
        preferences.save(next, version.copy(revision = 3))
        runCurrent()
        assertEquals(listOf(DefaultRunSettings, selected, next), seen)
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

    @Test
    fun `last accepted receipt wins over delayed disk IO and survives service recreation`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val started = KeyValueStudioLastStartedRun(stores, profile)
        val first = StudioSessionSettings("exact-first", "native-high", ApprovalMode.AutoApprove)
        val last = StudioSessionSettings("exact-last", null, ApprovalMode.Ask)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.values.beforeWrite = {
            writing.complete(Unit)
            release.await()
        }
        val screen = launch { started.capture(first) }
        writing.await()
        screen.cancelAndJoin()
        started.capture(last)
        assertEquals(last, started.observe().first())
        release.complete(Unit)
        runCurrent()
        assertEquals(last, KeyValueStudioLastStartedRun(stores, profile).observe().first())
    }

    @Test
    fun `failed persistence keeps receipt and repeated acceptance retries`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        KeyValueStudioPreferences(stores, profile).save(selected, version)
        val started = KeyValueStudioLastStartedRun(stores, profile)
        assertEquals(null, started.observe().first())
        val accepted = StudioSessionSettings("exact-route", "native-max")
        stores.values.beforeWrite = { error("disk unavailable") }
        started.capture(accepted)
        runCurrent()
        assertEquals(accepted, started.observe().first())
        stores.values.beforeWrite = {}
        started.capture(accepted)
        runCurrent()
        assertEquals(accepted, KeyValueStudioLastStartedRun(stores, profile).observe().first())
        assertEquals(null, KeyValueStudioLastStartedRun(PreferenceStores("other"), profile).observe().first())
    }

    @Test
    fun `restored defaults use last accepted exact route instead of unsent choices or another default`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val preferences = KeyValueStudioPreferences(stores, profile)
        preferences.save(selected, version)
        val first = EngineTarget(EngineId("engine"), EngineBindingId("disappeared"), ModelId("old"))
        val neighbor = first.copy(binding = EngineBindingId("other"))
        val accepted = StudioSessionSettings(first.studioModelId(), null, ApprovalMode.Ask)
        val started = KeyValueStudioLastStartedRun(stores, profile)
        started.capture(accepted)
        runCurrent()
        val selections = object : ModelSelections {
            override fun observe() = kotlinx.coroutines.flow.flowOf(ModelSelection().withDefault(neighbor))
            override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection = error("read only")
        }
        val efforts = object : EffortChoicesView {
            override val state = MutableStateFlow<EffortConfigurationState>(EffortConfigurationState.Ready())
        }
        val restored = observeStudioDefaults(preferences, selections, efforts, started).first()
        assertEquals(first.studioModelId(), restored.modelId)
        assertEquals(StudioEffortOverride(first.studioModelId(), null), restored.nativeEffort)
        preferences.save(selected.copy(modelId = "unsent-change"), version.copy(revision = 2))
        assertEquals(restored, observeStudioDefaults(preferences, selections, efforts, started).first())
    }

    @Test
    fun `profile closure cancels queued receipt writes without extending the storage owner`() = runTest {
        val stores = PreferenceStores()
        val profile = PreferenceProfile(backgroundScope)
        val started = KeyValueStudioLastStartedRun(stores, profile)
        started.capture(StudioSessionSettings("not-yet-persisted", null))
        profile.closeAndJoin()
        val reopened = KeyValueStudioLastStartedRun(stores, PreferenceProfile(backgroundScope))
        assertEquals(null, reopened.observe().first())
    }
}

private class PreferenceProfile(parent: CoroutineScope) : ScopeHandle {
    override val coroutineScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    override val name = "preferences-test-profile"
    override val savedState: ScopeSavedState get() = error("unused")
    override var isClosed = false
        private set
    suspend fun closeAndJoin() {
        isClosed = true
        coroutineScope.coroutineContext[Job]?.cancelAndJoin()
    }
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
