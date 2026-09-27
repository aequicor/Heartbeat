package io.aequicor.heartbeat.core.secrets.impl

import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSecretStoreTest {
    private val key = SecretKey("credential")
    private val first = SecretUsage("engine", "one", "api-key")
    private val second = SecretUsage("engine", "two", "api-key")

    @Test
    fun rotationReachesEveryConsumerAndDeletionListsBlockers() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        Secret("old".toCharArray()).use { store.write(key, it) }
        store.bind(first, key)
        store.bind(second, key)
        Secret("new".toCharArray()).use { store.write(key, it) }
        assertEquals("new", assertNotNull(store.readFor(first)).text())
        assertEquals("new", assertNotNull(store.readFor(second)).text())
        assertEquals(listOf(key), store.keys())
        assertEquals(SecretRemoval.InUse(listOf(first, second)), store.remove(key))
        store.bind(first, null)
        assertEquals(SecretRemoval.InUse(listOf(second)), store.remove(key))
        store.bind(second, null)
        assertEquals(SecretRemoval.Removed, store.remove(key))
        assertEquals(SecretRemoval.Missing, store.remove(key))
    }

    @Test
    fun profileSwitchAndProcessRestartPreserveValuesAndReferences() = runTest {
        val env = VaultTestEnv(this)
        val alice = env.store()
        Secret("personal".toCharArray()).use { alice.write(key, it) }
        alice.bind(first, key)
        env.scope.isClosed = true
        assertFailsWith<IllegalStateException> { alice.read(key) }
        val freshScope = TestVaultScope(this)
        assertNull(env.store("bob", freshScope).read(key))
        env.restart()
        val reopened = env.store("alice", freshScope)
        assertEquals("personal", assertNotNull(reopened.read(key)).text())
        assertEquals(listOf(first), reopened.usages(key))
        env.registry.wipe(ProfileId("bob"))
        assertNotNull(reopened.read(key)).close()
        env.registry.wipe(ProfileId("alice"))
        assertNull(reopened.read(key))
        assertTrue(reopened.usages(key).isEmpty())
    }

    @Test
    fun rebindingIsAtomicAndMissingTargetsDoNotLoseReferences() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        val other = SecretKey("other")
        Secret("a".toCharArray()).use { store.write(key, it) }
        store.bind(first, key)
        assertFailsWith<IllegalStateException> { store.bind(first, other) }
        assertEquals(listOf(first), store.usages(key))
        Secret("b".toCharArray()).use { store.write(other, it) }
        store.bind(first, other)
        assertEquals("b", assertNotNull(store.readFor(first)).text())
        assertEquals(SecretRemoval.Removed, store.remove(key))
    }

    @Test
    fun failedCommitPreservesOldValueAndDoesNotExposeBackendMessages() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        Secret("old".toCharArray()).use { store.write(key, it) }
        env.backend.failWrite = true
        val error = assertFailsWith<IllegalStateException> {
            Secret("new".toCharArray()).use { store.write(key, it) }
        }
        assertFalse(error.toString().contains("plaintext"))
        assertFalse(error.stackTraceToString().contains("plaintext from backend"))
        assertEquals("old", assertNotNull(store.read(key)).text())
    }

    @Test
    fun corruptionFailsClosedWithoutOverwritingAndCancellationPropagates() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        Secret("old".toCharArray()).use { store.write(key, it) }
        val namespace = env.backend.disk.keys.single()
        env.backend.disk[namespace] = "sensitive invalid json".encodeToByteArray()
        assertFailsWith<IllegalStateException> { store.remove(key) }
        assertEquals("sensitive invalid json", env.backend.disk.getValue(namespace).decodeToString())
        env.backend.beforeRead = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { store.read(key) }
    }

    @Test
    fun competingBindAndDeleteNeverLeaveDanglingReferences() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        Secret("value".toCharArray()).use { store.write(key, it) }
        listOf(
            async { store.bind(first, key) },
            async { assertEquals(SecretRemoval.InUse(listOf(first)), store.remove(key)) },
        ).awaitAll()
        assertEquals("value", assertNotNull(store.readFor(first)).text())
        store.bind(first, null)
        listOf(
            async { assertEquals(SecretRemoval.Removed, store.remove(key)) },
            async { assertFailsWith<IllegalStateException> { store.bind(first, key) } },
        ).awaitAll()
        assertNull(store.readFor(first))
        assertTrue(store.usages(key).isEmpty())
    }
}
