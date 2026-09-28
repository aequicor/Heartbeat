package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KoogDiscoveryTest {
    @Test
    fun profileCloseCancelsDiscoveryAndClosesClient() = runTest {
        val f = KoogTestFixture(this)
        val entered = CompletableDeferred<Unit>()
        f.beforeModels = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val caller = async {
            f.adapter.discoverModels(f.source, EngineContext(KoogEngineId, f.binding.id))
        }
        entered.await()
        f.profile.close()
        assertFailsWith<CancellationException> { caller.await() }
        assertEquals(1, f.executor.closed)
    }

    @Test
    fun profileCloseDuringSecretReadNeverOpensClient() = runTest {
        val f = KoogTestFixture(this)
        val source = AuthSource.ManagedKey(
            f.source.info,
            AuthScope(KoogProvider.OpenAI.id, KoogProvider.OpenAI.origin),
            AuthSecretId("test-key"),
        )
        f.connections.put(KoogConnection(f.binding, source))
        val entered = CompletableDeferred<Unit>()
        f.secrets.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val caller = async {
            f.adapter.discoverModels(source, EngineContext(KoogEngineId, f.binding.id))
        }
        entered.await()
        f.profile.close()
        assertFailsWith<CancellationException> { caller.await() }
        assertEquals(0, f.opens)
    }

    @Test
    fun callerCancellationClosesDiscoveryClient() = runTest {
        val f = KoogTestFixture(this)
        val entered = CompletableDeferred<Unit>()
        f.beforeModels = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val caller = async {
            f.adapter.discoverModels(f.source, EngineContext(KoogEngineId, f.binding.id))
        }
        entered.await()
        caller.cancel()
        caller.join()
        assertEquals(1, f.executor.closed)
    }

    @Test
    fun authContextAcceptsArbitraryBindingIdentifiers() = runTest {
        val f = KoogTestFixture(this)
        assertEquals(
            f.adapter.authContext(EngineContext(KoogEngineId, f.binding.id)),
            f.adapter.authContext(EngineContext(KoogEngineId, EngineBindingId("binding with spaces".repeat(20)))),
        )
    }
}
