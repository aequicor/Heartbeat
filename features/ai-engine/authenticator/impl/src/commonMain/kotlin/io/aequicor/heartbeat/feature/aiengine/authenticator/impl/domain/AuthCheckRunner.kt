package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.Authenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * [AuthChecks] over contributed authenticators. Requested ids are tried first, then [builtIns]; a CLI login is
 * never offered to a shared built-in. Observations are kept in memory per source and context: a check that
 * throws returns the previous verdict of the same revision marked stale, otherwise an explicit stale Unknown.
 */
class AuthCheckRunner(
    private val sources: AuthSources,
    authenticators: Set<Authenticator>,
    private val builtIns: Set<AuthenticatorId>,
    private val clock: Clock,
) : AuthChecks {
    private val log = Log.tag("AuthChecks")
    private val byId = authenticators.associateBy { it.id }
    private val observations = MutableStateFlow(emptyMap<Pair<AuthSourceId, AuthContextKey>, AuthCheck>())

    init {
        require(byId.size == authenticators.size) { "Duplicate authenticator id" }
    }

    override fun last(source: AuthSourceId, context: AuthContextKey): AuthCheck? = observations.value[source to context]

    override suspend fun check(
        source: AuthSourceId,
        context: AuthContextKey,
        authenticators: Set<AuthenticatorId>,
    ): AuthCheck {
        log.i { "check source=${source.value} context=${context.value}" }
        val resolved = sources.get(source)
        val authenticator = resolved?.let { found ->
            (authenticators + builtIns.filter { found !is AuthSource.CliLogin })
                .mapNotNull { byId[it] }
                .firstOrNull { it.supports(found) }
        }
        val result = when {
            resolved == null -> observation(source, context, AuthVerdict.SourceUnavailable, AuthRevision.Unknown)
            authenticator == null -> observation(source, context, AuthVerdict.Unknown, resolved.info.revision)
            else -> verify(authenticator, resolved, context)
        }
        return record(result)
    }

    private suspend fun verify(authenticator: Authenticator, source: AuthSource, context: AuthContextKey): AuthCheck {
        val result = try {
            authenticator.check(source, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(
                e,
            ) { "check failed, keeping stale verdict source=${source.info.id.value} by=${authenticator.id.value}" }
            return stale(source, context)
        }
        check(result.source == source.info.id && result.context == context) { "Authenticator answered another request" }
        return result
    }

    private fun stale(source: AuthSource, context: AuthContextKey): AuthCheck {
        val previous = last(source.info.id, context)?.takeIf { it.revision == source.info.revision }
        return previous?.copy(isStale = true)
            ?: observation(source.info.id, context, AuthVerdict.Unknown, source.info.revision).copy(isStale = true)
    }

    private fun observation(
        source: AuthSourceId,
        context: AuthContextKey,
        verdict: AuthVerdict,
        revision: AuthRevision,
    ) = AuthCheck(source, context, verdict, AuthCheckBasis.Local, clock.now(), revision)

    private fun record(check: AuthCheck): AuthCheck {
        log.i { "verdict source=${check.source.value} verdict=${check.verdict} stale=${check.isStale}" }
        observations.update { it + ((check.source to check.context) to check) }
        return check
    }
}

/** Shared local verification of Heartbeat-owned keys: presence in the vault, not service acceptance. */
class ManagedKeyAuthenticator(private val vault: ManagedKeyVault, private val clock: Clock) : Authenticator {
    override val id: AuthenticatorId = Id

    override fun supports(source: AuthSource): Boolean = source is AuthSource.ManagedKey

    override suspend fun check(source: AuthSource, context: AuthContextKey): AuthCheck {
        val managed = source as AuthSource.ManagedKey
        val verdict = if (vault.contains(managed.secret)) AuthVerdict.Authenticated else AuthVerdict.NeedsLogin
        return AuthCheck(source.info.id, context, verdict, AuthCheckBasis.Local, clock.now(), source.info.revision)
    }

    /** Stable id. */
    companion object {
        /** Id of the managed-key authenticator. */
        val Id: AuthenticatorId = AuthenticatorId("heartbeat.managed_key")
    }
}

/** Shared verification of endpoints that need no credentials. */
class NoAuthAuthenticator(private val clock: Clock) : Authenticator {
    override val id: AuthenticatorId = Id

    override fun supports(source: AuthSource): Boolean = source is AuthSource.NoAuth

    override suspend fun check(source: AuthSource, context: AuthContextKey): AuthCheck = AuthCheck(
        source.info.id,
        context,
        AuthVerdict.Authenticated,
        AuthCheckBasis.Local,
        clock.now(),
        source.info.revision,
    )

    /** Stable id. */
    companion object {
        /** Id of the no-auth authenticator. */
        val Id: AuthenticatorId = AuthenticatorId("heartbeat.no_auth")
    }
}
