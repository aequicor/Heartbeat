package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTogglesIntegrationTest {

    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val accessors = app as TestToggleAccessors

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `toggles contributed by modules are registered`() {
        assertTrue(TestToggles.Experiment in accessors.toggleControl.registered)
    }

    @Test
    fun `an override is stored in the app storage and read by features`() = runTest {
        accessors.toggleControl.setOverride(TestToggles.Experiment, true)

        assertEquals(true, accessors.featureToggles.get(TestToggles.Experiment))
        assertEquals(
            true,
            app.appStores.keyValue(KeyValueSpec("core_feature_toggles")).get(booleanKey(TestToggles.Experiment.key)),
        )
        assertEquals(
            ToggleState(TestToggles.Experiment, true, ToggleSource.LocalOverride),
            accessors.toggleControl.observeStates().first().single { it.toggle == TestToggles.Experiment },
        )
    }

    @Test
    fun `overrides are app-wide and survive a profile switch`() = runTest {
        app.profileSessions.open(ProfileId("alice"))
        accessors.toggleControl.setOverride(TestToggles.Experiment, true)

        app.profileSessions.open(ProfileId("bob"))

        assertEquals(true, accessors.featureToggles.observe(TestToggles.Experiment).first())
    }
}

object TestToggles {
    val Mode = FeatureToggle.Choice("test.mode", "Processing mode", listOf("Fast", "Deep"))
    val Experiment = FeatureToggle.Flag("test.experiment", "Experimental behaviour of the test feature")
}

/** A feature registers its toggles for the control panel. */
@ContributesTo(AppScope::class)
interface TestTogglesContribution {
    @Provides
    @IntoSet
    fun mode(): FeatureToggle<*> = TestToggles.Mode

    @Provides
    @IntoSet
    fun experiment(): FeatureToggle<*> = TestToggles.Experiment
}

/** Accessors to the toggle platform from the test. */
@ContributesTo(AppScope::class)
interface TestToggleAccessors {
    val featureToggles: FeatureToggles
    val toggleControl: FeatureToggleControl
}
