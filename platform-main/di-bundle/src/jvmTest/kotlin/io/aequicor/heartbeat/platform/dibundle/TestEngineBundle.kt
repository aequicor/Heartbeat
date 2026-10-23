package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import kotlin.reflect.KClass

/** What the test engine bundle publishes at the application level: the adapter's own toggle. */
interface TestEngineAppExports {
    val featureToggles: Set<FeatureToggle<*>>
}

/** What the test engine bundle publishes for a profile: the scripted adapter, to be registered by the facade. */
interface TestEngineProfileExports {
    val engineRegistrations: Set<EngineRegistration>
}

/**
 * A bundle that only the tests load: it brings the scripted [TestAdapter] to the `engines` bundle the way a third-party
 * adapter would, through the exports the facade imports. It has no Metro graph, the exports are plain objects.
 */
class TestEngineBundle : BundleEntry {
    override val id: BundleId = BundleId("test-engine")

    override val importsType: KClass<*>? = null

    override val exportTypes: List<KClass<*>> = listOf(TestEngineAppExports::class)

    override fun createGraph(foundation: Any, imports: Any): Any = object : TestEngineAppExports {
        override val featureToggles: Set<FeatureToggle<*>> = setOf(TestAdapter.toggle)
    }

    override val hasProfileGraph: Boolean = true

    override val profileExportTypes: List<KClass<*>> = listOf(TestEngineProfileExports::class)

    override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
        object : TestEngineProfileExports {
            override val engineRegistrations: Set<EngineRegistration> get() = setOf(TestAdapter.registration)
        }
}
