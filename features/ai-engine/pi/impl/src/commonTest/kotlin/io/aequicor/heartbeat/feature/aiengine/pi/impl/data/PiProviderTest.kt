package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiProviderTest {
    private val source = AuthSource.ManagedKey(
        AuthSourceInfo(AuthSourceId("source"), "Key", AuthRevision.Known("1")),
        AuthScope(ProviderId("anthropic"), EndpointOrigin("https://api.anthropic.com")),
        AuthSecretId("vault_key"),
    )
    private val context = EngineContext(PiEngineId, EngineBindingId("binding"), model = ModelId("anthropic/model"))

    @Test
    fun exactProviderAndOriginAcceptManagedKey() {
        assertTrue(acceptsPi(source, context))
    }

    @Test
    fun foreignEngineModelEndpointAndCliLoginAreRejected() {
        assertFalse(acceptsPi(source, context.copy(engine = EngineId("other"))))
        assertFalse(acceptsPi(source, context.copy(model = ModelId("openai/model"))))
        assertFalse(
            acceptsPi(source.copy(scope = source.scope.copy(origin = EndpointOrigin("https://example.com"))), context),
        )
        assertFalse(
            acceptsPi(
                AuthSource.CliLogin(source.info, source.scope, AuthOwnerId("other"), AuthLocationId("cli")),
                context,
            ),
        )
    }
}
