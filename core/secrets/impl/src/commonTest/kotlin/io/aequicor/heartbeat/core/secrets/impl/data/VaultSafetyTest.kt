package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSafetyTest {
    @Test
    fun explicitWipeDoesNotDecodeCorruptedData() = runTest {
        val env = VaultTestEnv(this)
        val store = env.store()
        val key = SecretKey("secret")
        Secret("personal".toCharArray()).use { store.write(key, it) }
        val namespace = env.backend.disk.keys.single()
        env.backend.disk[namespace] = "not-json".encodeToByteArray()
        env.registry.wipe(ProfileId("alice"))
        assertNull(store.read(key))
    }

    @Test
    fun closingScopeDuringReadErasesUndeliveredSecret() = runTest {
        val env = VaultTestEnv(this)
        val value = Secret("personal".toCharArray())
        var open = true
        assertFailsWith<IllegalStateException> {
            env.registry.access(ProfileId("alice"), "read", false, { check(open) }) {
                open = false
                value
            }
        }
        assertFailsWith<IllegalStateException> { value.reveal { } }
    }

    @Test
    fun cancellationDuringDispatchErasesUndeliveredSecret() = runTest {
        val env = VaultTestEnv(this)
        val value = Secret("personal".toCharArray())
        val read = async {
            val job = currentCoroutineContext().job
            env.registry.access(ProfileId("alice"), "read", false, {}) {
                job.cancel()
                value
            }
        }
        assertFailsWith<CancellationException> { read.await() }
        assertFailsWith<IllegalStateException> { value.reveal { } }
    }

    @Test
    fun logsAndPropagatedFailuresNeverContainDataOrProfileIds() = runTest {
        val lines = mutableListOf<String>()
        Log.init(
            true,
            listOf(LogSink { _, _, error, message -> lines += message + error?.stackTraceToString().orEmpty() }),
        )
        try {
            val env = VaultTestEnv(this)
            val store = env.store("private-profile-email")
            val key = SecretKey("private-key-id")
            Secret("private-value".toCharArray()).use { store.write(key, it) }
            env.backend.failWrite = true
            assertFailsWith<IllegalStateException> { store.remove(key) }
            assertTrue(lines.any { it.contains("vault operation=") })
            assertFalse(lines.joinToString().contains("private-"))
            assertFalse(lines.joinToString().contains("plaintext from backend"))
        } finally {
            Log.init(false)
        }
    }
}
