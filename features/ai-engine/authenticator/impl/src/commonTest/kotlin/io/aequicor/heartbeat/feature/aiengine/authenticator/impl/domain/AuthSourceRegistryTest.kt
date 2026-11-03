package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal val TestAuthScope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))

internal class FakeSourceStore : AuthSourceStore {
    val sources = MutableStateFlow(emptyList<AuthSource>())
    var saveFailure: Exception? = null
    var afterSave: suspend () -> Unit = {}

    override fun observe(): Flow<List<AuthSource>> = sources

    override suspend fun load(): List<AuthSource> = sources.value

    override suspend fun save(sources: List<AuthSource>) {
        saveFailure?.let { throw it }
        this.sources.value = sources
        afterSave()
    }
}

internal class FakeVault : ManagedKeyVault {
    val values = mutableMapOf<AuthSecretId, String>()
    var removeFailure: Exception? = null
    var storeFailureAfterWrite: Exception? = null

    override suspend fun store(secret: AuthSecretId, key: Secret) {
        values[secret] = key.reveal { it.concatToString() }
        storeFailureAfterWrite?.let { throw it }
    }

    override suspend fun read(secret: AuthSecretId): Secret? = values[secret]?.let { Secret(it.toCharArray()) }

    override suspend fun contains(secret: AuthSecretId): Boolean = secret in values

    override suspend fun remove(secret: AuthSecretId) {
        removeFailure?.let { throw it }
        values -= secret
    }
}

internal class FakeAuthEditJournal : AuthSourceEditJournal {
    var pending: PendingAuthSourceEdit? = null
    override suspend fun load(): PendingAuthSourceEdit? = pending
    override suspend fun save(edit: PendingAuthSourceEdit) {
        pending = edit
    }
    override suspend fun clear() {
        pending = null
    }
}

class AuthSourceRegistryTest {
    private val store = FakeSourceStore()
    private val vault = FakeVault()
    private val edits = FakeAuthEditJournal()
    private var counter = 0

    private fun TestScope.registry() = AuthSourceRegistry(store, vault, backgroundScope, edits) { "t${++counter}" }

    @Test
    fun `editing a managed endpoint and key swaps the secret only after saving metadata`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        val scope = TestAuthScope.copy(origin = EndpointOrigin("https://new.example.com"))
        var saved: AuthSource? = null

        registry.editIfUnchanged(old, "new", scope, Secret("new-key".toCharArray())) { saved = it }

        val updated = assertIs<AuthSource.ManagedKey>(saved)
        assertEquals(old.info.id, updated.info.id)
        assertEquals(scope, updated.scope)
        assertNotEquals(old.info.revision, updated.info.revision)
        assertNotEquals(old.secret, updated.secret)
        assertEquals("new-key", vault.values[updated.secret])
        assertNull(vault.values[old.secret])
        assertEquals(updated, registry.get(old.info.id))
        assertNull(edits.pending)
    }

    @Test
    fun `stale edit cannot overwrite a newer source or create a key`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        registry.editIfUnchanged(old, "first", TestAuthScope, null) {}
        val after = registry.get(old.info.id)
        val failure = assertFailsWith<AuthException> {
            registry.editIfUnchanged(old, "second", TestAuthScope, Secret("new-key".toCharArray())) {}
        }
        assertEquals(AuthFailureReason.SourceChanged, failure.failure.reason)
        assertEquals(after, registry.get(old.info.id))
        assertEquals(mapOf(old.secret to "old-key"), vault.values)
        assertNull(edits.pending)
    }

    @Test
    fun `failed staged key is recovered without deleting the original`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        vault.storeFailureAfterWrite = IllegalStateException("vault")
        assertFailsWith<IllegalStateException> {
            registry.editIfUnchanged(old, "new", TestAuthScope, Secret("new-key".toCharArray())) {}
        }
        assertEquals(old, registry.get(old.info.id))
        assertEquals(2, vault.values.size)
        assertTrue(edits.pending != null)

        vault.storeFailureAfterWrite = null
        registry.register(AuthSourceDraft.NoAuth("local", TestAuthScope))
        assertEquals(mapOf(old.secret to "old-key"), vault.values)
        assertNull(edits.pending)
    }

    @Test
    fun `ambiguous metadata failure is reconciled from the actual saved source`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        store.afterSave = { throw IllegalStateException("after commit") }
        var receipt: AuthSource? = null
        assertFailsWith<IllegalStateException> {
            registry.editIfUnchanged(old, "new", TestAuthScope, Secret("new-key".toCharArray())) { receipt = it }
        }
        assertNull(receipt)
        val saved = assertIs<AuthSource.ManagedKey>(registry.get(old.info.id))
        assertNotEquals(old.secret, saved.secret)
        assertEquals(2, vault.values.size)
        assertTrue(edits.pending != null)

        store.afterSave = {}
        registry.register(AuthSourceDraft.NoAuth("local", TestAuthScope))
        assertNull(vault.values[old.secret])
        assertEquals("new-key", vault.values[saved.secret])
        assertNull(edits.pending)
    }

    @Test
    fun `cleanup failure retains a confirmed edit and is retried before another write`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        vault.removeFailure = IllegalStateException("vault")
        var receipt: AuthSource? = null
        registry.editIfUnchanged(old, "new", TestAuthScope, Secret("new-key".toCharArray())) { receipt = it }
        val saved = assertIs<AuthSource.ManagedKey>(receipt)
        assertEquals(saved, registry.get(old.info.id))
        assertTrue(edits.pending != null)
        assertFailsWith<IllegalStateException> { registry.register(AuthSourceDraft.NoAuth("local", TestAuthScope)) }

        vault.removeFailure = null
        registry.register(AuthSourceDraft.NoAuth("local", TestAuthScope))
        assertNull(vault.values[old.secret])
        assertEquals("new-key", vault.values[saved.secret])
        assertNull(edits.pending)
    }

    @Test
    fun `cancellation during metadata commit still delivers its saved receipt`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.afterSave = {
            entered.complete(Unit)
            release.await()
        }
        var receipt: AuthSource? = null
        val edit = async {
            registry.editIfUnchanged(old, "new", TestAuthScope, Secret("new-key".toCharArray())) { receipt = it }
        }
        entered.await()
        edit.cancel()
        release.complete(Unit)
        edit.join()
        val saved = assertIs<AuthSource.ManagedKey>(receipt)
        assertEquals(saved, registry.get(old.info.id))
        assertEquals("new-key", vault.values[saved.secret])
        assertNull(vault.values[old.secret])
    }

    @Test
    fun `unexpected metadata during recovery preserves both keys and blocks mutations`() = runTest {
        val registry = registry()
        val old = registry.addManagedKey("old", TestAuthScope, Secret("old-key".toCharArray()))
        vault.removeFailure = IllegalStateException("vault")
        registry.editIfUnchanged(old, "new", TestAuthScope, Secret("new-key".toCharArray())) {}
        val saved = assertIs<AuthSource.ManagedKey>(registry.get(old.info.id))
        store.sources.value = listOf(saved.copy(info = saved.info.copy(label = "changed elsewhere")))
        vault.removeFailure = null

        assertFailsWith<IllegalStateException> { registry.register(AuthSourceDraft.NoAuth("local", TestAuthScope)) }
        assertEquals("old-key", vault.values[old.secret])
        assertEquals("new-key", vault.values[saved.secret])
        assertTrue(edits.pending != null)
    }

    @Test
    fun `editing a no-auth endpoint changes its revision without touching the vault`() = runTest {
        val registry = registry()
        val old = registry.register(AuthSourceDraft.NoAuth("old", TestAuthScope))
        val scope = TestAuthScope.copy(origin = EndpointOrigin("https://new.example.com"))
        var receipt: AuthSource? = null

        registry.editIfUnchanged(old, "new", scope, null) { receipt = it }

        val saved = assertIs<AuthSource.NoAuth>(receipt)
        assertEquals(scope, saved.scope)
        assertNotEquals(old.info.revision, saved.info.revision)
        assertTrue(vault.values.isEmpty())
        assertNull(edits.pending)
    }

    @Test
    fun `managed key stores the value in the vault and only a reference in metadata`() = runTest {
        val registry = registry()
        val source = registry.addManagedKey("work", TestAuthScope, Secret("sk-value".toCharArray()))
        runCurrent()

        assertEquals(AuthSourceId("src_t1"), source.info.id)
        assertEquals(AuthRevision.Known("t2"), source.info.revision)
        assertEquals("sk-value", vault.values[source.secret])
        assertEquals(listOf<AuthSource>(source), registry.state.value)
        assertEquals(source, registry.get(source.info.id))
    }

    @Test
    fun `failed metadata write removes the orphan value`() = runTest {
        store.saveFailure = IllegalStateException("disk")
        assertFailsWith<IllegalStateException> {
            registry().addManagedKey("work", TestAuthScope, Secret("sk-value".toCharArray()))
        }
        assertTrue(vault.values.isEmpty())
    }

    @Test
    fun `replacing a managed key keeps its id and changes the revision`() = runTest {
        val registry = registry()
        val source = registry.addManagedKey("work", TestAuthScope, Secret("old".toCharArray()))
        val replaced = registry.replaceManagedKey(source.info.id, Secret("new".toCharArray()))

        assertEquals(source.info.id, replaced.info.id)
        assertNotEquals(source.info.revision, replaced.info.revision)
        assertEquals("new", vault.values[source.secret])
        val missing = assertFailsWith<AuthException> {
            registry.replaceManagedKey(AuthSourceId("src_missing"), Secret("x".toCharArray()))
        }
        assertEquals(AuthFailureReason.SourceUnavailable, missing.failure.reason)
    }

    @Test
    fun `a failed replacement never keeps the old revision for a new value`() = runTest {
        val registry = registry()
        val source = registry.addManagedKey("work", TestAuthScope, Secret("old".toCharArray()))
        store.saveFailure = IllegalStateException("disk")

        assertFailsWith<IllegalStateException> {
            registry.replaceManagedKey(
                source.info.id,
                Secret("new".toCharArray()),
            )
        }

        assertEquals("old", vault.values[source.secret])
        assertEquals(source, registry.get(source.info.id))
    }

    @Test
    fun `external sources record owner revisions but managed keys do not`() = runTest {
        val registry = registry()
        val cli = registry.register(
            AuthSourceDraft.CliLogin("cli", TestAuthScope, AuthOwnerId("codex"), AuthLocationId("default")),
        )
        assertIs<AuthSource.CliLogin>(cli)
        assertEquals(AuthRevision.Unknown, cli.info.revision)

        val updated = registry.updateRevision(cli.info.id, AuthRevision.Known("account-2"))
        assertEquals(AuthRevision.Known("account-2"), updated.info.revision)

        val managed = registry.addManagedKey("work", TestAuthScope, Secret("v".toCharArray()))
        assertFailsWith<IllegalArgumentException> { registry.updateRevision(managed.info.id, AuthRevision.Unknown) }
    }

    @Test
    fun `forgetting removes managed values but never touches external credentials`() = runTest {
        val registry = registry()
        val managed = registry.addManagedKey("work", TestAuthScope, Secret("v".toCharArray()))
        val external = registry.register(AuthSourceDraft.ExternalKey("env", TestAuthScope, AuthLocationId("env.key")))

        registry.forget(managed.info.id)
        registry.forget(external.info.id)
        registry.forget(external.info.id)

        assertTrue(vault.values.isEmpty())
        assertTrue(store.sources.value.isEmpty())
        assertNull(registry.get(managed.info.id))
    }

    @Test
    fun `blank labels are rejected before any write`() = runTest {
        assertFailsWith<IllegalArgumentException> { registry().register(AuthSourceDraft.NoAuth(" ", TestAuthScope)) }
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun `a failed orphan rollback keeps the original error and suppresses the rollback one`() = runTest {
        val original = IllegalStateException("disk")
        store.saveFailure = original
        vault.removeFailure = IllegalArgumentException("vault")

        val thrown = assertFailsWith<IllegalStateException> {
            registry().addManagedKey("work", TestAuthScope, Secret("sk-value".toCharArray()))
        }

        assertSame(original, thrown)
        assertIs<IllegalArgumentException>(thrown.suppressedExceptions.single())
    }

    @Test
    fun `a managed key whose value cannot be removed is not forgotten`() = runTest {
        val registry = registry()
        val managed = registry.addManagedKey("work", TestAuthScope, Secret("v".toCharArray()))
        vault.removeFailure = IllegalStateException("in use")

        assertFailsWith<IllegalStateException> { registry.forget(managed.info.id) }

        assertEquals(managed, registry.get(managed.info.id))
        assertEquals("v", vault.values[managed.secret])
    }
}
