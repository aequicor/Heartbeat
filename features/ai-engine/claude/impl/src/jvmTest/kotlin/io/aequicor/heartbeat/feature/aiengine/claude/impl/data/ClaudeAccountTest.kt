package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ClaudeAccountTest {
    @Test
    fun `CLI account fingerprint is opaque and changes with account`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val first = fixture.account.inspect()
        assertFalse(first.check.revision.toString().contains(fixture.transport.account))
        fixture.transport.account = "another@example.test"
        assertNotEquals(first.check.revision, fixture.account.inspect().check.revision)
    }

    @Test
    fun `logged out account is not treated as successful authentication`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.loggedIn = false
        assertEquals(AuthVerdict.NeedsLogin, fixture.account.inspect().check.verdict)
    }

    @Test
    fun `API key route cannot replace selected CLI login`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.method = "api_key"
        assertFailsWith<EngineException> { fixture.account.inspect() }
    }
}
