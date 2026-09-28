package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompatibleProtocolTest {
    @Test
    fun `plain HTTP is allowed only on the loopback interface`() {
        listOf(
            "https://api.example.com",
            "https://10.0.0.5:8443",
            "http://localhost",
            "http://localhost:8000",
            "http://127.0.0.1:4000",
            "http://[::1]",
            "http://[::1]:8080",
        ).forEach { assertTrue(isCompatibleOriginAllowed(EndpointOrigin(it)), it) }
        listOf(
            "http://example.com",
            "http://192.168.1.10:8000",
            "http://localhost.evil.com",
            "http://127.0.0.1.nip.io",
            "http://127.0.0.2",
            "http://[::2]:8080",
        ).forEach { assertFalse(isCompatibleOriginAllowed(EndpointOrigin(it)), it) }
    }

    @Test
    fun `api base follows the SDK convention of each protocol`() {
        fun scope(protocol: CompatibleProtocol, path: String?) =
            AuthScope(protocol.provider.id, EndpointOrigin("https://host"), path)

        assertEquals("/v1", CompatibleProtocol.OpenAI.apiBase(scope(CompatibleProtocol.OpenAI, null)))
        assertEquals("/api/v1", CompatibleProtocol.OpenAI.apiBase(scope(CompatibleProtocol.OpenAI, "/api/v1")))
        assertEquals("", CompatibleProtocol.Anthropic.apiBase(scope(CompatibleProtocol.Anthropic, null)))
        assertEquals("", CompatibleProtocol.Anthropic.apiBase(scope(CompatibleProtocol.Anthropic, "/v1")))
        val nested = scope(CompatibleProtocol.Anthropic, "/anthropic/v1")
        assertEquals("/anthropic", CompatibleProtocol.Anthropic.apiBase(nested))
        assertEquals("/foo-v1", CompatibleProtocol.Anthropic.apiBase(scope(CompatibleProtocol.Anthropic, "/foo-v1")))
    }

    @Test
    fun `lookup requires a compatible provider at an allowed origin`() {
        val allowed = EndpointOrigin("http://localhost:8000")
        CompatibleProtocol.entries.forEach {
            assertEquals(it, CompatibleProtocol.of(AuthScope(it.provider.id, allowed)))
            assertNull(CompatibleProtocol.of(AuthScope(it.provider.id, EndpointOrigin("http://example.com"))))
        }
        assertNull(CompatibleProtocol.of(AuthScope(ProviderId("openai"), EndpointOrigin("https://api.openai.com"))))
    }

    @Test
    fun `compatible methods edit both origin and path`() {
        CompatibleProtocol.entries.forEach {
            assertTrue(it.method.isOriginEditable && it.method.isPathEditable)
            assertTrue(isCompatibleOriginAllowed(it.suggestedOrigin))
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionMethod.ApiKey(
                ConnectionMethodId("fixed"),
                CompatibleProtocol.OpenAI.provider,
                EndpointOrigin("https://host"),
                isPathEditable = true,
            )
        }
    }
}
