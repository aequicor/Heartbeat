package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeepSeekLoginAuthenticatorTest {
    private val source = AuthSource.CliLogin(
        AuthSourceInfo(AuthSourceId("profile-login"), "DSH", AuthRevision.Known("old")),
        AuthScope(ProviderId("deepseek"), EndpointOrigin("https://api.deepseek.com")),
        DeepSeekEngine.AuthOwner,
        DeepSeekEngine.AuthLocation,
    )

    @Test
    fun `only exact owned login is accepted without reading credentials`() {
        val authenticator = DeepSeekLoginAuthenticator { error("Must remain lazy") }
        assertTrue(authenticator.supports(source))
        assertFalse(authenticator.supports(source.copy(scope = source.scope.copy(basePath = "/other"))))
        assertFalse(authenticator.supports(source.copy(owner = AuthOwnerId("other"))))
        assertFalse(authenticator.supports(source.copy(location = AuthLocationId("other"))))
        val foreignProvider = source.copy(scope = source.scope.copy(provider = ProviderId("other")))
        assertFalse(authenticator.supports(foreignProvider))
        val foreignOrigin = source.copy(scope = source.scope.copy(origin = EndpointOrigin("https://other.test")))
        assertFalse(authenticator.supports(foreignOrigin))
    }

    @Test
    fun `local verdict names requested source and preserves observed account revision`() = runTest {
        for (verdict in listOf(AuthVerdict.Authenticated, AuthVerdict.NeedsLogin)) {
            val observed = AuthCheck(
                source = DeepSeekEngine.AuthSource,
                context = DeepSeekEngine.AuthContext,
                verdict = verdict,
                basis = AuthCheckBasis.Local,
                checkedAt = Instant.fromEpochMilliseconds(1),
                revision = AuthRevision.Known("current"),
            )
            val authenticator = DeepSeekLoginAuthenticator { DeepSeekLogin(source, observed) }
            val result = authenticator.check(source, DeepSeekEngine.AuthContext)
            assertEquals(observed.copy(source = source.info.id), result)
        }
    }

    @Test
    fun `foreign context is rejected before inspection and cancellation propagates`() = runTest {
        val authenticator = DeepSeekLoginAuthenticator { throw CancellationException("cancelled") }
        assertFailsWith<IllegalArgumentException> { authenticator.check(source, AuthContextKey("foreign")) }
        val foreignPath = source.copy(scope = source.scope.copy(basePath = "/other"))
        assertFailsWith<IllegalArgumentException> { authenticator.check(foreignPath, DeepSeekEngine.AuthContext) }
        assertFailsWith<CancellationException> { authenticator.check(source, DeepSeekEngine.AuthContext) }
    }
}
