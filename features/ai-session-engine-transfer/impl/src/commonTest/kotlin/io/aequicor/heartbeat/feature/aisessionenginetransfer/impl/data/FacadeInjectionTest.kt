package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the Metro contract the facade adapters rely on: an optional non-null parameter receives an installed
 * binding and falls back to [MissingEngineFacade] otherwise (a nullable parameter would be a separate key).
 */
class FacadeInjectionTest {
    @Test
    fun `an installed facade binding reaches an optional facade parameter`() {
        assertIs<FakeEngineFacade>(createGraph<InstalledFacadeGraph>().consumer.facade)
    }

    @Test
    fun `without a binding the missing facade is used`() {
        assertSame(MissingEngineFacade, createGraph<NoFacadeGraph>().consumer.facade)
    }
}

@Inject
internal class OptionalFacadeConsumer(val facade: EngineFacade = MissingEngineFacade)

@DependencyGraph
internal interface InstalledFacadeGraph {
    val consumer: OptionalFacadeConsumer

    @Provides
    fun provideFacade(): EngineFacade = FakeEngineFacade()
}

@DependencyGraph
internal interface NoFacadeGraph {
    val consumer: OptionalFacadeConsumer
}
