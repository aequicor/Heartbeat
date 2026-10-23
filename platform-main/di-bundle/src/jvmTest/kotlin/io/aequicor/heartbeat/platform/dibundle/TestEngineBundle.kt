package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import kotlin.reflect.KClass

/** What the test engine bundle publishes at the application level: the adapter's own toggle. */
interface TestEngineAppExports {
    val featureToggles: Set<FeatureToggle<*>>
}

/**
 * What the test engine bundle publishes for a profile: the scripted adapter, to be registered by the facade, and the
 * scheduler's test owner and observer. The owner and the observer are one per profile.
 */
interface TestEngineProfileExports {
    val engineRegistrations: Set<EngineRegistration>
    val scheduledHelperPromptOwners: Set<ScheduledHelperPromptOwner>
    val scheduledRequestOriginObservers: Set<ScheduledRequestOriginObserver>
    val helperPromptOwner: HelperPromptTestOwner
    val requestOriginObserver: RequestOriginTestObserver
}

/**
 * A bundle that only the tests load: it brings the scripted [TestAdapter] to the `engines` bundle and test
 * contributions to the scheduler of `agent-runtime` the way a third-party bundle would, through the exports they
 * import. It has no Metro graph, the exports are plain objects.
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
            override val helperPromptOwner = HelperPromptTestOwner()
            override val requestOriginObserver = RequestOriginTestObserver()
            override val engineRegistrations: Set<EngineRegistration> get() = setOf(TestAdapter.registration)
            override val scheduledHelperPromptOwners: Set<ScheduledHelperPromptOwner> = setOf(helperPromptOwner)
            override val scheduledRequestOriginObservers: Set<ScheduledRequestOriginObserver> =
                setOf(requestOriginObserver)
        }
}
