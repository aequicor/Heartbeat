package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.Authenticator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FixedClock(var now: Instant = Instant.fromEpochSeconds(100)) : Clock {
    override fun now(): Instant = now
}

/** CLI-owned authenticator whose verdict or failure is set by the test. */
private class CliAuthenticator(private val clock: Clock) : Authenticator {
    var failure: Exception? = null
    var calls = 0
    var onCheck: suspend () -> Unit = {}

    override val id = AuthenticatorId("codex.cli")

    override fun supports(source: AuthSource) = source is AuthSource.CliLogin

    override suspend fun check(source: AuthSource, context: AuthContextKey): AuthCheck {
        calls++
        onCheck()
        failure?.let { throw it }
        return AuthCheck(
            source.info.id,
            context,
            AuthVerdict.Authenticated,
            AuthCheckBasis.CliStatus,
            clock.now(),
            source.info.revision,
        )
    }
}

class AuthCheckRunnerTest {
    private val clock = FixedClock()
    private val vault = FakeVault()
    private val store = FakeSourceStore()
    private val context = AuthContextKey("codex.default")
    private val cli = CliAuthenticator(clock)
    private var counter = 0

    private fun TestScope.fixture(): Pair<AuthSourceRegistry, AuthCheckRunner> {
        val registry = AuthSourceRegistry(store, vault, backgroundScope) { "t${++counter}" }
        val authenticators = setOf(ManagedKeyAuthenticator(vault, clock), NoAuthAuthenticator(clock), cli)
        val builtIns = setOf(ManagedKeyAuthenticator.Id, NoAuthAuthenticator.Id)
        return registry to AuthCheckRunner(registry, authenticators, builtIns, clock)
    }

    private suspend fun AuthSourceRegistry.cliLogin() =
        register(AuthSourceDraft.CliLogin("cli", TestAuthScope, AuthOwnerId("codex"), AuthLocationId("default")))

    @Test
    fun `managed key is checked locally against the vault`() = runTest {
        val (registry, checks) = fixture()
        val source = registry.addManagedKey("work", TestAuthScope, Secret("v".toCharArray()))

        val present = checks.check(source.info.id, context)
        assertEquals(AuthVerdict.Authenticated, present.verdict)
        assertEquals(AuthCheckBasis.Local, present.basis)

        vault.values.clear()
        assertEquals(AuthVerdict.NeedsLogin, checks.check(source.info.id, context).verdict)
        assertEquals(AuthVerdict.NeedsLogin, checks.last(source.info.id, context)?.verdict)
    }

    @Test
    fun `CLI logins are verified only by a requested owner authenticator`() = runTest {
        val (registry, checks) = fixture()
        val source = registry.cliLogin()

        assertEquals(AuthVerdict.Unknown, checks.check(source.info.id, context).verdict)
        assertEquals(0, cli.calls)

        val verified = checks.check(source.info.id, context, setOf(cli.id))
        assertEquals(AuthVerdict.Authenticated, verified.verdict)
        assertEquals(AuthCheckBasis.CliStatus, verified.basis)
    }

    @Test
    fun `failed check keeps the previous verdict as stale`() = runTest {
        val (registry, checks) = fixture()
        val source = registry.cliLogin()
        checks.check(source.info.id, context, setOf(cli.id))

        cli.failure = IllegalStateException("network down")
        val stale = checks.check(source.info.id, context, setOf(cli.id))

        assertEquals(AuthVerdict.Authenticated, stale.verdict)
        assertTrue(stale.isStale)
    }

    @Test
    fun `stale fallback does not survive a revision change`() = runTest {
        val (registry, checks) = fixture()
        val source = registry.cliLogin()
        checks.check(source.info.id, context, setOf(cli.id))
        registry.updateRevision(source.info.id, AuthRevision.Known("account-2"))

        cli.failure = IllegalStateException("network down")
        val stale = checks.check(source.info.id, context, setOf(cli.id))

        assertEquals(AuthVerdict.Unknown, stale.verdict)
        assertEquals(AuthRevision.Known("account-2"), stale.revision)
        assertTrue(stale.isStale)
    }

    @Test
    fun `unknown source is reported as unavailable`() = runTest {
        val (_, checks) = fixture()
        val missing = AuthSourceId("src_missing")
        assertNull(checks.last(missing, context))
        assertEquals(AuthVerdict.SourceUnavailable, checks.check(missing, context).verdict)
    }

    @Test
    fun `a late answer for an old revision does not overwrite the current verdict`() = runTest {
        val (registry, checks) = fixture()
        val source = registry.cliLogin()
        cli.onCheck = { registry.updateRevision(source.info.id, AuthRevision.Known("account-2")) }

        val late = checks.check(source.info.id, context, setOf(cli.id))

        assertEquals(AuthRevision.Unknown, late.revision)
        assertNull(checks.last(source.info.id, context))
    }
}
