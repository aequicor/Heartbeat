package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeLogin
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Clock

@Inject
internal class ClaudeAccount(private val transport: ClaudeTransport, private val clock: Clock) {
    private val log = Log.tag("ClaudeAccount")

    suspend fun inspect(): ClaudeLogin = withProbeTimeout {
        log.i { "Checking Claude CLI login" }
        val output = StringBuilder()
        val exit = transport.run(listOf("auth", "status")) {
            if (output.length + it.length > MAX_AUTH_CHARS) protocolFailure()
            output.append(it)
            false
        }
        val status = parseClaudeObject(output.toString())
        val isLoggedIn = status.text("loggedIn") == "true"
        if (exit !in 0..1 || (exit == 0) != isLoggedIn) protocolFailure()
        if (isLoggedIn && status.text("authMethod") != "claude.ai") authFailure(AuthFailureReason.AuthMismatch)
        val revision = revision(status, isLoggedIn)
        log.i { "Claude CLI login checked loggedIn=$isLoggedIn" }
        val source = AuthSource.CliLogin(
            AuthSourceInfo(ClaudeEngine.AuthSource, "Claude Code", revision),
            AuthScope(ProviderId("anthropic"), EndpointOrigin("https://api.anthropic.com")),
            ClaudeEngine.AuthOwner,
            ClaudeEngine.AuthLocation,
        )
        ClaudeLogin(
            source,
            AuthCheck(
                source.info.id,
                ClaudeEngine.AuthContext,
                if (isLoggedIn) AuthVerdict.Authenticated else AuthVerdict.NeedsLogin,
                AuthCheckBasis.CliStatus,
                clock.now(),
                revision,
            ),
        )
    }

    private fun revision(status: kotlinx.serialization.json.JsonObject, isLoggedIn: Boolean): AuthRevision {
        val account = status.text("email")
        val organization = status.text("orgId")
        if (isLoggedIn && account.isNullOrBlank()) authFailure(AuthFailureReason.SourceUnavailable)
        return if (isLoggedIn) {
            val identity = listOf(account, organization, status.text("authMethod")).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            AuthRevision.Known(digest.joinToString("") { "%02x".format(Locale.ROOT, it) })
        } else {
            AuthRevision.Unknown
        }
    }

    suspend fun validate(revision: AuthRevision) {
        val current = inspect()
        if (current.check.verdict != AuthVerdict.Authenticated) authFailure(AuthFailureReason.NotAuthenticated)
        if (revision !is AuthRevision.Known || current.check.revision != revision) {
            authFailure(AuthFailureReason.SourceChanged)
        }
    }
}

internal fun authFailure(reason: AuthFailureReason): Nothing = throw EngineException(
    EngineFailure.Authentication(AuthFailure(reason, ClaudeEngine.AuthSource)),
)

/**
 * Bounds a CLI probe. `withTimeout` would surface as a `CancellationException` and be mistaken for caller
 * cancellation, so an expired probe is reported as a transport timeout instead.
 */
internal suspend fun <T : Any> withProbeTimeout(block: suspend CoroutineScope.() -> T): T =
    withTimeoutOrNull(PROBE_TIMEOUT_MS, block)
        ?: throw EngineException(EngineFailure.Transport(TransportFailureReason.Timeout))

internal const val PROBE_TIMEOUT_MS = 30_000L
private const val MAX_AUTH_CHARS = 32 * 1024
