package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.NewAuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
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
    fun `each method builds its own source kind`() {
        Secret("sk".toCharArray()).use { key ->
            assertIs<NewAuthSource.ManagedKey>(apiKey.newSource("Work", key = key))
            assertFailsWith<IllegalArgumentException> { local.newSource("Local", key = key) }
        }
        assertFailsWith<IllegalArgumentException> { apiKey.newSource("Work") }
        assertIs<NewAuthSource.CliLogin>(cli.newSource("CLI"))
        assertIs<NewAuthSource.NoAuth>(local.newSource("Local"))
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
