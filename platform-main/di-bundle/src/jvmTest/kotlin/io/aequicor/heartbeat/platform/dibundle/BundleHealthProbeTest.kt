package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BindingKey
import io.aequicor.heartbeat.core.bundles.BundleHealth
import kotlin.test.Test
import kotlin.test.assertEquals

/** The smoke of a processed train reads the worse of the app and profile level: a slip would turn it green. */
class BundleHealthProbeTest {
    private val failed = BundleHealth.Failed(IllegalStateException("boom"))
    private val degraded = BundleHealth.Degraded(listOf(BindingKey("x")))

    @Test
    fun `healthy at both levels is healthy`() {
        assertEquals(BundleHealth.Healthy, worseHealth(BundleHealth.Healthy, BundleHealth.Healthy))
    }

    @Test
    fun `a failed profile graph fails the bundle whatever the app level is`() {
        assertEquals(failed, worseHealth(BundleHealth.Healthy, failed))
        assertEquals(failed, worseHealth(degraded, failed))
    }

    @Test
    fun `a failed app level wins over the profile level`() {
        assertEquals(failed, worseHealth(failed, BundleHealth.Healthy))
        assertEquals(failed, worseHealth(failed, degraded))
    }

    @Test
    fun `degraded beats healthy from either level`() {
        assertEquals(degraded, worseHealth(degraded, BundleHealth.Healthy))
        assertEquals(degraded, worseHealth(BundleHealth.Healthy, degraded))
    }
}
