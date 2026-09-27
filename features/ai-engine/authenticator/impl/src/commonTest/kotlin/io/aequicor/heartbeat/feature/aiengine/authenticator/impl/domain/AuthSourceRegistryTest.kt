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
import kotlin.test.assertTrue

internal val TestAuthScope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))

internal class FakeSourceStore : AuthSourceStore {
    val sources = MutableStateFlow(emptyList<AuthSource>())
    var saveFailure: Exception? = null

    override fun observe(): Flow<List<AuthSource>> = sources

    override suspend fun load(): List<AuthSource> = sources.value

    override suspend fun save(sources: List<AuthSource>) {
        saveFailure?.let { throw it }
        this.sources.value = sources
    }
}

internal class FakeVault : ManagedKeyVault {
    val values = mutableMapOf<AuthSecretId, String>()

    override suspend fun store(secret: AuthSecretId, key: Secret) {
        values[secret] = key.reveal { it.concatToString() }
    }

    override suspend fun read(secret: AuthSecretId): Secret? = values[secret]?.let { Secret(it.toCharArray()) }

    override suspend fun contains(secret: AuthSecretId): Boolean = secret in values

    override suspend fun remove(secret: AuthSecretId) {
        values -= secret
    }
}

class AuthSourceRegistryTest {
    private val store = FakeSourceStore()
    private val vault = FakeVault()
    private var counter = 0

    private fun TestScope.registry() = AuthSourceRegistry(store, vault, backgroundScope) { "t${++counter}" }

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
}
