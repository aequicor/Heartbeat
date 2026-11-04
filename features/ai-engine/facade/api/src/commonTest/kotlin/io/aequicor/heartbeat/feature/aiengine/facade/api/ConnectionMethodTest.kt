package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConnectionMethodTest {
    private val openAi = ProviderInfo(ProviderId("openai"), "OpenAI", "https://platform.openai.com/api-keys")
    private val fixed = EndpointOrigin("https://api.openai.com")
    private val apiKey = ConnectionMethod.ApiKey(ConnectionMethodId("openai.key"), openAi, fixed)
    private val local = ConnectionMethod.NoAuth(
        ConnectionMethodId("ollama"),
        ProviderInfo(ProviderId("ollama"), "Ollama"),
        EndpointOrigin("http://localhost:11434"),
    )
    private val cli = ConnectionMethod.CliLogin(
        ConnectionMethodId("codex.cli"),
        openAi,
        fixed,
        AuthOwnerId("codex"),
        AuthLocationId("codex.local"),
    )

    @Test
    fun `fixed origin cannot be replaced by user input`() {
        assertEquals(AuthScope(openAi.id, fixed), apiKey.scopeFor())
        assertFailsWith<IllegalArgumentException> { apiKey.scopeFor(EndpointOrigin("https://proxy.example.com")) }
        val lan = EndpointOrigin("http://192.168.1.5:11434")
        assertEquals(AuthScope(ProviderId("ollama"), lan), local.scopeFor(lan))
    }

    @Test
    fun `each method creates its own source kind`() = runTest {
        val sources = RecordingSources()
        Secret("sk".toCharArray()).use { key ->
            assertIs<AuthSource.ManagedKey>(sources.create(apiKey, "Work", key = key))
            assertFailsWith<IllegalArgumentException> { sources.create(local, "Local", key = key) }
        }
        assertFailsWith<IllegalArgumentException> { sources.create(apiKey, "Work") }
        sources.create(cli, "CLI")
        sources.create(local, "Local")
        assertIs<AuthSourceDraft.CliLogin>(sources.drafts[0])
        assertIs<AuthSourceDraft.NoAuth>(sources.drafts[1])
        assertEquals(2, sources.drafts.size)
    }

    @Test
    fun `edit enforces provider source kind and fixed endpoint before storage`() = runTest {
        val sources = RecordingSources()
        val managed = Secret("key".toCharArray()).use { sources.addManagedKey("Work", apiKey.scopeFor(), it) }
        assertFailsWith<IllegalArgumentException> {
            sources.edit(apiKey, managed, "Work", origin = EndpointOrigin("https://proxy.example.com")) {}
        }
        assertFailsWith<IllegalArgumentException> { sources.edit(local, managed, "Work") {} }
        assertFailsWith<IllegalStateException> { sources.edit(cli, managed, "Work") {} }
        assertEquals(0, sources.edits)

        sources.edit(apiKey, managed, "Renamed") {}
        assertEquals(1, sources.edits)
        assertEquals(apiKey.scopeFor(), sources.editScope)
    }

    @Test
    fun `editing a label preserves an existing custom endpoint and path`() = runTest {
        val sources = RecordingSources()
        val method = apiKey.copy(isOriginEditable = true, isPathEditable = true)
        val custom = method.scopeFor(EndpointOrigin("https://proxy.example.com"), "/v1")
        val managed = Secret("key".toCharArray()).use { sources.addManagedKey("Work", custom, it) }

        sources.edit(method, managed, "Renamed") {}

        assertEquals(custom, sources.editScope)
    }

    @Test
    fun `editing no-auth does not accept a key or a managed source`() = runTest {
        val sources = RecordingSources()
        val noAuth = sources.register(AuthSourceDraft.NoAuth("Local", local.scopeFor()))
        val managed = Secret("key".toCharArray()).use { sources.addManagedKey("Local", local.scopeFor(), it) }
        Secret("key".toCharArray()).use { key ->
            assertFailsWith<IllegalArgumentException> { sources.edit(local, noAuth, "Local", key = key) {} }
        }
        assertFailsWith<IllegalArgumentException> { sources.edit(local, managed, "Local") {} }
        assertEquals(0, sources.edits)
        sources.edit(local, noAuth, "Local") {}
        assertEquals(1, sources.edits)
    }

    @Test
    fun `descriptor rejects duplicate methods and foreign CLI owners`() {
        assertFailsWith<IllegalArgumentException> { descriptor(listOf(apiKey, apiKey)) }
        assertFailsWith<IllegalArgumentException> { ProviderInfo(ProviderId("x"), "X", "http://keys.example.com") }
        val registration = { methods: List<ConnectionMethod> ->
            EngineRegistration(descriptor(methods), AuthOwnerId("claude.code"), lazy { error("not constructed") })
        }
        assertFailsWith<IllegalArgumentException> { registration(listOf(cli)) }
        registration(listOf(apiKey, local))
    }

    private fun descriptor(methods: List<ConnectionMethod>) = EngineDescriptor(
        EngineId("test"),
        "Test",
        EngineFamily.MultiProvider,
        setOf(EnginePlatform.DesktopWindows),
        AiEngines,
        connectionMethods = methods,
    )
}

private class RecordingSources : AuthSources {
    val drafts = mutableListOf<AuthSourceDraft>()
    var edits = 0
    var editScope: AuthScope? = null
    override val state: StateFlow<List<AuthSource>> = MutableStateFlow(emptyList())
    private val info = AuthSourceInfo(AuthSourceId("s"), "label", AuthRevision.Unknown)

    override suspend fun get(id: AuthSourceId): AuthSource? = null

    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): AuthSource.ManagedKey =
        AuthSource.ManagedKey(info, scope, AuthSecretId("secret"))

    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): AuthSource.ManagedKey = error("unused")

    override suspend fun register(draft: AuthSourceDraft): AuthSource {
        drafts += draft
        return AuthSource.NoAuth(info, draft.scope)
    }

    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): AuthSource = error("unused")

    override suspend fun forget(id: AuthSourceId): Unit = error("unused")

    override suspend fun editIfUnchanged(
        expected: AuthSource,
        label: String,
        scope: AuthScope,
        key: Secret?,
        onSaved: (AuthSource) -> Unit,
    ) {
        edits++
        editScope = scope
        onSaved(expected)
    }
}
