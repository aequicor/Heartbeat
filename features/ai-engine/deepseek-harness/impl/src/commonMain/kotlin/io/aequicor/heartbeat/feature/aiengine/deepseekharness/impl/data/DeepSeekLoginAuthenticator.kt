package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.Authenticator
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekLogin

/** Explicit read-only local login check; it does not attest service acceptance or import credentials. */
internal class DeepSeekLoginAuthenticator(private val inspect: suspend () -> DeepSeekLogin) : Authenticator {
    override val id = Id

    override fun supports(source: AuthSource): Boolean = source is AuthSource.CliLogin &&
        source.owner == DeepSeekEngine.AuthOwner && source.location == DeepSeekEngine.AuthLocation &&
        source.scope.basePath == null && source.scope.provider.value == "deepseek" &&
        source.scope.origin.value == "https://api.deepseek.com"

    override suspend fun check(source: AuthSource, context: AuthContextKey) = run {
        require(supports(source) && context == DeepSeekEngine.AuthContext)
        // Preserve the owner observation, including Unknown when account identity cannot be established.
        inspect().check.copy(source = source.info.id, context = context)
    }

    companion object {
        val Id = AuthenticatorId("deepseek.harness.local")
    }
}
