package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProtectedHarnessDataTest {
    @Test
    fun `recreated repository reads values and revisions from protected storage`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val first = ProtectedHarnessData(secrets)
        first.write(owner, "state", JsonPrimitive("private progress"), 0) {}
        val restored = ProtectedHarnessData(secrets).read(owner, "state") {}
        assertEquals(JsonPrimitive("private progress"), restored.value)
        assertEquals(1L, restored.revision)
        assertFalse("private progress" in restored.toString())
        assertEquals(1L, first.revision(owner).first())
    }

    @Test
    fun `owners are isolated and a revision conflict preserves the newer value`() = runTest {
        val storage = harnessTestData()
        val another = HarnessId("another")
        storage.write(owner, "state", JsonPrimitive(1), 0) {}
        val conflict = storage.write(owner, "state", JsonPrimitive(2), 0) {}
        assertEquals(JsonPrimitive(1), assertIs<ScriptDataWriteResult.Conflict>(conflict).current.value)
        assertNull(storage.read(another, "state") {}.value)
        assertEquals(1L, storage.revision(owner).first())
        assertEquals(0L, storage.revision(another).first())
    }

    @Test
    fun `deletion keeps a namespace revision without per-key tombstones`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        storage.write(owner, "state", JsonNull, 0) {}
        assertEquals(JsonNull, storage.read(owner, "state") {}.value)
        storage.write(owner, "state", null, 1) {}
        assertNull(storage.read(owner, "state") {}.value)
        assertEquals(2L, storage.read(owner, "missing") {}.revision)
        assertIs<ScriptDataWriteResult.Conflict>(storage.write(owner, "state", JsonPrimitive(3), 0) {})
        assertFalse("state" in secrets.values.values.single())
    }

    @Test
    fun `unchanged writes and reads do not invalidate owner surfaces`() = runTest {
        val storage = harnessTestData()
        storage.read(owner, "state") {}
        storage.write(owner, "state", null, 0) {}
        assertEquals(0L, storage.revision(owner).first())
        storage.write(owner, "state", JsonPrimitive(2), 0) {}
        storage.write(owner, "state", JsonPrimitive(2), 1) {}
        storage.read(owner, "state") {}
        assertEquals(1L, storage.revision(owner).first())
    }

    @Test
    fun `invalid keys and oversized UTF-8 data never change storage or revision`() = runTest {
        val storage = harnessTestData()
        for (key in listOf("", "../state", "Private name", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { storage.write(owner, key, JsonNull, 0) {} }
            assertFailsWith<IllegalArgumentException> { storage.read(owner, key) {} }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.write(owner, "state", JsonPrimitive("я".repeat(HarnessLimits.DATA_BYTES / 2)), 0) {}
        }
        repeat(HarnessLimits.DATA_KEYS) { index ->
            storage.write(owner, "key_$index", JsonNull, index.toLong()) {}
        }
        assertFailsWith<IllegalArgumentException> {
            storage.write(owner, "overflow", JsonNull, HarnessLimits.DATA_KEYS.toLong()) {}
        }
        assertNull(storage.read(owner, "overflow") {}.value)
        assertEquals(HarnessLimits.DATA_KEYS.toLong(), storage.revision(owner).first())
    }

    @Test
    fun `storage failure neither publishes a revision nor looks like missing data`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        secrets.beforeWrite = { error("private backend payload") }
        val failure = assertFailsWith<IllegalStateException> { storage.write(owner, "state", JsonNull, 0) {} }
        assertFalse("private backend payload" in failure.toString())
        val causes = generateSequence<Throwable>(failure) { it.cause }
        assertFalse(causes.any { "private backend payload" in it.toString() })
        assertEquals(0L, storage.revision(owner).first())
        secrets.beforeRead = { error("unreadable") }
        assertFailsWith<IllegalStateException> { storage.read(owner, "state") {} }
        secrets.beforeRead = {}
        secrets.values[SecretKey("harness_data_" + "ignored")] = "foreign malformed data"
        secrets.beforeWrite = {}
        storage.write(owner, "state", JsonNull, 0) {}
        val key = secrets.values.keys.single { !it.value.endsWith("ignored") }
        secrets.values[key] = "corrupt private value"
        assertFailsWith<IllegalStateException> { storage.read(owner, "state") {} }
    }

    @Test
    fun `publication is required and revocation during IO prevents a later commit`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        val access = access()
        val script = HarnessScriptData(owner, access, storage)
        access.isActive = false
        assertFailsWith<IllegalStateException> { script.read("state") }
        assertFailsWith<IllegalStateException> { script.update("state") { JsonNull } }
        access.isActive = true
        secrets.beforeRead = { access.isActive = false }
        assertFailsWith<IllegalStateException> { script.write("state", JsonNull, 0) }
        assertEquals(emptyMap(), secrets.values)
        assertEquals(0L, storage.revision(owner).first())
    }

    @Test
    fun `concurrent script items update shared data without dropping changes`() = runTest {
        val secrets = HarnessDataTestSecrets().apply { beforeRead = { yield() } }
        val storage = ProtectedHarnessData(secrets)
        val scripts = List(2) { HarnessScriptData(owner, access(), storage) }
        List(40) { index ->
            async {
                scripts[index % 2].update("count") { previous ->
                    JsonPrimitive(((previous as? JsonPrimitive)?.int ?: 0) + 1)
                }
            }
        }.awaitAll()
        assertEquals(JsonPrimitive(40), scripts.first().read("count").value)
        assertEquals(40L, storage.revision(owner).first())
    }

    @Test
    fun `an admitted commit publishes after persistence even when caller is cancelled`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        secrets.beforeWrite = {
            writing.complete(Unit)
            finish.await()
        }
        val task = launch { storage.write(owner, "state", JsonNull, 0) {} }
        writing.await()
        assertEquals(0L, storage.revision(owner).first())
        task.cancel()
        finish.complete(Unit)
        task.join()
        assertEquals(1L, storage.revision(owner).first())
        assertEquals(JsonNull, storage.read(owner, "state") {}.value)
    }

    @Test
    fun `deletion waits for an admitted write then prevents retired script from recreating its data`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        val access = access()
        val script = HarnessScriptData(owner, access, storage)
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        secrets.beforeWrite = {
            writing.complete(Unit)
            finish.await()
        }
        val write = async { script.write("state", JsonPrimitive(5), 0) }
        writing.await()
        // HarnessLibraryAdmission denies pending removals, then runtime.revokeHarness retires the instance,
        // both before HarnessEffects calls library.remove and its retainOwners cleanup.
        access.isActive = false
        val cleanup = async { storage.retainOwners(emptySet()) }
        finish.complete(Unit)
        write.await()
        cleanup.await()
        assertFailsWith<IllegalStateException> { script.write("state", JsonPrimitive(6), 0) }
        assertEquals(emptyMap(), secrets.values)
        assertEquals(0L, storage.revision(owner).first())
    }

    @Test
    fun `library recovery deletes orphan data while preserving existing owners and unrelated secrets`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedHarnessData(secrets)
        val kept = HarnessId("kept")
        storage.write(owner, "state", JsonNull, 0) {}
        storage.write(kept, "state", JsonPrimitive(1), 0) {}
        secrets.values[SecretKey("provider_credentials")] = "never touched"
        storage.retainOwners(setOf(kept))
        storage.retainOwners(setOf(kept))
        assertNull(storage.read(owner, "state") {}.value)
        assertEquals(JsonPrimitive(1), storage.read(kept, "state") {}.value)
        assertEquals("never touched", secrets.values[SecretKey("provider_credentials")])
        assertEquals(2, secrets.values.size)
    }

    private fun TestScope.access() = DataTestAccess(this)
}

private class DataTestAccess(scope: TestScope) : HarnessInstanceAccess {
    override val scope = scope.backgroundScope
    override val dispatcher = StandardTestDispatcher(scope.testScheduler)
    override var isActive = true
    override suspend fun awaitPublication() = isActive
}

private val owner = HarnessId("owner with / unicode имя")
