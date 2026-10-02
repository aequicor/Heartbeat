package io.aequicor.heartbeat.feature.computeruse.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUseSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileComputerUsePreferencesTest {
    @Test
    fun `computer tools start disabled and enabling survives reopening only in that profile`() = runTest {
        val stores = PreferenceStores("first")
        val first = ProfileComputerUsePreferences(stores)
        assertFalse(first.read().isEnabled)
        first.setEnabled(true)
        assertTrue(ProfileComputerUsePreferences(stores).read().isEnabled)
        assertFalse(ProfileComputerUsePreferences(PreferenceStores("second")).read().isEnabled)
        first.setEnabled(false)
        assertFalse(ProfileComputerUsePreferences(stores).read().isEnabled)
    }

    @Test
    fun `observers receive enabled changes without any frame preset change`() = runTest {
        val preferences = ProfileComputerUsePreferences(PreferenceStores("profile"))
        val observed = mutableListOf<ComputerUseSettings>()
        backgroundScope.launch { preferences.observe().collect { observed += it } }
        runCurrent()
        assertFalse(observed.last().isEnabled)
        preferences.setEnabled(true)
        runCurrent()
        assertTrue(observed.last().isEnabled)
        preferences.setCursorIncluded(false)
        runCurrent()
        assertFalse(observed.last().isCursorIncluded)
        preferences.setEnabled(false)
        runCurrent()
        assertFalse(observed.last().isEnabled)
        assertEquals(ComputerUseSettings.DEFAULT_PRESET, observed.last().preset)
    }
}

private class PreferenceStores(id: String) : DataStores {
    override val owner = StorageOwner.Profile(ProfileId(id))
    private val stores = mutableMapOf<String, PreferenceValues>()
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec.name) { PreferenceValues(spec) }
    override fun filesDirectory(name: String): String = error("Files are not used by this test")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Database is not used by this test")
    override suspend fun fire(event: DataEvent) = Unit
}

private class PreferenceValues(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = MutableStateFlow<Map<String, Any>>(emptyMap())

    @Suppress("UNCHECKED_CAST") // This fixture stores values under their original typed key.
    override fun <T : Any> observe(key: StoreKey<T>) = values.map { it[key.name] as T? }

    @Suppress("UNCHECKED_CAST") // This fixture stores values under their original typed key.
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
