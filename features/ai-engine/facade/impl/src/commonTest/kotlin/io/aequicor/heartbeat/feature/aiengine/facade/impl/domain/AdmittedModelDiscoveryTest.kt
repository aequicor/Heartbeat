package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeModelDiscovery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdmittedModelDiscoveryTest {
    @Test
    fun `unknown compatibility does not bind or discover and keeps the cache`() = runTest {
        val fixture = RouteFixture(this)
        fixture.factory.isAdmittedRuntimeRequiredForModels = true
        fixture.factory.isCandidateAvailable = false
        val cache = FakeModelCache()
        val old = CachedModels(TestEngine, fixture.binding.id, emptyList(), fixture.clock.now)
        cache.entries.value = listOf(old)
        val pool = RuntimePool(fixture.context, { _, _ -> false }, { _, _ -> })
        val catalog = ModelCatalogService(cache, fixture.routes, fixture.context, runtimePool = pool)

        assertEquals(
            EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown),
            assertFailsWith<EngineException> { catalog.refresh(TestEngine, fixture.binding.id) }.failure,
        )
        assertTrue(fixture.factory.routes.isEmpty())
        assertEquals(0, fixture.factory.modelDiscoveries)
        assertEquals(0, fixture.factory.promotions)
        assertEquals(listOf(old), cache.entries.value)
        pool.closeAll()
    }

    @Test
    fun `discovery uses the promoted runtime and the checked binding`() = runTest {
        val fixture = RouteFixture(this)
        fixture.factory.isAdmittedRuntimeRequiredForModels = true
        val model = ModelInfo(fixture.target, "Model")
        val seen = mutableListOf<EngineBindingId>()
        fixture.factory.runtime = { identity ->
            object : EngineRuntime by FakeRuntime(identity, supports = false) {
                override val features = FeatureTable(
                    mapOf(
                        RuntimeModelDiscovery.id to available(
                            object : RuntimeModelDiscovery {
                                override suspend fun discover(binding: EngineBindingId): List<ModelInfo> {
                                    seen += binding
                                    return listOf(model)
                                }
                            },
                        ),
                    ),
                )
            }
        }
        val cache = FakeModelCache()
        val pool = RuntimePool(fixture.context, { _, _ -> false }, { _, _ -> })
        val catalog = ModelCatalogService(cache, fixture.routes, fixture.context, runtimePool = pool)

        assertEquals(listOf(model), catalog.refresh(TestEngine, fixture.binding.id).models)
        assertEquals(listOf(fixture.binding.id), seen)
        assertEquals(1, fixture.factory.promotions)
        assertEquals(0, fixture.factory.modelDiscoveries)
        assertEquals(listOf(model), cache.entries.value.single().models)
        pool.closeAll()
    }

    @Test
    fun `source rotation during discovery does not cache its result`() = runTest {
        val fixture = RouteFixture(this)
        fixture.factory.isAdmittedRuntimeRequiredForModels = true
        fixture.factory.runtime = { identity ->
            object : EngineRuntime by FakeRuntime(identity, supports = false) {
                override val features = FeatureTable(
                    mapOf(
                        RuntimeModelDiscovery.id to available(
                            object : RuntimeModelDiscovery {
                                override suspend fun discover(binding: EngineBindingId): List<ModelInfo> {
                                    fixture.sources.remove(fixture.source.info.id)
                                    fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))
                                    return listOf(ModelInfo(fixture.target, "Model"))
                                }
                            },
                        ),
                    ),
                )
            }
        }
        val cache = FakeModelCache()
        val pool = RuntimePool(fixture.context, { _, _ -> false }, { _, _ -> })
        val catalog = ModelCatalogService(cache, fixture.routes, fixture.context, runtimePool = pool)

        assertFailsWith<EngineException> { catalog.refresh(TestEngine, fixture.binding.id) }
        assertTrue(cache.entries.value.isEmpty())
        pool.closeAll()
    }
}
