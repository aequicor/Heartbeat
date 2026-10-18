package io.aequicor.heartbeat.feature.worktreemode.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildCapacitySettingsTest {
    @Test
    fun `project plans pool size and application pause timeout persist independently`() = runTest {
        val stores = SettingsStores()
        val settings = StoredProjectBuildSettings(lazy { stores })
        assertEquals(2, settings.get("project-go").poolSize)
        val go = io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan(
            "go",
            listOf(
                io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand(
                    "test",
                    "go",
                    listOf("test", "./..."),
                ),
            ),
        )
        val python = go.copy(
            system = "python",
            commands = listOf(go.commands.single().copy(executable = "python", arguments = listOf("-m", "unittest"))),
        )
        val first = settings.configure("project-go", go)
        settings.configure("project-python", python)
        settings.setPoolSize("project-go", 4)
        settings.setPauseTimeout(60_000)
        val restored = StoredProjectBuildSettings(lazy { stores })
        assertEquals(4, restored.get("project-go").poolSize)
        assertEquals(first.revision, restored.get("project-go").revision)
        assertEquals(python, restored.get("project-python").plan)
        assertEquals(60_000, restored.pauseTimeout())
        assertEquals(first.revision + 1, restored.configure("project-go", python).revision)
        assertEquals(go, first.plan)
    }

    @Test
    fun `user limit is normalized and clearing it restores the automatic estimate`() = runTest {
        val settings = BuildCapacitySettings(lazy { SettingsStores() })
        assertNull(settings.observe().first())
        settings.set(3)
        assertEquals(3, settings.observe().first())
        settings.set(100)
        assertEquals(BuildBudget.MAX_PARALLEL_BUILDS, settings.observe().first())
        settings.set(0)
        assertNull(settings.observe().first())
    }

    @Test
    fun `desktop budget follows the build queue toggle and the stored limit`() = runTest {
        withContext(RealTestDispatchers.default) {
            val scope = SettingsScope()
            try {
                val settings = BuildCapacitySettings(lazy { SettingsStores() })
                val toggles = QueueToggles()
                val capacity = DesktopBuildCapacity(scope, settings, toggles)
                assertEquals(BuildBudget.Unlimited, capacity.budget())
                toggles.isQueueEnabled.value = true
                withTimeout(AWAIT_MILLIS) {
                    while (capacity.budget() != capacity.automatic) kotlinx.coroutines.yield()
                }
                settings.set(2)
                withTimeout(AWAIT_MILLIS) {
                    while (capacity.budget() != BuildBudget.parallel(2)) kotlinx.coroutines.yield()
                }
                settings.set(null)
                withTimeout(AWAIT_MILLIS) {
                    while (capacity.budget() != capacity.automatic) kotlinx.coroutines.yield()
                }
            } finally {
                scope.coroutineScope.cancel()
            }
        }
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
    }
}

private class QueueToggles : FeatureToggles {
    val isQueueEnabled = MutableStateFlow(false)

    // The capacity only reads the build queue flag.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = isQueueEnabled as Flow<T>

    // The capacity only reads the build queue flag.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isQueueEnabled.value as T
}

private class SettingsScope : ScopeHandle {
    override val name = "capacity-test"
    override val coroutineScope = CoroutineScope(SupervisorJob() + RealTestDispatchers.default)
    override val savedState: ScopeSavedState get() = error("Not used")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
}

internal class SettingsStores(private val files: java.nio.file.Path? = null) : DataStores {
    private val stores = mutableMapOf<String, SettingsStore>()
    override val owner: StorageOwner = StorageOwner.App
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(
        spec.name,
    ) { SettingsStore() }.also { it.spec = spec }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override fun filesDirectory(name: String): String = checkNotNull(files).resolve(name).toString()
    override suspend fun fire(event: DataEvent) = Unit
}

private class SettingsStore : KeyValueStore {
    private val values = MutableStateFlow(emptyMap<String, Any>())
    override var spec: KeyValueSpec = KeyValueSpec("unused")

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it[key.name] as T? }

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value[key.name] as T?
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        values.value += key.name to value
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }
}
