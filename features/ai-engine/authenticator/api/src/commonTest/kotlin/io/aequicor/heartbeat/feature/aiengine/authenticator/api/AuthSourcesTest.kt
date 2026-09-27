package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import io.aequicor.heartbeat.core.secrets.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuthSourcesTest {
    @Test
    fun `user input is canonicalized to an origin`() {
        assertEquals(EndpointOrigin("https://open.cherryin.net"), canonicalOrigin(" HTTPS://Open.CherryIN.net/ "))
        assertEquals(EndpointOrigin("http://localhost:11434"), canonicalOrigin("http://localhost:11434"))
        assertEquals(EndpointOrigin("https://api.example.com"), canonicalOrigin("https://api.example.com:443"))
        assertEquals(EndpointOrigin("http://[::1]:8080"), canonicalOrigin("http://[::1]:8080/"))
    }

    @Test
    fun `input with parts an origin cannot hold is rejected`() {
        listOf(
            "https://user:pass@api.example.com",
            "https://api.example.com/v1",
            "https://api.example.com?x=1",
            "ftp://api.example.com",
            "api.example.com",
            "http://localhost:0",
            "http://localhost:070",
            "http://localhost:70000",
            "",
        ).forEach { assertNull(canonicalOrigin(it), it) }
    }

    @Test
    fun `requests never reveal labels or keys`() {
        val scope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.openai.com"))
        Secret("sk-secret".toCharArray()).use { key ->
            val requests = listOf(
                NewAuthSource.ManagedKey("private label", scope, key),
                NewAuthSource.CliLogin("private label", scope, AuthOwnerId("codex"), AuthLocationId("codex.local")),
                NewAuthSource.NoAuth("private label", scope),
            )
            requests.forEach {
                assertFalse(it.toString().contains("private label"))
                assertFalse(it.toString().contains("sk-secret"))
            }
        }
    }
}
