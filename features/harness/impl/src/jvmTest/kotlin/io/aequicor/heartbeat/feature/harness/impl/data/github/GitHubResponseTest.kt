package io.aequicor.heartbeat.feature.harness.impl.data.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubResponseTest {
    @Test
    fun `included headers preserve pagination and empty response`() {
        val response = decodeGitHubResponse("HTTP/2.0 204 No Content\r\nX-RateLimit-Remaining: 0\r\n\r\n")
        assertEquals(204, response.status)
        assertEquals("0", response.headers["x-ratelimit-remaining"])
        assertNull(response.body)
        assertEquals(0, decodeGitHubResponse("private gh error without HTTP status").status)
    }
}
