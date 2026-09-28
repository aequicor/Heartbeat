package io.aequicor.heartbeat.feature.aiengine.koog.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KoogCompatibilityTest {
    private val info = AuthSourceInfo(AuthSourceId("source"), "Label", AuthRevision.Known("1"))
    private val scope = AuthScope(KoogProvider.OpenAI.id, KoogProvider.OpenAI.origin)
    private val key = AuthSource.ManagedKey(info, scope, AuthSecretId("vault_key"))

    @Test
    fun managedKeyRequiresExactProviderAndOrigin() {
        assertEquals(KoogProvider.OpenAI, koogProvider(key))
        assertNull(koogProvider(key.copy(scope = scope.copy(origin = EndpointOrigin("https://other.example")))))
        assertNull(koogProvider(key.copy(scope = scope.copy(provider = KoogProvider.Anthropic.id))))
    }

    @Test
    fun alibabaTokenPlanCredentialsAreIsolatedFromOtherProvidersAndEndpoints() {
        val alibaba = AuthScope(KoogProvider.AlibabaQwen.id, KoogProvider.AlibabaQwen.origin)
        assertEquals(KoogProvider.AlibabaQwen, koogProvider(key.copy(scope = alibaba)))
        assertNull(koogProvider(AuthSource.NoAuth(info, alibaba)))
        assertNull(koogProvider(key.copy(scope = alibaba.copy(provider = KoogProvider.OpenAI.id))))
        listOf(
            KoogProvider.OpenAI.origin,
            EndpointOrigin("https://dashscope.aliyuncs.com"),
            EndpointOrigin("https://other.example"),
        ).forEach { origin ->
            assertNull(koogProvider(key.copy(scope = alibaba.copy(origin = origin))))
        }
    }

    @Test
    fun localOllamaDoesNotReceiveCloudCredentials() {
        val local = AuthScope(KoogProvider.Ollama.id, KoogProvider.Ollama.origin)
        assertEquals(KoogProvider.Ollama, koogProvider(AuthSource.NoAuth(info, local)))
        assertNull(koogProvider(key.copy(scope = local)))
        assertNull(koogProvider(AuthSource.NoAuth(info, scope)))
    }

    @Test
    fun compatibleProvidersAcceptUserOriginsOverHttpsOrLoopbackOnly() {
        listOf(KoogProvider.OpenAICompatible, KoogProvider.AnthropicCompatible).forEach { provider ->
            val custom = AuthScope(provider.id, EndpointOrigin("https://llm.example:8443"))
            assertEquals(provider, koogProvider(key.copy(scope = custom)))
            listOf("http://localhost:8000", "http://127.0.0.1", "http://[::1]:4000").forEach { origin ->
                assertEquals(provider, koogProvider(key.copy(scope = custom.copy(origin = EndpointOrigin(origin)))))
            }
            listOf("http://llm.example", "http://192.168.1.10:8000", "http://localhost.example").forEach { origin ->
                assertNull(koogProvider(key.copy(scope = custom.copy(origin = EndpointOrigin(origin)))))
            }
            assertNull(koogProvider(AuthSource.NoAuth(info, custom)))
        }
        assertNull(koogProvider(key.copy(scope = scope.copy(origin = EndpointOrigin("https://llm.example")))))
    }

    @Test
    fun cliAndHelperSourcesAreNeverConsumed() {
        val location = AuthLocationId("external")
        assertNull(koogProvider(AuthSource.CliLogin(info, scope, AuthOwnerId("koog"), location)))
        assertNull(koogProvider(AuthSource.ExternalKey(info, scope, location)))
        assertNull(koogProvider(AuthSource.CredentialHelper(info, scope, location)))
    }

    @Test
    fun connectionCannotNameForeignEngineOrSource() {
        val binding = EngineBinding(EngineBindingId("binding"), KoogEngineId, info.id)
        assertFailsWith<IllegalArgumentException> { KoogConnection(binding.copy(engine = EngineId("foreign")), key) }
        assertFailsWith<IllegalArgumentException> {
            KoogConnection(binding.copy(authSource = AuthSourceId("different")), key)
        }
        assertFalse(KoogEngineEnabled.default)
    }
}
