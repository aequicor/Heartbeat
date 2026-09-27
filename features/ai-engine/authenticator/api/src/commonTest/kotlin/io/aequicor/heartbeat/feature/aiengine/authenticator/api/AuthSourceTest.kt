package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuthSourceTest {
    private val owner = AuthOwnerId("codex")
    private val scope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))
    private val info = AuthSourceInfo(AuthSourceId("source-1"), "private account", AuthRevision.Unknown)

    @Test
    fun `CLI login cannot be consumed by a different engine`() {
        val source = AuthSource.CliLogin(info, scope, owner, AuthLocationId("profile-1"))
        assertTrue(source.isVisibleTo(owner))
        assertFalse(source.isVisibleTo(AuthOwnerId("other")))
        assertEquals(source, Json.decodeFromString<AuthSource>(Json.encodeToString<AuthSource>(source)))
    }

    @Test
    fun `managed key can be shared without copying its secret`() {
        val source = AuthSource.ManagedKey(info, scope, AuthSecretId("vault-entry"))
        assertTrue(source.isVisibleTo(owner))
        assertTrue(source.isVisibleTo(AuthOwnerId("koog")))
        assertFalse(source.toString().contains("private account"))
    }

    @Test
    fun `origin rejects credentials path query fragment and default ports`() {
        listOf(
            "https://user:key@example.com",
            "https://example.com/path",
            "https://example.com?key=x",
            "https://example.com#x",
            "https://example.com:443",
            "https://example.com:0443",
            "http://example.com:080",
            "http://example.com:011434",
            "http://example.com:80",
            "https://example.com:65536",
        ).forEach { assertFailsWith<IllegalArgumentException> { EndpointOrigin(it) } }
        assertEquals("http://[::1]:11434", EndpointOrigin("http://[::1]:11434").value)
    }

    @Test
    fun `network staleness preserves the last authenticated verdict`() {
        val check = AuthCheck(
            info.id,
            AuthContextKey("context"),
            AuthVerdict.Authenticated,
            AuthCheckBasis.CliStatus,
            Instant.fromEpochSeconds(1),
            AuthRevision.Known("opaque-revision"),
        )
        val stale = check.copy(isStale = true)
        assertEquals(AuthVerdict.Authenticated, stale.verdict)
        assertEquals(check.revision, stale.revision)
        assertFalse(stale.toString().contains("opaque-revision"))
        assertEquals(stale, Json.decodeFromString<AuthCheck>(Json.encodeToString(stale)))
    }
}
