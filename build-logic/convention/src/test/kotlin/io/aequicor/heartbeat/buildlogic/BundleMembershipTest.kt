package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleMembershipTest {
    @Test
    fun `impl assembled by a single bundle is fine`() {
        val membership = mapOf(
            ":bundles:web" to listOf(":features:browser:impl", ":features:plant-uml-support:impl"),
            ":bundles:studio" to listOf(":features:ai-studio:impl"),
        )
        assertTrue(bundleMembershipViolations(membership).isEmpty())
    }

    @Test
    fun `impl assembled by two bundles is reported once per module`() {
        val membership = mapOf(
            ":bundles:web" to listOf(":features:browser:impl", ":features:browser:impl"),
            ":bundles:studio" to listOf(":features:browser:impl", ":features:ai-studio:impl"),
            ":bundles:shell" to listOf(":features:browser:impl"),
        )
        assertEquals(
            listOf(":features:browser:impl is assembled by :bundles:shell, :bundles:studio, :bundles:web"),
            bundleMembershipViolations(membership),
        )
    }
}
