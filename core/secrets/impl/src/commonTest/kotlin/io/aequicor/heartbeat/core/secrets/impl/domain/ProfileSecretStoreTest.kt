package io.aequicor.heartbeat.core.secrets.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretUsage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileSecretStoreTest {
    @Test
    fun rotationAndDeletionDependOnlyOnRegisteredReferences() = runTest {
        FakeSecretRepository().use { repository ->
            val store = ProfileSecretStore(repository)
            val key = SecretKey("shared")
            val first = SecretUsage("engine", "one", "api-key")
            val second = SecretUsage("engine", "two", "api-key")
            Secret("old".toCharArray()).use { store.write(key, it) }
            store.bind(first, key)
            store.bind(second, key)
            Secret("new".toCharArray()).use { store.write(key, it) }
            listOf(first, second).forEach { usage ->
                assertNotNull(store.readFor(usage)).use { value ->
                    value.reveal { assertEquals("new", it.concatToString()) }
                }
            }
            assertEquals(listOf(key), store.keys())
            assertEquals(SecretRemoval.InUse(listOf(first, second)), store.remove(key))
            store.bind(first, null)
            assertEquals(SecretRemoval.InUse(listOf(second)), store.remove(key))
            store.bind(second, null)
            assertEquals(SecretRemoval.Removed, store.remove(key))
            assertEquals(SecretRemoval.Missing, store.remove(key))
        }
    }

    @Test
    fun invalidRebindPreservesExistingReferenceAndDoesNotCommit() = runTest {
        FakeSecretRepository().use { repository ->
            val store = ProfileSecretStore(repository)
            val key = SecretKey("present")
            val usage = SecretUsage("engine", "one", "api-key")
            Secret("value".toCharArray()).use { store.write(key, it) }
            store.bind(usage, key)
            val commits = repository.commits
            assertFailsWith<IllegalArgumentException> { store.bind(usage, SecretKey("missing")) }
            assertEquals(commits, repository.commits)
            assertEquals(listOf(usage), store.usages(key))
            assertEquals(SecretRemoval.InUse(listOf(usage)), store.remove(key))
        }
    }

    @Test
    fun replacementAndRemovalEraseSupersededTransactionBuffers() = runTest {
        val snapshot = SecretSnapshot()
        val repository = object : SecretRepository {
            override suspend fun <T> transaction(hasChanges: Boolean, action: (SecretSnapshot) -> T): T = action(
                snapshot,
            )
        }
        snapshot.use {
            val store = ProfileSecretStore(repository)
            val key = SecretKey("key")
            Secret("old".toCharArray()).use { store.write(key, it) }
            val old = snapshot.values.getValue(key)
            Secret("new".toCharArray()).use { store.write(key, it) }
            assertTrue(old.all { it == '\u0000' })
            val replaced = snapshot.values.getValue(key)
            store.remove(key)
            assertTrue(replaced.all { it == '\u0000' })
        }
    }
}

private class FakeSecretRepository :
    SecretRepository,
    AutoCloseable {
    private var persisted = SecretSnapshot()
    var commits = 0
        private set

    override suspend fun <T> transaction(hasChanges: Boolean, action: (SecretSnapshot) -> T): T {
        val working = persisted.copySnapshot()
        working.use {
            val result = action(working)
            if (hasChanges) {
                val next = working.copySnapshot()
                persisted.close()
                persisted = next
                commits++
            }
            return result
        }
    }

    override fun close() = persisted.close()

    private fun SecretSnapshot.copySnapshot(): SecretSnapshot = SecretSnapshot(
        values.mapValues { it.value.copyOf() }.toMutableMap(),
        references.toMutableMap(),
    )
}
