package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StoredLocalWorkspacesTest {
    @Test
    fun `managed checkout resolves after restart without appearing as a user project`() = runTest {
        val stores = WorkspaceTestStores()
        val first = StoredLocalWorkspaces(stores, FakeDirectories())
        val checkout = first.registerManaged("/projects/demo")
        assertEquals(emptyList(), first.observe().first())
        val reopened = StoredLocalWorkspaces(stores, FakeDirectories())
        assertEquals("/projects/demo", reopened.resolve(checkout.ref))
        assertEquals(emptyList(), reopened.observe().first())
        assertEquals(checkout, reopened.register("/projects/demo"))
        assertEquals(listOf(checkout), reopened.observe().first())
    }

    @Test
    fun `canonical aliases reuse identity and registration survives registry recreation`() = runTest {
        val stores = WorkspaceTestStores()
        val directories = FakeDirectories()
        val first = StoredLocalWorkspaces(stores, directories)
        val project = first.register("/projects/demo")
        assertEquals(project, first.register("/projects/demo/../demo"))
        val reopened = StoredLocalWorkspaces(stores, directories)
        assertEquals(listOf(project), reopened.observe().first())
        assertEquals("/projects/demo", reopened.resolve(project.ref))
        assertEquals("demo", project.name)
        assertFalse(project.toString().contains("/projects"))
        assertFalse(stores.keyValue(LocalWorkspacesSpec).spec.areValuesLogged)
    }

    @Test
    fun `invalid registrations and removed directories cannot become a native working directory`() = runTest {
        val directories = FakeDirectories()
        val registry = StoredLocalWorkspaces(WorkspaceTestStores(), directories)
        assertFailsWith<IllegalArgumentException> { registry.register("/missing") }
        assertEquals(emptyList(), registry.observe().first())
        assertNull(registry.resolve(WorkspaceRef("unknown")))
        val project = registry.register("/projects/demo")
        directories.current = null
        assertNull(registry.resolve(project.ref))
        assertEquals(listOf(project), registry.observe().first())
        directories.current = WorkspaceDirectory("/different", "different")
        assertNull(registry.resolve(project.ref))
    }

    @Test
    fun `unreadable metadata is preserved when registration fails`() = runTest {
        val stores = WorkspaceTestStores()
        val store = stores.keyValue(LocalWorkspacesSpec)
        val key = stringKey("projects")
        store.set(key, "broken private metadata")
        val registry = StoredLocalWorkspaces(stores, FakeDirectories())
        val error = assertFailsWith<IllegalStateException> { registry.register("/projects/demo") }
        assertFalse(error.toString().contains("private metadata"))
        assertEquals("broken private metadata", store.get(key))
    }

    @Test
    fun `unsupported platforms expose no projects and explicitly reject filesystem operations`() = runTest {
        val registry = StoredLocalWorkspaces(WorkspaceTestStores(), FakeDirectories(isAvailable = false))
        assertFalse(registry.isAvailable)
        assertEquals(emptyList(), registry.observe().first())
        assertFailsWith<UnsupportedOperationException> { registry.register("/projects/demo") }
        assertFailsWith<UnsupportedOperationException> { registry.resolve(WorkspaceRef("id")) }
    }

    private class FakeDirectories(override val isAvailable: Boolean = true) : WorkspaceDirectories {
        var current: WorkspaceDirectory? = WorkspaceDirectory("/projects/demo", "demo")
        override suspend fun canonical(directory: String): WorkspaceDirectory? =
            current.takeIf { directory.startsWith("/projects/") }
    }
}

private class WorkspaceTestStores : DataStores {
    private val stores = mutableMapOf<String, KeyValueStore>()
    override val owner: StorageOwner = StorageOwner.App
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = stores.getOrPut(spec.name) { WorkspaceTestStore(spec) }
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Not used")
    override suspend fun fire(event: DataEvent) = Unit
}

private class WorkspaceTestStore(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = MutableStateFlow(emptyMap<String, Any>())

    // Test fake retains values as typed by the caller's StoreKey.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it[key.name] as T? }

    // Test fake retains values as typed by the caller's StoreKey.
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
