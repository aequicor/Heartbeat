package io.aequicor.heartbeat.feature.searchengine.impl

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
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.impl.data.SearchPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPreferencesTest {
    @Test fun `profile settings and two key slots survive reopening without crossing profiles`() = runTest {
        val firstStores = MemoryStores("first")
        val firstSecrets = MemorySecrets()
        val first = SearchPreferences(firstStores, firstSecrets)
        first.setHost(SearchOperation.Search, "https://search.example")
        first.setHost(SearchOperation.Contents, "https://contents.example")
        first.setPreferNative(false)
        Secret("search-key".toCharArray()).use { first.setKey(SearchOperation.Search, it) }
        Secret("contents-key".toCharArray()).use { first.setKey(SearchOperation.Contents, it) }

        val second = SearchPreferences(MemoryStores("second"), MemorySecrets())
        assertFalse(second.read().search.hasKey)
        assertTrue(second.read().isNativePreferred)

        val reopened = SearchPreferences(firstStores, firstSecrets)
        assertEquals("https://search.example", reopened.read().search.host)
        assertEquals("https://contents.example", reopened.read().contents.host)
        assertFalse(reopened.read().isNativePreferred)
        assertTrue(reopened.read().search.hasKey)
        assertTrue(reopened.read().contents.hasKey)
        assertEquals(
            "search-key",
            reopened.credential(SearchOperation.Search).use { it.reveal { chars -> chars.concatToString() } },
        )
        assertEquals(
            "contents-key",
            reopened.credential(SearchOperation.Contents).use { it.reveal { chars -> chars.concatToString() } },
        )
        reopened.setKey(SearchOperation.Search, null)
        assertFalse(reopened.read().search.hasKey)
        assertTrue(reopened.read().contents.hasKey)
    }

    @Test fun `keys are trimmed on save and blank keys are rejected`() = runTest {
        val preferences = SearchPreferences(MemoryStores("profile"), MemorySecrets())
        Secret("  qr-key \r\n".toCharArray()).use { preferences.setKey(SearchOperation.Search, it) }
        assertEquals(
            "qr-key",
            preferences.credential(SearchOperation.Search).use { it.reveal { chars -> chars.concatToString() } },
        )
        Secret("   ".toCharArray()).use {
            assertEquals(
                SearchFailure.InvalidInput,
                assertFailsWith<SearchException> { preferences.setKey(SearchOperation.Contents, it) }.failure,
            )
        }
        assertFalse(preferences.read().contents.hasKey)
        assertTrue(preferences.read().search.hasKey)
    }
}

private class MemoryStores(id: String) : DataStores {
    override fun filesDirectory(name: String): String = error("File storage is not used by this fake")
    override val owner = StorageOwner.Profile(ProfileId(id))
    private val stores = mutableMapOf<String, MemoryValues>()
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec.name) { MemoryValues(spec) }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("No database used")
    override suspend fun fire(event: DataEvent) = Unit
}

private class MemoryValues(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = MutableStateFlow<Map<String, Any>>(emptyMap())

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>) = values.map { it[key.name] as T? }

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

private class MemorySecrets : SecretStore {
    private val keys = mutableMapOf<SecretKey, String>()
    private val bindings = mutableMapOf<SecretUsage, SecretKey>()
    override suspend fun write(key: SecretKey, value: Secret) {
        keys[key] =
            value.reveal { chars -> chars.concatToString() }
    }
    override suspend fun read(key: SecretKey): Secret? = keys[key]?.let { Secret(it.toCharArray()) }
    override suspend fun keys(): List<SecretKey> = keys.keys.toList()
    override suspend fun bind(usage: SecretUsage, key: SecretKey?) {
        if (key == null) {
            bindings.remove(usage)
        } else {
            require(key in keys)
            bindings[usage] = key
        }
    }
    override suspend fun readFor(usage: SecretUsage): Secret? = bindings[usage]?.let { read(it) }
    override suspend fun usages(key: SecretKey): List<SecretUsage> = bindings.filterValues { it == key }.keys.toList()
    override suspend fun remove(key: SecretKey): SecretRemoval = when {
        usages(key).isNotEmpty() -> SecretRemoval.InUse(usages(key))
        keys.remove(key) != null -> SecretRemoval.Removed
        else -> SecretRemoval.Missing
    }
}
